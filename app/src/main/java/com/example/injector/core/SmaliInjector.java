package com.example.injector.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jf.baksmali.Baksmali;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

public class SmaliInjector {

    private static final int ONCREATE_PARAM_REGISTERS = 2;
    private static final Pattern LOCALS = Pattern.compile("^\\.locals\\s+(\\d+)\\s*$");
    private static final Pattern REGISTERS = Pattern.compile("^\\.registers\\s+(\\d+)\\s*$");
    private static final Pattern VREG = Pattern.compile("\\bv(\\d+)\\b");

    public static File inject(File originalDex, String className, String smaliCall,
                              File workDir, int maxJobs) throws Exception {
        List<String> calls = new ArrayList<>(1);
        calls.add(smaliCall);
        return inject(originalDex, className, calls, workDir, maxJobs);
    }

    /**
     * 多调用点插桩（依次触发策略）：在启动类 onCreate 的 invoke-super 之后
     * 按顺序插入每条 invoke，每条独立 try-catch，单弹窗异常不影响后续弹窗。
     */
    public static File inject(File originalDex, String className, List<String> smaliCalls,
                              File workDir, int maxJobs) throws Exception {
        List<List<String>> snippets = new ArrayList<>();
        for (String c : smaliCalls) snippets.add(new ArrayList<>(java.util.Collections.singletonList(c)));
        return injectSnippets(originalDex, className, snippets, workDir, maxJobs);
    }

    /**
     * 片段插桩：每个片段为多行指令序列（如 const-string + invoke + move-result），
     * 每段独立 try-catch。
     */
    public static File injectSnippets(File originalDex, String className, List<List<String>> snippets,
                              File workDir, int maxJobs) throws Exception {
        if (snippets == null || snippets.isEmpty()) {
            throw new IllegalStateException("没有可插入的调用代码");
        }
        File smaliDir = new File(workDir, "smali_tmp");
        File patched = new File(workDir, "patched.dex");

        org.jf.dexlib2.dexbacked.DexBackedDexFile dexFile =
                org.jf.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(
                        org.jf.dexlib2.Opcodes.getDefault(),
                        new FileInputStream(originalDex));
        Baksmali.disassembleDexFile(dexFile, smaliDir, Math.max(1, Math.min(maxJobs, 4)),
                new BaksmaliOptions());

        String smaliPath = className.replace('.', '/') + ".smali";
        File targetSmali = new File(smaliDir, smaliPath);
        if (!targetSmali.exists()) {
            throw new IllegalStateException("反编译后找不到 smali：" + smaliPath);
        }

        insertSnippets(targetSmali, snippets);

        SmaliOptions opts = new SmaliOptions();
        opts.outputDexFile = patched.getAbsolutePath();
        opts.jobs = Math.max(1, Math.min(maxJobs, 4));
        opts.verboseErrors = true;
        boolean ok = Smali.assemble(opts, smaliDir.getAbsolutePath());
        if (!ok || !patched.exists()) {
            throw new IllegalStateException(
                    "smali 汇编失败，请检查插入行语法。smali 目录：" + smaliDir.getAbsolutePath()
                            + "，插入段数：" + snippets.size());
        }
        return patched;
    }

