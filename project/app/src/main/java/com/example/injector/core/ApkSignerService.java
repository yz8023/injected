package com.example.injector.core;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.android.apksig.ApkSigner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import javax.security.auth.x500.X500Principal;

/**
 * APK 签名服务。
 *
 * 两种密钥来源：
 * 1. 自动生成：AndroidKeyStore 中 RSA-2048 密钥（别名 injector_autogen），首次签名时创建；
 * 2. 用户导入：设置页选择 keystore 文件后经 importKeystore 落盘为
 *    filesDir/Injector.ks，签名时按 PKCS12/JKS/BKS 顺序识别加载。
 *
 * 签名算法走 apksig：v1(JAR) + v2，minSdk 24。
 */
public final class ApkSignerService {

    public static final String IMPORTED_KS_NAME = "Injector.ks";
    private static final String AUTO_ALIAS = "injector_autogen";
    private static final String SIGNER_NAME = "INJECTOR";
    private static final int MIN_SDK = 24;

    private ApkSignerService() {
    }

    public interface LogFn {
        void log(String line);
    }

    /**
     * 导入用户 keystore：识别格式并验证私钥可用后，复制到 importDir/IMPORTED_KS_NAME。
     *
     * @return 实际使用的私钥别名
     */
    public static String importKeystore(File userKs, String pass, String alias,
                                        File importDir, LogFn log) throws Exception {
        if (userKs == null || !userKs.exists() || userKs.length() == 0) {
            throw new IllegalStateException("keystore 文件为空");
        }
        char[] password = toChars(pass);
        Exception last = null;
        for (String type : new String[]{"PKCS12", "JKS", "BKS"}) {
            try {
                KeyStore ks = KeyStore.getInstance(type);
                try (InputStream in = new FileInputStream(userKs)) {
                    ks.load(in, password);
                }
                String usedAlias = resolveAlias(ks, alias, password);
                File target = new File(importDir, IMPORTED_KS_NAME);
                copyFile(userKs, target);
                log.log("keystore 导入成功（类型 " + type + "，别名 " + usedAlias + "）");
                return usedAlias;
            } catch (Exception e) {
                last = e;
                log.log("按 " + type + " 解析失败：" + safeMsg(e));
            }
        }
        throw new IllegalStateException(
                "keystore 解析失败（已尝试 PKCS12/JKS/BKS，请确认密码）："
                        + safeMsg(last));
    }

    /**
     * 对 APK 签名。失败时删除半成品输出并原样抛出。
     */
    public static void signApk(File input, File output, Context ctx,
                               String ksPass, String ksAlias, LogFn log) throws Exception {
        try {
            SignerCredentials creds = loadCredentials(ctx, ksPass, ksAlias, log);
            log.log("开始 apksig 签名（v1 + v2）…");
            ApkSigner.SignerConfig cfg = new ApkSigner.SignerConfig.Builder(
                    SIGNER_NAME, creds.privateKey, creds.certs, false).build();
            new ApkSigner.Builder(Collections.singletonList(cfg))
                    .setInputApk(input)
                    .setOutputApk(output)
                    .setMinSdkVersion(MIN_SDK)
                    .setV1SigningEnabled(true)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(false)
                    .build()
                    .sign();
            log.log("签名完成：" + output.getName());
        } catch (Exception e) {
            if (output.exists()) output.delete();
            throw e;
        }
    }

    private static SignerCredentials loadCredentials(Context ctx, String ksPass,
                                                     String ksAlias, LogFn log) throws Exception {
        File imported = new File(ctx.getFilesDir(), IMPORTED_KS_NAME);
        if (imported.exists()) {
            log.log("使用已导入密钥：" + IMPORTED_KS_NAME);
            char[] password = toChars(ksPass);
            Exception last = null;
            for (String type : new String[]{"PKCS12", "JKS", "BKS"}) {
                try {
                    KeyStore ks = KeyStore.getInstance(type);
                    try (InputStream in = new FileInputStream(imported)) {
                        ks.load(in, password);
                    }
                    String alias = resolveAlias(ks, ksAlias, password);
                    PrivateKey pk = (PrivateKey) ks.getKey(alias, password);
                    Certificate[] chain = ks.getCertificateChain(alias);
                    List<X509Certificate> certs = new ArrayList<>();
                    if (chain != null) {
                        for (Certificate c : chain) certs.add((X509Certificate) c);
                    }
                    if (pk == null) throw new IllegalStateException("取不到私钥");
                    log.log("已加载私钥（别名 " + alias + "）");
                    return new SignerCredentials(pk, certs);
                } catch (Exception e) {
                    last = e;
                }
            }
            throw new IllegalStateException(
                    "已导入密钥加载失败（密码可能不对）：" + safeMsg(last));
        }

        log.log("使用自动生成密钥（AndroidKeyStore）");
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(AUTO_ALIAS)) {
            log.log("首次使用，生成 RSA-2048 签名密钥…");
            generateAutoKey();
        }
        KeyStore.PrivateKeyEntry entry =
                (KeyStore.PrivateKeyEntry) ks.getEntry(AUTO_ALIAS, null);
        Certificate[] chain = ks.getCertificateChain(AUTO_ALIAS);
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : chain) certs.add((X509Certificate) c);
        return new SignerCredentials(entry.getPrivateKey(), certs);
    }

    private static void generateAutoKey() throws Exception {
        Calendar start = Calendar.getInstance();
        start.add(Calendar.YEAR, -1);
        Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 30);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                AUTO_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(
                        new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                .setCertificateSubject(new X500Principal("CN=Injector, O=Injector, C=CN"))
                .setCertificateNotBefore(start.getTime())
                .setCertificateNotAfter(end.getTime())
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build();
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        kpg.initialize(spec);
        kpg.generateKeyPair();
    }

    private static String resolveAlias(KeyStore ks, String alias, char[] password) throws Exception {
        if (alias != null && !alias.isEmpty()) {
            if (!ks.containsAlias(alias)) {
                throw new IllegalStateException("别名不存在：" + alias);
            }
            if (!ks.isKeyEntry(alias)) {
                throw new IllegalStateException("该别名不是私钥条目：" + alias);
            }
            if (ks.getKey(alias, password) == null) {
                throw new IllegalStateException("取不到私钥（密码可能不对）");
            }
            return alias;
        }
        Enumeration<String> en = ks.aliases();
        while (en.hasMoreElements()) {
            String a = en.nextElement();
            if (ks.isKeyEntry(a) && ks.getKey(a, password) != null) {
                return a;
            }
        }
        throw new IllegalStateException("keystore 里没有可用的私钥条目");
    }

    private static char[] toChars(String pass) {
        return (pass == null || pass.isEmpty()) ? null : pass.toCharArray();
    }

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String safeMsg(Exception e) {
        return e == null ? "未知错误" : (e.getMessage() == null ? e.toString() : e.getMessage());
    }

    private static final class SignerCredentials {
        final PrivateKey privateKey;
        final List<X509Certificate> certs;

        SignerCredentials(PrivateKey privateKey, List<X509Certificate> certs) {
            this.privateKey = privateKey;
            this.certs = certs;
        }
    }
}
