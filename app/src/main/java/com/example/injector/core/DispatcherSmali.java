package com.example.injector.core;

import org.jf.smali.Smali;
import org.jf.smali.SmaliOptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.List;

/**
 * 随机触发调度器：生成一个 smali 分发类并汇编为独立 dex。
 *
 * 宿主启动类 onCreate 只插入一个调用点
 * {@code invoke-static {p0}, Lcom/xypopup/dispatch/Dispatcher;->dispatch(Landroid/content/Context;)V}，
 * 运行时由调度器等概率随机触发一个弹窗入口。
 * 每个分支独立 try-catch，单弹窗异常不影响宿主与其他分支。
 */
public final class DispatcherSmali {

    public static final String CLASS_TYPE = "Lcom/xypopup/dispatch/Dispatcher;";

    private DispatcherSmali() {
    }

    /**
     * 生成并汇编调度器 dex。
     *
     * @param invokes 弹窗调用行列表，形如
     *                {@code invoke-static {p0}, Lcom/x/Popup;->show(Landroid/content/Context;)V}
     * @param workDir 工作目录
     * @return dispatcher.dex 文件
     */
    public static File build(List<String> invokes, File workDir) throws Exception {
        if (invokes == null || invokes.isEmpty()) {
            throw new IllegalStateException("调度器至少需要一个弹窗入口");
        }
        List<List<List<String>>> groups = new java.util.ArrayList<>();
        for (String s : invokes) {
            List<List<String>> pkg = new java.util.ArrayList<>();
            List<String> snip = new java.util.ArrayList<>();
            snip.add(s);
            pkg.add(snip);
            groups.add(pkg);
        }
        return buildSnippets(groups, workDir);
    }

    /**
     * 片段版调度器：每个候选弹窗包 = 一组 smali 片段行，
     * 分支内顺序执行该包全部片段（整包一个 try-catch）。
     *
     * @param groups 每个弹窗包的片段列表
     */
    public static File buildSnippets(List<List<List<String>>> groups, File workDir) throws Exception {
        if (groups == null || groups.isEmpty()) {
            throw new IllegalStateException("调度器至少需要一个弹窗入口");
        }
        int count = groups.size();
        int maxReg = 3;
        for (List<List<String>> pkg : groups) {
            if (pkg == null) continue;
            for (List<String> snip : pkg) {
                if (snip == null) continue;
                for (String line : snip) maxReg = Math.max(maxReg, maxVReg(line));
            }
        }
        int locals = maxReg + 2; // 片段寄存器 + exception 专用高位寄存器

        StringBuilder sb = new StringBuilder();
        sb.append(".class public ").append(CLASS_TYPE).append('\n')
          .append(".super Ljava/lang/Object;\n")
          .append('\n')
          .append(".method public static dispatch(Landroid/content/Context;)V\n")
          .append("    .locals ").append(locals).append('\n')
          .append('\n')
          .append("    new-instance v0, Ljava/util/Random;\n")
          .append("    invoke-direct {v0}, Ljava/util/Random;-><init>()V\n")
          .append('\n')
          .append("    invoke-static {}, Ljava/lang/System;->currentTimeMillis()J\n")
          .append("    move-result-wide v1\n")
          .append("    invoke-virtual {v0, v1, v2}, Ljava/util/Random;->setSeed(J)V\n")
          .append('\n')
          .append("    const v").append(locals - 1).append(", 0x")
            .append(Integer.toHexString(count)).append('\n')
          .append("    invoke-virtual {v0, v").append(locals - 1)
            .append("}, Ljava/util/Random;->nextInt(I)I\n")
          .append("    move-result v1\n")
          .append('\n')
          .append("    packed-switch v1, :pswitch_data_0\n")
          .append("    return-void\n")
          .append('\n');

        int exReg = locals - 1;
        for (int k = 0; k < count; k++) {
            sb.append("    :pswitch_").append(k).append('\n')
              .append("    :try_start_").append(k).append('\n');
            List<List<String>> pkg = groups.get(k);
            if (pkg != null) {
                for (List<String> snip : pkg) {
                    if (snip == null) continue;
                    for (String line : snip) {
                        if (line == null || line.trim().isEmpty()
                                || line.trim().startsWith(".locals")
                                || line.trim().startsWith(".registers")) continue;
                        sb.append("    ").append(line.trim()).append('\n');
                    }
                }
            }
            sb.append("    :try_end_").append(k).append('\n')
              .append("    .catch Ljava/lang/Throwable; {:try_start_").append(k)
                .append(" .. :try_end_").append(k).append("} :catch_").append(k).append('\n')
              .append("    goto/32 :goto_done\n")
              .append("    :catch_").append(k).append('\n')
              .append("    move-exception v").append(exReg).append('\n')
              .append("    goto/32 :goto_done\n")
              .append('\n');
        }

        sb.append("    :goto_done\n")
          .append("    return-void\n")
          .append('\n')
          .append("    :pswitch_data_0\n")
          .append("    .packed-switch 0x0\n");
        for (int k = 0; k < count; k++) {
            sb.append("        :pswitch_").append(k).append('\n');
        }
        sb.append("    .end packed-switch\n")
          .append(".end method\n");

        File smaliDir = new File(workDir, "dispatcher_smali");
        smaliDir.mkdirs();
        File smaliFile = new File(smaliDir, "com/xypopup/dispatch/Dispatcher.smali");
        smaliFile.getParentFile().mkdirs();
        FileIo.write(smaliFile, sb.toString());

        File dex = new File(workDir, "dispatcher.dex");
        SmaliOptions opts = new SmaliOptions();
        opts.outputDexFile = dex.getAbsolutePath();
        opts.jobs = 1;
        opts.verboseErrors = true;

        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(errBuf, true, "UTF-8"));
        System.setErr(new PrintStream(errBuf, true, "UTF-8"));
        boolean ok;
        try {
            ok = Smali.assemble(opts, smaliDir.getAbsolutePath());
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        if (!ok || !dex.exists() || dex.length() == 0) {
            throw new IllegalStateException("调度器 smali 汇编失败：" + FileIo.tail(errBuf));
        }
        return dex;
    }

    private static int maxVReg(String line) {
        int max = -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bv(\\d+)\\b")
                .matcher(line == null ? "" : line);
        while (m.find()) {
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return max;
    }

    private static final class FileIo {
        static void write(File f, String text) throws Exception {
            java.io.OutputStream os = new java.io.FileOutputStream(f);
            os.write(text.getBytes("UTF-8"));
            os.close();
        }

        static String tail(ByteArrayOutputStream buf) {
            String s;
            try {
                s = buf.toString("UTF-8").trim();
            } catch (Exception e) {
                s = "";
            }
            if (s.isEmpty()) return "无错误输出";
            return s.length() > 1500 ? s.substring(s.length() - 1500) : s;
        }
    }
}
