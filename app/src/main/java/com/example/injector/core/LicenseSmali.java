package com.example.injector.core;

import java.io.File;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 一机一码验证门生成器。
 *
 * 生成 Gate 类（独立 dex）注入宿主：
 * - 宿主 onCreate 被拆分：原逻辑移入 onCreate$xygate，新 onCreate 只调用 Gate.gate()
 * - gate 验证机器码（ANDROID_ID）的 RSA-SHA256 签名，公钥硬编码于宿主
 * - 验证通过才执行 onCreate$xygate；失败弹激活框（不可取消），激活成功 recreate
 * - 删除 gate dex / 屏蔽弹窗均无法绕过：原逻辑引用 gate 类，删类直接崩溃
 *
 * 激活码 = Base64( RSA私钥签名( SHA256withRSA, 机器码 bytes ) )
 */
public final class LicenseSmali {

    public static final String GATE_CLASS = "Lcom/xymod/license/Gate;";

    private LicenseSmali() {
    }

    public static File buildGate(File workDir, String hostClass, String parentType,
                                 String gateClass, String publicKeyB64) throws Exception {
        String hostType = "L" + hostClass.replace('.', '/') + ";";
        String inner = gateClass.substring(0, gateClass.length() - 1) + "$1;";
        String[] classes = render(hostType, gateClass, publicKeyB64);

        File smaliDir = new File(workDir, "gate_smali");
        smaliDir.mkdirs();
        File f = new File(smaliDir, "com/xymod/license/Gate.smali");
        f.getParentFile().mkdirs();
        java.io.OutputStream os = new java.io.FileOutputStream(f);
        os.write(classes[0].getBytes("UTF-8"));
        os.close();
        File f2 = new File(smaliDir, "com/xymod/license/Gate$1.smali");
        os = new java.io.FileOutputStream(f2);
        os.write(classes[1].getBytes("UTF-8"));
        os.close();

        File dex = new File(workDir, "gate.dex");
        org.jf.smali.SmaliOptions opts = new org.jf.smali.SmaliOptions();
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
            ok = org.jf.smali.Smali.assemble(opts, smaliDir.getAbsolutePath());
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        if (!ok || !dex.exists() || dex.length() == 0) {
            String tail = errBuf.toString("UTF-8").trim();
            if (tail.length() > 1500) tail = tail.substring(tail.length() - 1500);
            throw new IllegalStateException("gate smali 汇编失败：" + tail);
        }
        return dex;
    }

