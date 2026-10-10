package com.example.injector.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用弹窗包解析器：兼容社区各种弹窗包格式。
 *
 * 社区格式差异很大：
 * - 声明文件名任意（smali.txt / 调用.txt / hook.smali / 说明.md）
 * - dex 命名任意（classes.dex / classes0.dex / classes2.dex / 中文名.dex）
 * - 存在嵌套 zip、嵌套目录（变体包）
 * - 中文文件名编码不可靠
 *
 * 因此全部按内容识别：dex 用魔数，声明用 smali 指令关键字。
 */
public final class PopupPackage {

    private static final byte[] DEX_MAGIC = {'d', 'e', 'x', '\n'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};
    private static final long MAX_TEXT_SIZE = 1024 * 1024;

    private static final Pattern INVOKE_LINE = Pattern.compile(
            "^invoke-(static|virtual|direct|super|interface)(/range)?\\s");
    private static final Pattern ASSIGN_LINE = Pattern.compile(
            "^(const|new-instance|sget|sput|iget|iput|move-exception|move-object|move)\\S*\\s");
    private static final Pattern MOVE_RESULT = Pattern.compile("^move-result(-\\w+)?\\s");
    private static final Pattern IGNORE_STRUCT = Pattern.compile(
            "^(\\.(line|registers|locals|param|prologue|end|catch|annotation|local|restart|packed-switch|sparse-switch|array-data)|#)");
    private static final Pattern ENTRY_REF = Pattern.compile(
            "(L[\\w/$]+;)->([\\w$<>]+)\\(");

    public final String name;
    public final File rootDir;
    public final List<File> dexFiles = new ArrayList<>();
    /** 每段为可执行指令序列（可能多行：const-string + invoke + move-result） */
    public final List<List<String>> snippets = new ArrayList<>();
    public File assetsDir;
    public final List<File> textFiles = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public String source = "external";

    private PopupPackage(String name, File rootDir) {
        this.name = name;
        this.rootDir = rootDir;
    }

    public int invokeCount() {
        return snippets.size();
    }

    public boolean injectable() {
        return !dexFiles.isEmpty() && !snippets.isEmpty();
    }

    /** 首个调用点的 类#方法，用于 UI 自动填充；类名为点分 Java 名（已剥离 L 前缀与 ; 后缀）；无则返回 null */
    public String[] primaryEntry() {
        for (List<String> sn : snippets) {
            for (String line : sn) {
                if (INVOKE_LINE.matcher(line).find()) {
                    Matcher m = ENTRY_REF.matcher(line);
                    if (m.find()) {
                        String cls = m.group(1);
                        if (cls.startsWith("L") && cls.endsWith(";")) {
                            cls = cls.substring(1, cls.length() - 1);
                        }
                        return new String[]{cls.replace('/', '.'), m.group(2)};
                    }
                }
            }
        }
        return null;
    }

    /**
     * 从 zip 文件解析（宽容模式：坏条目跳过、GBK 文件名回退、嵌套变体展开）。
     * 返回 0..N 个弹窗包。
     */
    public static List<PopupPackage> parseZip(File zipFile, String fallbackName,
                                              File workBase) throws IOException {
        File stage = new File(workBase, "pkg_" + System.currentTimeMillis());
        stage.mkdirs();
        unzipTolerant(zipFile, stage);
        return expand(stage, fallbackName, workBase, 0);
    }