    /**
     * 一机一码插桩：宿主启动类 onCreate 拆分为验证门结构。
     * 原 onCreate 改名 onCreate$xygate（保留完整逻辑，invoke-super 原样保留在原方法内），
     * 新 onCreate：invoke-super 父类后只调用 Gate.gate()，由 gate 决定是否执行原逻辑。
     *
     * @return File[2]：[0]=patched.dex（宿主 dex 插桩后），[1]=gate.dex（验证门，追加进 APK）
     */
    public static File[] injectWithLicense(File originalDex, String hostClass, String gateClass,
                                           List<List<String>> extraSnippets, File workDir,
                                           int maxJobs, String publicKeyB64) throws Exception {
        File smaliDir = new File(workDir, "smali_tmp");
        File patched = new File(workDir, "patched.dex");

        DexBackedDexFile dexFile = DexBackedDexFile.fromInputStream(
                Opcodes.getDefault(), new FileInputStream(originalDex));
        Baksmali.disassembleDexFile(dexFile, smaliDir, Math.max(1, Math.min(maxJobs, 4)),
                new BaksmaliOptions());

        String smaliPath = hostClass.replace('.', '/') + ".smali";
        File targetSmali = new File(smaliDir, smaliPath);
        if (!targetSmali.exists()) {
            throw new IllegalStateException("反编译后找不到 smali：" + smaliPath);
        }
        String parentType = rewriteOnCreateForGate(targetSmali, gateClass);

        // 额外弹窗片段照常插入（gate 通过后、原逻辑执行前弹出）
        if (extraSnippets != null && !extraSnippets.isEmpty()) {
            insertSnippets(new File(smaliDir, smaliPath), extraSnippets);
        }

        SmaliOptions opts = new SmaliOptions();
        opts.outputDexFile = patched.getAbsolutePath();
        opts.jobs = Math.max(1, Math.min(maxJobs, 4));
        opts.verboseErrors = true;
        boolean ok = Smali.assemble(opts, smaliDir.getAbsolutePath());
        if (!ok || !patched.exists()) {
            throw new IllegalStateException("注册验证插桩后 smali 汇编失败：" + smaliDir);
        }

        // 生成 gate dex
        File gateDex = LicenseSmali.buildGate(workDir, hostClass, parentType, gateClass,
                publicKeyB64);
        return new File[]{patched, gateDex};
    }