    private static String[] render(String hostType, String gateClass, String pubB64) {
        String inner = gateClass.substring(0, gateClass.length() - 1) + "$1;";
        String dlgListener = "Landroid/content/DialogInterface$OnClickListener;";
        StringBuilder sb = new StringBuilder();

        sb.append(".class public ").append(gateClass).append('\n')
          .append(".super Ljava/lang/Object;\n")
          .append(".source \"Gate.java\"\n\n")
          .append(".field private static final PUB:Ljava/lang/String; = \"")
            .append(pubB64).append("\"\n\n");

        // ---------------- gate ----------------
        sb.append(".method public static gate(Landroid/app/Activity;Landroid/os/Bundle;)V\n")
          .append("    .locals 2\n\n")
          .append("    invoke-static {p0}, ").append(gateClass)
            .append("->verify(Landroid/app/Activity;)Z\n")
          .append("    move-result v0\n")
          .append("    if-eqz v0, :gate_act\n\n")
          .append("    invoke-virtual {p0, p1}, ").append(hostType)
            .append("->onCreate$xygate(Landroid/os/Bundle;)V\n")
          .append("    return-void\n\n")
          .append("    :gate_act\n")
          .append("    invoke-static {p0}, ").append(gateClass)
            .append("->showActivation(Landroid/app/Activity;)V\n")
          .append("    return-void\n")
          .append(".end method\n\n");

        // ---------------- verify(activity) ----------------
        sb.append(".method public static verify(Landroid/app/Activity;)Z\n")
          .append("    .locals 4\n\n")
          .append("    :try_start_0\n")
          .append("    const-string v0, \"xylicense\"\n")
          .append("    const/4 v1, 0x0\n")
          .append("    invoke-virtual {p0, v0, v1}, Landroid/app/Activity;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;\n")
          .append("    move-result-object v0\n")
          .append("    const-string v1, \"activation_code\"\n")
          .append("    const-string v2, \"\"\n")
          .append("    invoke-interface {v0, v1, v2}, Landroid/content/SharedPreferences;->getString(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;\n")
          .append("    move-result-object v0\n")
          .append("    invoke-virtual {v0}, Ljava/lang/String;->length()I\n")
          .append("    move-result v1\n")
          .append("    if-lez v1, :v_fail\n\n")
          .append("    invoke-virtual {p0}, ").append(gateClass)
            .append("->machineId(Landroid/app/Activity;)Ljava/lang/String;\n")
          .append("    move-result-object v1\n")
          .append("    invoke-static {v1, v0}, ").append(gateClass)
            .append("->rsaCheck(Ljava/lang/String;Ljava/lang/String;)Z\n")
          .append("    move-result v0\n")
          .append("    return v0\n")
          .append("    :try_end_0\n")
          .append("    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :v_catch\n")
          .append("    :v_fail\n")
          .append("    const/4 v0, 0x0\n")
          .append("    return v0\n")
          .append("    :v_catch\n")
          .append("    move-exception v0\n")
          .append("    const/4 v1, 0x0\n")
          .append("    return v1\n")
          .append(".end method\n\n");

        // ---------------- verifyCode(activity, code) ----------------
        sb.append(".method public static verifyCode(Landroid/app/Activity;Ljava/lang/String;)Z\n")
          .append("    .locals 3\n\n")
          .append("    :try_start_0\n")
          .append("    invoke-virtual {p0}, ").append(gateClass)
            .append("->machineId(Landroid/app/Activity;)Ljava/lang/String;\n")
          .append("    move-result-object v0\n")
          .append("    invoke-static {v0, p1}, ").append(gateClass)
            .append("->rsaCheck(Ljava/lang/String;Ljava/lang/String;)Z\n")
          .append("    move-result v0\n")
          .append("    return v0\n")
          .append("    :try_end_0\n")
          .append("    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :c_catch\n")
          .append("    const/4 v0, 0x0\n")
          .append("    return v0\n")
          .append("    :c_catch\n")
          .append("    move-exception v0\n")
          .append("    const/4 v1, 0x0\n")
          .append("    return v1\n")
          .append(".end method\n\n");

        // ---------------- machineId(activity) ----------------
        sb.append(".method public static machineId(Landroid/app/Activity;)Ljava/lang/String;\n")
          .append("    .locals 3\n\n")
          .append("    :try_start_0\n")
          .append("    invoke-virtual {p0}, Landroid/app/Activity;->getContentResolver()Landroid/content/ContentResolver;\n")
          .append("    move-result-object v0\n")
          .append("    sget-object v1, Landroid/provider/Settings$Secure;->ANDROID_ID:Ljava/lang/String;\n")
          .append("    invoke-static {v0, v1}, Landroid/provider/Settings$Secure;->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;\n")
          .append("    move-result-object v0\n")
          .append("    if-eqz v0, :m_fail\n")
          .append("    return-object v0\n")
          .append("    :try_end_0\n")
          .append("    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :m_catch\n")
          .append("    :m_fail\n")
          .append("    const-string v0, \"unknown\"\n")
          .append("    return-object v0\n")
          .append("    :m_catch\n")
          .append("    move-exception v0\n")
          .append("    const-string v1, \"unknown\"\n")
          .append("    return-object v1\n")
          .append(".end method\n\n");

        // ---------------- publicKey() ----------------
        sb.append(".method private static publicKey()Ljava/security/PublicKey;\n")
          .append("    .locals 4\n\n")
          .append("    :try_start_0\n")
          .append("    sget-object v0, ").append(gateClass)
            .append("->PUB:Ljava/lang/String;\n")
          .append("    const/4 v1, 0x2\n")
          .append("    invoke-static {v0, v1}, Landroid/util/Base64;->decode(Ljava/lang/String;I)[B\n")
          .append("    move-result-object v0\n")
          .append("    new-instance v1, Ljava/security/spec/X509EncodedKeySpec;\n")
          .append("    invoke-direct {v1, v0}, Ljava/security/spec/X509EncodedKeySpec;-><init>([B)V\n")
          .append("    const-string v2, \"RSA\"\n")
          .append("    invoke-static {v2}, Ljava/security/KeyFactory;->getInstance(Ljava/lang/String;)Ljava/security/KeyFactory;\n")
          .append("    move-result-object v2\n")
          .append("    invoke-virtual {v2, v1}, Ljava/security/KeyFactory;->generatePublic(Ljava/security/spec/KeySpec;)Ljava/security/PublicKey;\n")
          .append("    move-result-object v0\n")
          .append("    return-object v0\n")
          .append("    :try_end_0\n")
          .append("    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :k_catch\n")
          .append("    const/4 v0, 0x0\n")
          .append("    return-object v0\n")
          .append("    :k_catch\n")
          .append("    move-exception v0\n")
          .append("    const/4 v1, 0x0\n")
          .append("    return-object v1\n")
          .append(".end method\n\n");

        // ---------------- rsaCheck(machineId, code) ----------------
        sb.append(".method private static rsaCheck(Ljava/lang/String;Ljava/lang/String;)Z\n")
          .append("    .locals 6\n\n")
          .append("    :try_start_0\n")
          .append("    invoke-static {}, ").append(gateClass)
            .append("->publicKey()Ljava/security/PublicKey;\n")
          .append("    move-result-object v0\n")
          .append("    if-eqz v0, :r_fail\n\n")
          .append("    const-string v1, \"SHA256withRSA\"\n")
          .append("    invoke-static {v1}, Ljava/security/Signature;->getInstance(Ljava/lang/String;)Ljava/security/Signature;\n")
          .append("    move-result-object v1\n")
          .append("    invoke-virtual {v1, v0}, Ljava/security/Signature;->initVerify(Ljava/security/PublicKey;)V\n")
          .append("    invoke-virtual {p0}, Ljava/lang/String;->getBytes()[B\n")
          .append("    move-result-object v2\n")
          .append("    invoke-virtual {v1, v2}, Ljava/security/Signature;->update([B)V\n")
          .append("    const/4 v3, 0x2\n")
          .append("    invoke-static {p1, v3}, Landroid/util/Base64;->decode(Ljava/lang/String;I)[B\n")
          .append("    move-result-object v4\n")
          .append("    invoke-virtual {v1, v4}, Ljava/security/Signature;->verify([B)Z\n")
          .append("    move-result v0\n")
          .append("    return v0\n")
          .append("    :try_end_0\n")
          .append("    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :r_catch\n")
          .append("    :r_fail\n")
          .append("    const/4 v0, 0x0\n")
          .append("    return v0\n")
          .append("    :r_catch\n")
          .append("    move-exception v0\n")
          .append("    const/4 v1, 0x0\n")
          .append("    return v1\n")
          .append(".end method\n\n");

        // ---------------- tryActivate(activity, code) ----------------
        sb.append(".method public static tryActivate(Landroid/app/Activity;Ljava/lang/String;)Z\n")
          .append("    .locals 4\n\n")
          .append("    :try_start_0\n")
          .append("    invoke-virtual {p1}, Ljava/lang/String;->length()I\n")
          .append("    move-result v0\n")
          .append("    if-lez v0, :t_fail\n\n")
          .append("    invoke-virtual {p0}, ").append(gateClass)
            .append("->machineId(Landroid/app/Activity;)Ljava/lang/String;\n")
          .append("    move-result-object v1\n")
          .append("    invoke-static {v1, p1}, ").append(gateClass)
            .append("->rsaCheck(Ljava/lang/String;Ljava/lang/String;)Z\n")
          .append("    move-result v0\n")
          .append("    if-eqz v0, :t_fail\n\n")
          .append("    const-string v2, \"xylicense\"\n")
          .append("    const/4 v3, 0x0\n")
          .append("    invoke-virtual {p0, v2, v3}, Landroid/app/Activity;->getSharedPreferences(Ljava/lang/String;I)Landroid/content/SharedPreferences;\n")
          .append("    move-result-object v2\n")
          .append("    invoke-interface {v2}, Landroid/content/SharedPreferences;->edit()Landroid/content/SharedPreferences$Editor;\n")
          .append("    move-result-object v2\n")
          .append("    const-string v3, \"activation_code\"\n")
          .append("    invoke-interface {v2, v3, p1}, Landroid/content/SharedPreferences$Editor;->putString(Ljava/lang/String;Ljava/lang/String;)Landroid/content/SharedPreferences$Editor;\n")
          .append("    move-result-object v2\n")
          .append("    invoke-interface {v2}, Landroid/content/SharedPreferences$Editor;->apply()V\n")
          .append("    const/4 v3, 0x1\n")
          .append("    return v3\n")
          .append("    :try_end_0\n")
          .append("    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :t_catch\n")
          .append("    :t_fail\n")
          .append("    const/4 v3, 0x0\n")
          .append("    return v3\n")
          .append("    :t_catch\n")
          .append("    move-exception v0\n")
          .append("    const/4 v3, 0x0\n")
          .append("    return v3\n")
          .append(".end method\n\n");

        // ---------------- showActivation(activity) ----------------
        sb.append(".method public static showActivation(Landroid/app/Activity;)V\n")
          .append("    .locals 8\n\n")
          .append("    :try_start_0\n")
          .append("    new-instance v0, Landroid/widget/LinearLayout;\n")
          .append("    invoke-direct {v0, p0}, Landroid/widget/LinearLayout;-><init>(Landroid/content/Context;)V\n")
          .append("    const/4 v1, 0x1\n")
          .append("    invoke-virtual {v0, v1}, Landroid/widget/LinearLayout;->setOrientation(I)V\n")
          .append("    const/16 v1, 0x30\n")
          .append("    invoke-virtual {v0, v1, v1, v1, v1}, Landroid/widget/LinearLayout;->setPadding(IIII)V\n\n")
          .append("    new-instance v1, Landroid/widget/TextView;\n")
          .append("    invoke-direct {v1, p0}, Landroid/widget/TextView;-><init>(Landroid/content/Context;)V\n")
          .append("    const-string v2, \"\\u5e94\\u7528\\u9700\\u8981\\u6fc0\\u6d3b\"\n")
          .append("    invoke-virtual {v1, v2}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V\n")
          .append("    const/high16 v2, 0x41c00000\n")
          .append("    invoke-virtual {v1, v2}, Landroid/widget/TextView;->setTextSize(F)V\n")
          .append("    invoke-virtual {v0, v1}, Landroid/widget/LinearLayout;->addView(Landroid/view/View;)V\n\n")
          .append("    new-instance v1, Landroid/widget/TextView;\n")
          .append("    invoke-direct {v1, p0}, Landroid/widget/TextView;-><init>(Landroid/content/Context;)V\n")
          .append("    const-string v2, \"\\u673a\\u5668\\u7801\\uff08\\u957f\\u6309\\u590d\\u5236\\uff09\\uff1a\"\n")
          .append("    invoke-virtual {v1, v2}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V\n")
          .append("    invoke-virtual {v0, v1}, Landroid/widget/LinearLayout;->addView(Landroid/view/View;)V\n\n")
          .append("    invoke-static {p0}, ").append(gateClass)
            .append("->machineId(Landroid/app/Activity;)Ljava/lang/String;\n")
          .append("    move-result-object v2\n")
          .append("    new-instance v3, Landroid/widget/TextView;\n")
          .append("    invoke-direct {v3, p0}, Landroid/widget/TextView;-><init>(Landroid/content/Context;)V\n")
          .append("    invoke-virtual {v3, v2}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V\n")
          .append("    const/4 v4, 0x1\n")
          .append("    invoke-virtual {v3, v4}, Landroid/widget/TextView;->setTextIsSelectable(Z)V\n")
          .append("    invoke-virtual {v0, v3}, Landroid/widget/LinearLayout;->addView(Landroid/view/View;)V\n\n")
          .append("    new-instance v4, Landroid/widget/EditText;\n")
          .append("    invoke-direct {v4, p0}, Landroid/widget/EditText;-><init>(Landroid/content/Context;)V\n")
          .append("    const-string v5, \"\\u8f93\\u5165\\u6fc0\\u6d3b\\u7801\"\n")
          .append("    invoke-virtual {v4, v5}, Landroid/widget/EditText;->setHint(Ljava/lang/CharSequence;)V\n")
          .append("    invoke-virtual {v0, v4}, Landroid/widget/LinearLayout;->addView(Landroid/view/View;)V\n\n")
          .append("    new-instance v5, ").append(inner).append('\n')
          .append("    invoke-direct {v5, p0, v4}, ").append(inner)
            .append("-><init>(Landroid/app/Activity;Landroid/widget/EditText;)V\n\n")
          .append("    new-instance v6, Landroid/app/AlertDialog$Builder;\n")
          .append("    invoke-direct {v6, p0}, Landroid/app/AlertDialog$Builder;-><init>(Landroid/content/Context;)V\n")
          .append("    invoke-virtual {v6, v0}, Landroid/app/AlertDialog$Builder;->setView(Landroid/view/View;)Landroid/app/AlertDialog$Builder;\n")
          .append("    move-result-object v6\n")
          .append("    const-string v7, \"\\u6fc0\\u6d3b\"\n")
          .append("    invoke-virtual {v6, v7, v5}, Landroid/app/AlertDialog$Builder;->setPositiveButton(Ljava/lang/CharSequence;Landroid/content/DialogInterface$OnClickListener;)Landroid/app/AlertDialog$Builder;\n")
          .append("    move-result-object v6\n")
          .append("    const/4 v7, 0x0\n")
          .append("    invoke-virtual {v6, v7}, Landroid/app/AlertDialog$Builder;->setCancelable(Z)Landroid/app/AlertDialog$Builder;\n")
          .append("    move-result-object v6\n")
          .append("    invoke-virtual {v6}, Landroid/app/AlertDialog$Builder;->show()Landroid/app/AlertDialog;\n")
          .append("    :try_end_0\n")
          .append("    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :a_catch\n")
          .append("    return-void\n")
          .append("    :a_catch\n")
          .append("    move-exception v0\n")
          .append("    return-void\n")
          .append(".end method\n");

        // ---------------- inner click listener (separate class file) ----------------
        StringBuilder inner1 = new StringBuilder();
        inner1.append(".class public ").append(inner).append('\n')
          .append(".super Ljava/lang/Object;\n")
          .append(".source \"Gate.java\"\n")
          .append(".implements ").append(dlgListener).append("\n\n")
          .append(".field private final act:Landroid/app/Activity;\n")
          .append(".field private final input:Landroid/widget/EditText;\n\n")
          .append(".method public constructor <init>(Landroid/app/Activity;Landroid/widget/EditText;)V\n")
          .append("    .locals 0\n")
          .append("    invoke-direct {p0}, Ljava/lang/Object;-><init>()V\n")
          .append("    iput-object p1, p0, ").append(inner).append("->act:Landroid/app/Activity;\n")
          .append("    iput-object p2, p0, ").append(inner).append("->input:Landroid/widget/EditText;\n")
          .append("    return-void\n")
          .append(".end method\n\n")
          .append(".method public onClick(Landroid/content/DialogInterface;I)V\n")
          .append("    .locals 6\n\n")
          .append("    :try_start_0\n")
          .append("    iget-object v0, p0, ").append(inner).append("->act:Landroid/app/Activity;\n")
          .append("    iget-object v1, p0, ").append(inner).append("->input:Landroid/widget/EditText;\n")
          .append("    invoke-virtual {v1}, Landroid/widget/EditText;->getText()Landroid/text/Editable;\n")
          .append("    move-result-object v1\n")
          .append("    if-eqz v1, :bad\n")
          .append("    invoke-interface {v1}, Landroid/text/Editable;->toString()Ljava/lang/String;\n")
          .append("    move-result-object v1\n")
          .append("    invoke-virtual {v1}, Ljava/lang/String;->trim()Ljava/lang/String;\n")
          .append("    move-result-object v1\n")
          .append("    goto :check\n\n")
          .append("    :bad\n")
          .append("    const-string v1, \"\"\n\n")
          .append("    :check\n")
          .append("    invoke-static {v0, v1}, ").append(gateClass)
            .append("->tryActivate(Landroid/app/Activity;Ljava/lang/String;)Z\n")
          .append("    move-result v2\n")
          .append("    const/4 v3, 0x0\n")
          .append("    if-eqz v2, :fail\n\n")
          .append("    const-string v4, \"\\u6fc0\\u6d3b\\u6210\\u529f\\uff01\"\n")
          .append("    invoke-static {v0, v4, v3}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;\n")
          .append("    move-result-object v4\n")
          .append("    invoke-virtual {v4}, Landroid/widget/Toast;->show()V\n")
          .append("    invoke-virtual {v0}, Landroid/app/Activity;->recreate()V\n")
          .append("    return-void\n\n")
          .append("    :fail\n")
          .append("    const-string v4, \"\\u6fc0\\u6d3b\\u7801\\u65e0\\u6548\"\n")
          .append("    invoke-static {v0, v4, v3}, Landroid/widget/Toast;->makeText(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;\n")
          .append("    move-result-object v4\n")
          .append("    invoke-virtual {v4}, Landroid/widget/Toast;->show()V\n")
          .append("    return-void\n")
          .append("    :try_end_0\n")
          .append("    .catch Ljava/lang/Throwable; {:try_start_0 .. :try_end_0} :o_catch\n")
          .append("    :o_catch\n")
          .append("    move-exception v0\n")
          .append("    return-void\n")
          .append(".end method\n");

        return new String[]{sb.toString(), inner1.toString()};
    }