    /** 宽容解压：ZipFile 按 central directory 逐条目解压，损坏条目跳过 */
    public static void unzipTolerant(File zipFile, File targetDir) throws IOException {
        java.util.zip.ZipFile zf;
        try {
            zf = new java.util.zip.ZipFile(zipFile, StandardCharsets.UTF_8);
        } catch (IOException badName) {
            zf = new java.util.zip.ZipFile(zipFile, Charset.forName("GBK"));
        }
        try (java.util.zip.ZipFile zfile = zf) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zfile.entries();
            byte[] buf = new byte[8192];
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                try {
                    File f = new File(targetDir, e.getName());
                    String canonical = f.getCanonicalPath();
                    String base = targetDir.getCanonicalPath();
                    if (!canonical.equals(base) && !canonical.startsWith(base + File.separator)) {
                        continue;
                    }
                    if (e.isDirectory()) {
                        f.mkdirs();
                        continue;
                    }
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (InputStream in = zfile.getInputStream(e);
                         FileOutputStream os = new FileOutputStream(f)) {
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                } catch (IOException badEntry) {
                    // 跳过损坏条目
                }
            }
        }
    }

    private static List<PopupPackage> expand(File dir, String name, File workBase,
                                             int depth) throws IOException {
        List<PopupPackage> out = new ArrayList<>();
        if (depth > 6) {
            PopupPackage p = new PopupPackage(name, dir);
            p.warnings.add("嵌套层级过深，已停止展开");
            out.add(p);
            return out;
        }
        File[] children = dir.listFiles();
        if (children == null) children = new File[0];

        // 1) 本目录存在嵌套 zip 且自身无 dex => 展开每个 zip 为变体（排除 apk 成品）
        List<File> nestedZips = new ArrayList<>();
        for (File c : children) {
            if (c.isFile() && startsWith(c, ZIP_MAGIC)
                    && !c.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                nestedZips.add(c);
            }
        }
        boolean hasLocalDex = false;
        for (File c : children) {
            if (c.isFile() && startsWith(c, DEX_MAGIC)) {
                hasLocalDex = true;
                break;
            }
        }
        if (!hasLocalDex && !nestedZips.isEmpty()) {
            int idx = 0;
            for (File z : nestedZips) {
                idx++;
                File sub = new File(workBase, "pkg_" + System.currentTimeMillis()
                        + "_" + idx + "_" + stripExt(z.getName()));
                sub.mkdirs();
                try (InputStream zin = new FileInputStream(z)) {
                    ZipSafety.unzip(zin, sub);
                } catch (Exception e) {
                    // 忽略损坏的嵌套 zip
                }
                String subName = name + "-" + stripExt(z.getName());
                out.addAll(expand(sub, subName, workBase, depth + 1));
            }
            return out;
        }

        // 2) 本目录无 dex 且仅一个子目录 => 深入（社区包常见单层包裹）
        List<File> subDirs = new ArrayList<>();
        for (File c : children) {
            if (c.isDirectory()) subDirs.add(c);
        }
        if (!hasLocalDex && subDirs.size() == 1 && nestedZips.isEmpty()) {
            return expand(subDirs.get(0), name, workBase, depth + 1);
        }
        // 3) 本目录无 dex 且多个子目录：递归每个子目录
        if (!hasLocalDex && !subDirs.isEmpty()) {
            for (File sd : subDirs) {
                out.addAll(expand(sd, name + "-" + sd.getName(), workBase, depth + 1));
            }
            // 过滤无 dex 且无声明的空包（纯资源/源码目录）
            List<PopupPackage> usable = new ArrayList<>();
            for (PopupPackage p : out) {
                if (!p.dexFiles.isEmpty() || !p.snippets.isEmpty()) usable.add(p);
            }
            return usable;
        }

        // 4) 本目录作为弹窗包：收集 dex / 声明 / assets
        PopupPackage pkg = new PopupPackage(name, dir);
        collectFiles(dir, pkg, true);
        pkg.warnings.addAll(validate(pkg));
        // 纯资源空目录（无 dex 无声明）不作为弹窗包输出
        if (pkg.dexFiles.isEmpty() && pkg.snippets.isEmpty()) {
            return out;
        }
        out.add(pkg);
        return out;
    }

    private static void collectFiles(File dir, PopupPackage pkg, boolean topLevel) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                if (topLevel && isAssetsDir(c)) {
                    pkg.assetsDir = c;
                    collectAssetsTexts(c, pkg);
                    continue;
                }
                // 弹窗包已在顶层收集到 dex 时，不深入子目录（避免把源码目录当资源）
                if (pkg.dexFiles.isEmpty() || !topLevel) {
                    collectFiles(c, pkg, false);
                }
                continue;
            }
            if (startsWith(c, DEX_MAGIC)) {
                pkg.dexFiles.add(c);
            } else if (isTextFile(c)) {
                pkg.textFiles.add(c);
                extractSnippets(c, pkg);
            } else if (startsWith(c, ZIP_MAGIC)) {
                // 顶层 zip 在 expand 已处理；此处忽略残余
            }
        }
    }

    private static void collectAssetsTexts(File assets, PopupPackage pkg) {
        File[] children = assets.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) {
                collectAssetsTexts(c, pkg);
            } else if (isEditableText(c)) {
                pkg.textFiles.add(c);
            }
        }
    }

    private static boolean isAssetsDir(File dir) {
        String n = dir.getName();
        return "assets".equalsIgnoreCase(n);
    }

    private static List<String> validate(PopupPackage pkg) {
        List<String> warns = new ArrayList<>();
        if (pkg.dexFiles.isEmpty()) {
            warns.add("未找到 dex 文件");
        }
        if (pkg.snippets.isEmpty()) {
            warns.add("未找到 smali 调用声明（无 invoke 指令）");
        }
        for (File t : pkg.textFiles) {
            String s = safeRead(t, 64 * 1024).toLowerCase(Locale.ROOT);
            if (s.contains("<provider") || s.contains("android:authorities")) {
                warns.add("该包需要向宿主 manifest 注入 provider，当前版本未支持（部分功能可能不可用）");
                break;
            }
        }
        return warns;
    }

    /**
     * 从声明文本提取插入片段：以 invoke 为锚点，向前收集赋值行、向后收集 move-result。
     */
    private static void extractSnippets(File f, PopupPackage pkg) {
        String text = safeRead(f, MAX_TEXT_SIZE);
        if (text.isEmpty()) return;
        List<String> pending = new ArrayList<>();
        boolean sawInvokeInPending = false;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (IGNORE_STRUCT.matcher(line).find()) {
                // 结构指令/注释：作为段落分隔
                if (sawInvokeInPending) {
                    pending.clear();
                    sawInvokeInPending = false;
                }
                continue;
            }
            if (INVOKE_LINE.matcher(line).find()) {
                if (sawInvokeInPending) {
                    pending.clear();
                    sawInvokeInPending = false;
                }
                List<String> snip = new ArrayList<>(pending);
                snip.add(line);
                pkg.snippets.add(snip);
                pending.clear();
                sawInvokeInPending = true;
            } else if (MOVE_RESULT.matcher(line).find()) {
                if (!pkg.snippets.isEmpty() && sawInvokeInPending) {
                    pkg.snippets.get(pkg.snippets.size() - 1).add(line);
                } else if (!pending.isEmpty()) {
                    pending.add(line);
                }
            } else if (ASSIGN_LINE.matcher(line).find()) {
                if (sawInvokeInPending) {
                    pending.clear();
                    sawInvokeInPending = false;
                }
                pending.add(line);
            } else {
                // 其它指令（return/goto/if 等）忽略
            }
        }
    }

    private static boolean isTextFile(File f) {
        if (f.length() == 0 || f.length() > MAX_TEXT_SIZE) return false;
        String low = f.getName().toLowerCase(Locale.ROOT);
        // 源码/工程文件是描述性内容，排除误扫描
        if (low.endsWith(".java") || low.endsWith(".kt") || low.endsWith(".gradle")
                || low.endsWith(".properties") || low.endsWith(".pro")
                || low.endsWith(".pdf") || low.endsWith(".png") || low.endsWith(".jpg")
                || low.endsWith(".ttf") || low.endsWith(".apk")) {
            return false;
        }
        if (low.endsWith(".txt") || low.endsWith(".md") || low.endsWith(".smali")
                || low.endsWith(".conf") || low.endsWith(".json")) {
            return true;
        }
        return isEditableText(f);
    }

    private static boolean isEditableText(File f) {
        if (f.length() == 0 || f.length() > MAX_TEXT_SIZE) return false;
        byte[] head = head(f, 4096);
        for (byte b : head) {
            if (b == 0) return false;
        }
        return true;
    }

    private static boolean startsWith(File f, byte[] magic) {
        byte[] h = head(f, magic.length);
        if (h.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (h[i] != magic[i]) return false;
        }
        return true;
    }

    private static byte[] head(File f, int n) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[n];
            int r = in.read(buf);
            if (r <= 0) return new byte[0];
            return r == n ? buf : Arrays.copyOf(buf, r);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static String safeRead(File f, long max) {
        try {
            long len = Math.min(f.length(), max);
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) len];
                int r = 0;
                while (r < buf.length) {
                    int k = in.read(buf, r, buf.length - r);
                    if (k < 0) break;
                    r += k;
                }
                return decode(buf, r);
            }
        } catch (IOException e) {
            return "";
        }
    }

    /** 宽松解码：UTF-8 失败则回退 GBK（兼容社区 GBK 文本） */
    public static String decode(byte[] buf, int len) {
        String utf8 = new String(buf, 0, len, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) return utf8;
        try {
            return new String(buf, 0, len, Charset.forName("GBK"));
        } catch (Exception e) {
            return utf8;
        }
    }

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
    }

    /** 将弹窗包目录重新打包为 zip（用于编辑后保存/导出） */
    public static void zipDir(File dir, File out) throws IOException {
        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(new FileOutputStream(out))) {
            zipInto(zos, dir, "");
        }
    }

    private static void zipInto(java.util.zip.ZipOutputStream zos, File dir, String prefix)
            throws IOException {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            String entry = prefix.isEmpty() ? c.getName() : prefix + "/" + c.getName();
            if (c.isDirectory()) {
                zipInto(zos, c, entry);
            } else {
                zos.putNextEntry(new java.util.zip.ZipEntry(entry));
                try (InputStream in = new FileInputStream(c)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
        }
    }
}