    /**
     * 改写宿主启动类：
     * 1. 原 onCreate 重命名 onCreate$xygate（逻辑与 invoke-super 原样保留）
     * 2. 新建 onCreate：invoke-super 父类 → Gate.gate(activity, bundle) → return
     * 返回父类类型描述符（供 gate dex 引用）。
     */
    public static String rewriteOnCreateForGate(File smaliFile, String gateClass) throws IOException {
        List<String> lines = Files.readAllLines(smaliFile.toPath());
        String parentType = null;
        String classNameType = null;
        for (String l : lines) {
            String t = l.trim();
            if (t.startsWith(".super ")) parentType = t.substring(".super ".length()).trim();
            if (t.startsWith(".class ")) {
                int d = t.lastIndexOf(' ');
                classNameType = t.substring(d + 1).trim();
            }
        }
        if (parentType == null || classNameType == null) {
            throw new IllegalStateException("smali 缺少 .class/.super 声明");
        }

        int methodStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith(".method") && t.contains("onCreate(Landroid/os/Bundle;)V")) {
                methodStart = i;
                break;
            }
        }
        if (methodStart < 0) {
            throw new IllegalStateException("smali 里没找到 onCreate(Landroid/os/Bundle;)V");
        }
        int methodEnd = -1;
        for (int i = methodStart + 1; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith(".end method")) {
                methodEnd = i;
                break;
            }
        }
        if (methodEnd < 0) throw new IllegalStateException("smali 方法体不完整");

        String origHeader = lines.get(methodStart).trim();
        // 重命名原方法并强制 public（供 gate dex 跨类调用）
        String renamed = ".method public onCreate$xygate(Landroid/os/Bundle;)V";

        // 新 onCreate（p0=this p1=bundle，.locals 0）
        List<String> newMethod = new ArrayList<>();
        newMethod.add(".method public onCreate(Landroid/os/Bundle;)V");
        newMethod.add("    .locals 0");
        newMethod.add("");
        newMethod.add("    invoke-super {p0, p1}, " + parentType
                + "->onCreate(Landroid/os/Bundle;)V");
        newMethod.add("");
        newMethod.add("    invoke-static {p0, p1}, " + gateClass
                + "->gate(Landroid/app/Activity;Landroid/os/Bundle;)V");
        newMethod.add("");
        newMethod.add("    return-void");
        newMethod.add(".end method");

        List<String> out = new ArrayList<>(lines.size() + newMethod.size() + 4);
        for (int i = 0; i < methodStart; i++) out.add(lines.get(i));
        out.add(renamed);
        for (int i = methodStart + 1; i <= methodEnd; i++) out.add(lines.get(i));
        out.add("");
        out.addAll(newMethod);
        for (int i = methodEnd + 1; i < lines.size(); i++) out.add(lines.get(i));
        Files.write(smaliFile.toPath(), out);
        return parentType;
    }

    static void insertInvoke(File smaliFile, String smaliCall) throws IOException {
        List<String> calls = new ArrayList<>(1);
        calls.add(smaliCall);
        insertInvokes(smaliFile, calls);
    }

    static void insertInvokes(File smaliFile, List<String> smaliCalls) throws IOException {
        List<List<String>> snippets = new ArrayList<>();
        for (String c : smaliCalls) snippets.add(new ArrayList<>(java.util.Collections.singletonList(c)));
        insertSnippets(smaliFile, snippets);
    }

    static void insertSnippets(File smaliFile, List<List<String>> snippets) throws IOException {
        List<String> lines = Files.readAllLines(smaliFile.toPath());
        int methodStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith(".method") && t.contains("onCreate(Landroid/os/Bundle;)V")) {
                methodStart = i;
                break;
            }
        }
        if (methodStart < 0) {
            throw new IllegalStateException("smali 里没找到 onCreate(Landroid/os/Bundle;)V");
        }

        int methodEnd = -1;
        for (int i = methodStart + 1; i < lines.size(); i++) {
            if (lines.get(i).trim().startsWith(".end method")) {
                methodEnd = i;
                break;
            }
        }
        if (methodEnd < 0) throw new IllegalStateException("smali 方法体不完整");

        int directivesIdx = -1;
        boolean usesLocals = false;
        int regCount = -1;
        for (int i = methodStart + 1; i < methodEnd; i++) {
            String t = lines.get(i).trim();
            Matcher m = LOCALS.matcher(t);
            if (m.matches()) {
                directivesIdx = i;
                usesLocals = true;
                regCount = Integer.parseInt(m.group(1));
                break;
            }
            m = REGISTERS.matcher(t);
            if (m.matches()) {
                directivesIdx = i;
                usesLocals = false;
                regCount = Integer.parseInt(m.group(1));
                break;
            }
        }
        if (directivesIdx < 0) {
            throw new IllegalStateException("onCreate 缺少 .locals/.registers 声明");
        }

        int superIdx = -1;
        for (int i = directivesIdx + 1; i < methodEnd; i++) {
            if (lines.get(i).trim().startsWith("invoke-super")) {
                superIdx = i;
                break;
            }
        }
        if (superIdx < 0) {
            throw new IllegalStateException("smali 里没找到 invoke-super，无法插入");
        }

        int oldLocals = usesLocals ? regCount : regCount - ONCREATE_PARAM_REGISTERS;
        if (oldLocals < 0) oldLocals = 0;

        // 收集片段里用到的最大 v 寄存器号，扩展 .locals 并规划独立 catch 寄存器
        int maxV = oldLocals - 1;
        for (List<String> sn : snippets) {
            for (String line : sn) {
                Matcher vm = VREG.matcher(line);
                while (vm.find()) {
                    int n = Integer.parseInt(vm.group(1));
                    if (n > maxV) maxV = n;
                }
            }
        }
        int catchReg = maxV + 1;
        int newLocals = catchReg + 1;

        List<String> out = new ArrayList<>(lines.size() + snippets.size() * 8 + 8);
        for (int i = 0; i <= superIdx; i++) {
            if (i == directivesIdx) {
                out.add(usesLocals ? "    .locals " + newLocals
                        : "    .registers " + (newLocals + ONCREATE_PARAM_REGISTERS));
            } else {
                out.add(lines.get(i));
            }
        }
        out.add("");
        for (int k = 0; k < snippets.size(); k++) {
            boolean last = (k == snippets.size() - 1);
            out.add("    :ins_try_start_" + k);
            for (String line : snippets.get(k)) {
                out.add("    " + line);
            }
            out.add("    :ins_try_end_" + k);
            out.add("    .catch Ljava/lang/Throwable; {:ins_try_start_" + k
                    + " .. :ins_try_end_" + k + "} :ins_catch_" + k);
            if (!last) {
                out.add("    goto/32 :ins_seq_" + (k + 1));
            }
            out.add("    :ins_catch_" + k);
            out.add("    move-exception v" + catchReg);
            if (!last) {
                out.add("    :ins_seq_" + (k + 1));
            }
        }
        out.add("    :ins_done_" + snippets.size());
        out.add("");
        for (int i = superIdx + 1; i < lines.size(); i++) {
            out.add(lines.get(i));
        }
        Files.write(smaliFile.toPath(), out);
    }

    public static List<String> verifyPatchedDex(File patchedDex, String className,
                                                String smaliCall) {
        List<String> report = new ArrayList<>();
        try {
            DexBackedDexFile dex = DexBackedDexFile.fromInputStream(
                    Opcodes.getDefault(), new FileInputStream(patchedDex));
            String type = "L" + className.replace('.', '/') + ";";
            String refSnippet = methodRefSnippet(smaliCall);

            org.jf.dexlib2.iface.ClassDef target = null;
            for (org.jf.dexlib2.iface.ClassDef cd : dex.getClasses()) {
                if (type.equals(cd.getType())) {
                    target = cd;
                    break;
                }
            }
            if (target == null) {
                report.add("P4 回读失败：patched.dex 中没有 " + type);
                return report;
            }
            org.jf.dexlib2.iface.Method onCreate = null;
            for (org.jf.dexlib2.iface.Method m : target.getVirtualMethods()) {
                if ("onCreate".equals(m.getName())
                        && "(Landroid/os/Bundle;)V".equals(sigOf(m))) {
                    onCreate = m;
                    break;
                }
            }
            if (onCreate == null) {
                for (org.jf.dexlib2.iface.Method m : target.getDirectMethods()) {
                    if ("onCreate".equals(m.getName())) {
                        onCreate = m;
                        break;
                    }
                }
            }
            if (onCreate == null) {
                report.add("P4 回读失败：onCreate 方法不存在");
                return report;
            }
            org.jf.dexlib2.iface.MethodImplementation impl = onCreate.getImplementation();
            if (impl == null) {
                report.add("P4 回读失败：onCreate 无方法体");
                return report;
            }
            boolean hasCall = false;
            for (org.jf.dexlib2.iface.instruction.Instruction ins : impl.getInstructions()) {
                if (ins instanceof org.jf.dexlib2.iface.instruction.ReferenceInstruction) {
                    String r = ((org.jf.dexlib2.iface.instruction.ReferenceInstruction) ins)
                            .getReference().toString();
                    if (refSnippet != null && r.contains(refSnippet)) {
                        hasCall = true;
                        break;
                    }
                }
            }
            boolean hasCatch = false;
            for (org.jf.dexlib2.iface.TryBlock tb : impl.getTryBlocks()) {
                for (Object eh : tb.getExceptionHandlers()) {
                    if (eh instanceof org.jf.dexlib2.iface.ExceptionHandler) {
                        String et = ((org.jf.dexlib2.iface.ExceptionHandler) eh).getExceptionType();
                        if ("Ljava/lang/Throwable;".equals(et)) {
                            hasCatch = true;
                            break;
                        }
                    }
                }
                if (hasCatch) break;
            }
            report.add("P4 回读校验：插入调用" + (hasCall ? "存在" : "缺失")
                    + "，Throwable 捕获" + (hasCatch ? "存在" : "缺失"));
        } catch (Throwable t) {
            report.add("P4 回读校验异常：" + t);
        }
        return report;
    }

    private static String sigOf(org.jf.dexlib2.iface.Method m) {
        String ret = m.getReturnType();
        StringBuilder params = new StringBuilder();
        for (CharSequence p : m.getParameterTypes()) params.append(p);
        return "(" + params + ")" + ret;
    }

    private static String methodRefSnippet(String smaliCall) {
        if (smaliCall == null) return null;
        int comma = smaliCall.indexOf(", ");
        if (comma < 0) return null;
        return smaliCall.substring(comma + 2).trim();
    }
}
