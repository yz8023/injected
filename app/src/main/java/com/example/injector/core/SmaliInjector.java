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
        if (smaliCalls == null || smaliCalls.isEmpty()) {
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

        insertInvokes(targetSmali, smaliCalls);

        SmaliOptions opts = new SmaliOptions();
        opts.outputDexFile = patched.getAbsolutePath();
        opts.jobs = Math.max(1, Math.min(maxJobs, 4));
        opts.verboseErrors = true;
        boolean ok = Smali.assemble(opts, smaliDir.getAbsolutePath());
        if (!ok || !patched.exists()) {
            throw new IllegalStateException(
                    "smali 汇编失败，请检查插入行语法。smali 目录：" + smaliDir.getAbsolutePath()
                            + "，插入行数：" + smaliCalls.size());
        }
        return patched;
    }

    static void insertInvoke(File smaliFile, String smaliCall) throws IOException {
        List<String> calls = new ArrayList<>(1);
        calls.add(smaliCall);
        insertInvokes(smaliFile, calls);
    }

    static void insertInvokes(File smaliFile, List<String> smaliCalls) throws IOException {
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
        int catchReg = oldLocals;

        List<String> out = new ArrayList<>(lines.size() + smaliCalls.size() * 8 + 8);
        for (int i = 0; i <= superIdx; i++) {
            if (i == directivesIdx) {
                out.add(usesLocals ? "    .locals " + (regCount + 1)
                        : "    .registers " + (regCount + 1));
            } else {
                out.add(lines.get(i));
            }
        }
        out.add("");
        for (int k = 0; k < smaliCalls.size(); k++) {
            boolean last = (k == smaliCalls.size() - 1);
            out.add("    :ins_try_start_" + k);
            out.add("    " + smaliCalls.get(k));
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
        out.add("    :ins_done_" + smaliCalls.size());
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