    /** 生成 RSA-2048 密钥对，返回 [公钥X509 b64, 私钥PKCS8 b64] */
    public static String[] generateKeyPair() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        java.security.KeyPair kp = kpg.generateKeyPair();
        String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        return new String[]{pub, priv};
    }

    /** 用 PKCS8 私钥 base64 给机器码签名，返回激活码（base64） */
    public static String signMachineCode(String privB64, String machineId) throws Exception {
        byte[] der = Base64.getDecoder().decode(privB64.replaceAll("\\s", ""));
        java.security.spec.PKCS8EncodedKeySpec spec =
                new java.security.spec.PKCS8EncodedKeySpec(der);
        java.security.PrivateKey key =
                java.security.KeyFactory.getInstance("RSA").generatePrivate(spec);
        java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(machineId.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    /** 校验激活码（本地测试用） */
    public static boolean checkActivation(String pubB64, String machineId, String code) {
        try {
            byte[] der = Base64.getDecoder().decode(pubB64);
            java.security.spec.X509EncodedKeySpec spec =
                    new java.security.spec.X509EncodedKeySpec(der);
            java.security.PublicKey key =
                    java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
            java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update(machineId.getBytes("UTF-8"));
            return sig.verify(Base64.getDecoder().decode(code));
        } catch (Exception e) {
            return false;
        }
    }
}
