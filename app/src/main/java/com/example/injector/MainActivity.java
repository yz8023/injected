package com.example.injector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import dalvik.system.DexClassLoader;

import com.example.injector.core.ApkRepackager;
import com.example.injector.core.ApkSignerService;
import com.example.injector.core.AxmlParser;
import com.example.injector.core.BuiltinPopups;
import com.example.injector.core.DispatcherSmali;
import com.example.injector.core.LicenseSmali;
import com.example.injector.core.ManifestInfo;
import com.example.injector.core.PopupConfig;
import com.example.injector.core.PopupPackage;
import com.example.injector.core.SmaliInjector;

public class MainActivity extends AppCompatActivity {

    private static final String DEFAULT_SECRET = "Aurora_Pro_#9981_Secret";
    private static final String PREF = "aurora_injector";
    private static final String KEY_SECRET = "secret_key";
    private static final String KEY_NOTICE_URL = "notice_url";
    private static final String KEY_KS_FILE = "ks_file";
    private static final String KEY_KS_PASS = "ks_pass";
    private static final String KEY_KS_ALIAS = "ks_alias";
    private static final String KEY_STRATEGY = "strategy";
    private static final String STRATEGY_RANDOM = "random";
    private static final String STRATEGY_SEQUENCE = "sequence";
    private static final String KEY_LAST_HOST = "last_host";
    private static final String KEY_LAST_POPUPS = "last_popups";
    private static final String KEY_LICENSE = "license_enabled";
    private static final long WINDOW_MS = 10 * 60_000L;

    private static final String OFFLINE_NOTICE =
            "离线演示公告：\n"
            + "1. 支持预览 assets 图片。\n"
            + "2. 弹窗包 = classes.dex + xymods.txt + assets/。\n"
            + "3. provider authorities 自动替换为宿主包名。";

    private SharedPreferences sp;
    private FrameLayout content;
    private BottomNavigationView bottomNav;

    private File hostFile;
    private final List<File> popupFiles = new ArrayList<>();
    private final Map<File, String> builtinNames = new HashMap<>();
    private final Map<File, PopupPackage> pkgCache = new HashMap<>();
    private File lastZip;
    private Uri ksUri;

    private LogConsole logger;
    private LogConsole previewLogger;
    private LogConsole settingsLogger;
    private TextView tvZipCount;
    private LinearLayout popupListContainer;

    private ActivityResultLauncher<Intent> apkPicker, zipPicker, ksPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sp = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        content = findViewById(R.id.content_container);
        bottomNav = findViewById(R.id.bottom_nav);

        bottomNav.getMenu().clear();
        bottomNav.getMenu().add(0, 1, 0, "出击").setIcon(android.R.drawable.ic_menu_send);
        bottomNav.getMenu().add(0, 2, 1, "预览").setIcon(android.R.drawable.ic_menu_view);
        bottomNav.getMenu().add(0, 3, 2, "公告").setIcon(android.R.drawable.ic_menu_info_details);
        bottomNav.getMenu().add(0, 4, 3, "设置").setIcon(android.R.drawable.ic_menu_preferences);

        bottomNav.setOnItemSelectedListener(item -> {
            animateBottomIcon();
            switch (item.getItemId()) {
                case 1: showInjectPage(); return true;
                case 2: showPreviewPage(); return true;
                case 3: showNoticePage(); return true;
                case 4: showSettingsPage(); return true;
            }
            return false;
        });

        apkPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
                    if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                        Uri u = r.getData().getData();
                        // SAF Uri 授权随时可能失效，选择时就地缓存私有副本
                        File imported = new File(getCacheDir(), "imported");
                        imported.mkdirs();
                        File copy = new File(imported, "host_" + System.currentTimeMillis() + ".apk");
                        try (InputStream in = getContentResolver().openInputStream(u);
                             FileOutputStream out = new FileOutputStream(copy)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        } catch (Exception e) {
                            copy.delete();
                            if (logger != null) logger.error("APK 读取失败：" + e.getMessage());
                            snack("APK 读取失败");
                            return;
                        }
                        hostFile = copy;
                        saveHostMemory();
                        if (logger != null) logger.ok("已选择 APK：" + hostFile.getName());
                        snack("APK 已就位☆");
                    }
                });
        zipPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
                    if (r.getResultCode() != Activity.RESULT_OK || r.getData() == null) return;
                    List<Uri> picked = new ArrayList<>();
                    if (r.getData().getClipData() != null) {
                        int n = r.getData().getClipData().getItemCount();
                        for (int i = 0; i < n; i++) {
                            picked.add(r.getData().getClipData().getItemAt(i).getUri());
                        }
                    } else if (r.getData().getData() != null) {
                        picked.add(r.getData().getData());
                    }
                    if (picked.isEmpty()) return;
                    // SAF Uri 授权随时可能失效，选择时就地缓存私有副本
                    File imported = new File(getCacheDir(), "imported");
                    imported.mkdirs();
                    int added = 0;
                    List<String> failed = new ArrayList<>();
                    File lastAdded = null;
                    for (Uri u : picked) {
                        if (u == null) continue;
                        File copy = new File(imported, "popup_"
                                + System.currentTimeMillis() + "_" + popupFiles.size() + ".zip");
                        try (InputStream in = getContentResolver().openInputStream(u);
                             FileOutputStream out = new FileOutputStream(copy)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        } catch (Exception e) {
                            copy.delete();
                            failed.add(shortUri(u));
                            if (logger != null) {
                                logger.error("弹窗包读取失败，已跳过："
                                        + shortUri(u) + " · " + e.getMessage());
                            }
                            continue;
                        }
                        popupFiles.add(copy);
                        lastAdded = copy;
                        added++;
                    }
                    if (added > 0) {
                        lastZip = lastAdded;
                        savePopupMemory();
                    }
                    if (logger != null && added > 0) {
                        logger.ok("已加入 " + added + " 个弹窗包 · 共 " + popupFiles.size() + " 个");
                    }
                    if (!failed.isEmpty() && previewLogger != null) {
                        previewLogger.warn("以下弹窗包无法读取："
                                + TextUtils.join("、", failed));
                    }
                    refreshZipCount();
                    refreshPopupList();
                });
        ksPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> {
                    if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) {
                        ksUri = r.getData().getData();
                        promptKeystoreImport();
                    }
                });

        bottomNav.setSelectedItemId(1);
        restoreMemory();
    }

    // =========================================================
    //                    上次选择记忆
    // =========================================================
    private void saveHostMemory() {
        sp.edit().putString(KEY_LAST_HOST,
                hostFile == null ? "" : hostFile.getAbsolutePath()).apply();
    }

    private void savePopupMemory() {
        StringBuilder sb = new StringBuilder();
        for (File f : popupFiles) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(f.getAbsolutePath());
        }
        sp.edit().putString(KEY_LAST_POPUPS, sb.toString()).apply();
    }

    private void restoreMemory() {
        String host = sp.getString(KEY_LAST_HOST, "");
        if (!host.isEmpty()) {
            File f = new File(host);
            if (f.exists()) {
                hostFile = f;
                if (logger != null) logger.info("已恢复上次 APK：" + f.getName());
            }
        }
        String pops = sp.getString(KEY_LAST_POPUPS, "");
        if (!pops.isEmpty()) {
            for (String p : pops.split("\n")) {
                if (p.isEmpty()) continue;
                File f = new File(p);
                if (f.exists()) popupFiles.add(f);
            }
            if (!popupFiles.isEmpty()) {
                lastZip = popupFiles.get(popupFiles.size() - 1);
                if (logger != null) {
                    logger.info("已恢复上次弹窗包 " + popupFiles.size() + " 个");
                }
            }
        }
        refreshZipCount();
        refreshPopupList();
    }

    // =========================================================
    //                          注入页
    // =========================================================
    private void showInjectPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = column();
        scroll.addView(root, lpMatchWrap());
        content.removeAllViews();
        content.addView(scroll, lpMatchMatch());

        LinearLayout header = columnNoPad();
        header.addView(h1("出击准备☆"));
        header.addView(sub("选 APK → 选弹窗包（可多选）→ 出击！"));
        root.addView(header);

        // 目标 APK 卡片
        MaterialCardView cardApk = mdCardOutlined();
        LinearLayout innerApk = columnNoPad();
        innerApk.setPadding(dp(20), dp(16), dp(20), dp(16));
        cardApk.addView(innerApk);
        TextInputLayout tilApk = new TextInputLayout(this);
        tilApk.setHint("目标 APK");
        TextInputEditText etApk = new TextInputEditText(this);
        etApk.setFocusable(false);
        etApk.setClickable(true);
        etApk.setOnClickListener(v -> { pressAnim(v); pickApk(); });
        tilApk.addView(etApk);
        innerApk.addView(tilApk);
        root.addView(cardApk);

        // 弹窗包卡片
        MaterialCardView cardZip = mdCardOutlined();
        LinearLayout innerZip = columnNoPad();
        innerZip.setPadding(dp(20), dp(16), dp(20), dp(16));
        cardZip.addView(innerZip);
        TextInputLayout tilZip = new TextInputLayout(this);
        tilZip.setHint("弹窗包（可多选）");
        TextInputEditText etZip = new TextInputEditText(this);
        etZip.setFocusable(false);
        etZip.setClickable(true);
        etZip.setOnClickListener(v -> { pressAnim(v); pickZip(); });
        tilZip.addView(etZip);
        innerZip.addView(tilZip);

        tvZipCount = labelInline("已选 0 个弹窗包");
        innerZip.addView(tvZipCount);

        popupListContainer = columnNoPad();
        popupListContainer.setPadding(0, dp(8), 0, 0);
        innerZip.addView(popupListContainer);

        LinearLayout zipBtnRow = new LinearLayout(this);
        zipBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton btnBuiltin = mdButtonTonal("内置弹窗库");
        btnBuiltin.setOnClickListener(v -> { pressAnim(v); useBuiltinPopupMulti(logger); });
        MaterialButton btnClearZip = mdButtonTonal("清空已选");
        btnClearZip.setOnClickListener(v -> {
            pressAnim(v);
            // 同步清理本地缓存副本
            delete(new File(getCacheDir(), "imported"));
            popupFiles.clear();
            builtinNames.clear();
            pkgCache.clear();
            lastZip = null;
            savePopupMemory();
            refreshZipCount();
            refreshPopupList();
            if (logger != null) logger.info("已清空弹窗包列表");
        });
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p1.rightMargin = dp(8);
        zipBtnRow.addView(btnBuiltin, p1);
        zipBtnRow.addView(btnClearZip, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        innerZip.addView(zipBtnRow);
        root.addView(cardZip);

        // 一机一码卡片
        MaterialCardView cardLicense = mdCardOutlined();
        LinearLayout innerLicense = columnNoPad();
        innerLicense.setPadding(dp(20), dp(16), dp(20), dp(12));
        cardLicense.addView(innerLicense);
        innerLicense.addView(labelInline("一机一码（注册验证）"));
        SwitchMaterial swLicense = new SwitchMaterial(this);
        swLicense.setText("注入激活验证门（激活前弹窗不出现）");
        swLicense.setChecked(sp.getBoolean(KEY_LICENSE, false));
        swLicense.setOnCheckedChangeListener((b, checked) -> {
            sp.edit().putBoolean(KEY_LICENSE, checked).apply();
            if (logger != null) {
                logger.info(checked ? "一机一码：已开启（注入后生成 RSA 密钥对，私钥请妥善保存）"
                        : "一机一码：已关闭");
            }
        });
        innerLicense.addView(swLicense);
        TextView tvLicenseNote = sub("开启后宿主首次启动需输入激活码；每个 APK 独立密钥对，"
                + "注入完成后请导出私钥，用设置页的注册机生成激活码。");
        tvLicenseNote.setPadding(0, dp(6), 0, 0);
        innerLicense.addView(tvLicenseNote);
        root.addView(cardLicense);

        // 触发策略卡片
        MaterialCardView cardStrategy = mdCardOutlined();
        LinearLayout innerStrategy = columnNoPad();
        innerStrategy.setPadding(dp(20), dp(16), dp(20), dp(12));
        cardStrategy.addView(innerStrategy);
        innerStrategy.addView(labelInline("多弹窗触发策略"));
        LinearLayout strategyRow = new LinearLayout(this);
        strategyRow.setOrientation(LinearLayout.HORIZONTAL);
        String saved = sp.getString(KEY_STRATEGY, STRATEGY_RANDOM);
        String[][] strategies = {
                {STRATEGY_RANDOM, "随机触发（每次启动弹一个）"},
                {STRATEGY_SEQUENCE, "依次触发（每次全弹出）"}
        };
        for (String[] s : strategies) {
            MaterialRadioButton rb = new MaterialRadioButton(this);
            rb.setText(s[1].split("（")[0]);
            rb.setChecked(s[0].equals(saved));
            rb.setOnClickListener(v -> {
                sp.edit().putString(KEY_STRATEGY, s[0]).apply();
                if (logger != null) logger.info("触发策略：" + s[1]);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            strategyRow.addView(rb, lp);
        }
        innerStrategy.addView(strategyRow);
        root.addView(cardStrategy);

        // 进度卡片
        MaterialCardView cardProgress = mdCardOutlined();
        LinearLayout innerProg = columnNoPad();
        innerProg.setPadding(dp(20), dp(16), dp(20), dp(16));
        cardProgress.addView(innerProg);

        LinearProgressIndicator progress = new LinearProgressIndicator(this);
        progress.setMax(100);
        progress.setProgress(0);
        innerProg.addView(progress);

        TextView tvStage = new TextView(this);
        tvStage.setTextSize(12);
        tvStage.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvStage.setPadding(0, dp(10), 0, 0);
        tvStage.setText("待命中 · 0%");
        innerProg.addView(tvStage);

        MaterialButton btnStart = mdButtonFilled("出击！");
        LinearLayout.LayoutParams blp = lpMatchWrap();
        blp.topMargin = dp(12);
        innerProg.addView(btnStart, blp);
        root.addView(cardProgress);

        // 日志面板
        logger = new LogConsole(this);
        LinearLayout.LayoutParams llp = lpMatchWrap();
        llp.topMargin = dp(8);
        root.addView(logger.view(), llp);

        logger.info("注入器就绪☆ 等待选择文件");

        btnStart.setOnClickListener(v -> {
            pressAnim(v);
            if (hostFile == null || !hostFile.exists()) {
                logger.warn("请先选择目标 APK");
                snack("请先选择目标 APK");
                return;
            }
            if (popupFiles.isEmpty()) {
                logger.warn("请先选择至少一个弹窗包");
                snack("请先选择至少一个弹窗包");
                return;
            }
            logger.clear();
            String strategy = sp.getString(KEY_STRATEGY, STRATEGY_RANDOM);
            boolean licenseOn = sp.getBoolean(KEY_LICENSE, false);
            logger.info("开始注入 · " + popupFiles.size() + " 个弹窗 · 策略："
                    + (licenseOn ? "一机一码（全部弹窗，激活后显示）"
                    : STRATEGY_RANDOM.equals(strategy) ? "随机" : "依次"));
            doFullInject(hostFile, new ArrayList<>(popupFiles), strategy, progress, tvStage);
        });

        refreshZipCount();
        animateInStagger(root);
    }

    private void refreshZipCount() {
        if (tvZipCount != null) {
            tvZipCount.setText("已选 " + popupFiles.size() + " 个弹窗包");
        }
    }

    /** 重建已选弹窗包列表（名称 + 入口 + 操作按钮） */
    private void refreshPopupList() {
        if (popupListContainer == null) return;
        popupListContainer.removeAllViews();
        for (int i = 0; i < popupFiles.size(); i++) {
            final File f = popupFiles.get(i);
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.bg_item_soft);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams rlp = lpMatchWrap();
            rlp.bottomMargin = dp(8);
            popupListContainer.addView(row, rlp);

            TextView tvName = new TextView(this);
            tvName.setTextSize(14);
            tvName.setTypeface(Typeface.DEFAULT_BOLD);
            String mark = builtinNames.containsKey(f) ? "★内置 · " : "";
            tvName.setText((idx + 1) + ". " + mark + stripZipName(f));
            row.addView(tvName);

            TextView tvEntry = new TextView(this);
            tvEntry.setTextSize(12);
            tvEntry.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
            tvEntry.setPadding(0, dp(2), 0, dp(6));
            PopupPackage cached = pkgCache.get(f);
            tvEntry.setText(cached != null ? entryText(cached) : "解析中…");
            row.addView(tvEntry);

            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            MaterialButton btnPrev = mdButtonTonal("预览");
            btnPrev.setPadding(dp(8), 0, dp(8), 0);
            btnPrev.setOnClickListener(v -> { pressAnim(v); previewPopupInline(f); });
            MaterialButton btnEdit = mdButtonTonal("编辑");
            btnEdit.setPadding(dp(8), 0, dp(8), 0);
            btnEdit.setOnClickListener(v -> { pressAnim(v); editPopup(f); });
            MaterialButton btnDel = mdButtonTonal("移除");
            btnDel.setPadding(dp(8), 0, dp(8), 0);
            btnDel.setOnClickListener(v -> {
                pressAnim(v);
                popupFiles.remove(idx);
                builtinNames.remove(f);
                pkgCache.remove(f);
                savePopupMemory();
                refreshZipCount();
                refreshPopupList();
            });
            LinearLayout.LayoutParams brp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            btnRow.addView(btnPrev, brp);
            btnRow.addView(btnEdit, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams m3 = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            m3.leftMargin = dp(8);
            btnRow.addView(btnDel, m3);
            row.addView(btnRow);

            // 后台解析入口信息
            if (cached == null) {
                new Thread(() -> {
                    PopupPackage p = parsePopupQuietly(f);
                    if (p == null) return;
                    pkgCache.put(f, p);
                    runOnUiThread(() -> {
                        if (popupFiles.contains(f)) refreshPopupList();
                    });
                }).start();
            }
        }
    }

    private String entryText(PopupPackage p) {
        StringBuilder sb = new StringBuilder();
        String[] e = p.primaryEntry();
        sb.append("入口：").append(e == null ? "未识别" : e[0].replace('/', '.') + "#" + e[1]);
        sb.append(" · 片段 ").append(p.snippets.size()).append(" 个");
        if (!p.warnings.isEmpty()) {
            sb.append(" · ").append(p.warnings.get(0));
        }
        return sb.toString();
    }

    private PopupPackage parsePopupQuietly(File f) {
        try {
            List<PopupPackage> pkgs = PopupPackage.parseZip(f, stripZipName(f),
                    new File(getCacheDir(), "pkg_parse"));
            for (PopupPackage p : pkgs) {
                if (p.injectable()) return p;
            }
            return pkgs.isEmpty() ? null : pkgs.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripZipName(File f) {
        String n = f.getName();
        return n.endsWith(".zip") ? n.substring(0, n.length() - 4) : n;
    }

    private void doFullInject(File apkSrc, List<File> zipSrcs, String strategy,
                              LinearProgressIndicator progress, TextView stage) {
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            boolean isRandom = STRATEGY_RANDOM.equals(strategy);
            boolean licenseOn = sp.getBoolean(KEY_LICENSE, false);
            File work = new File(getFilesDir(), "inject_work");
            try {
                delete(work);
                work.mkdirs();

                updateUi(progress, stage, 5, "复制 APK");
                logger.info("复制 APK 到工作目录…");
                File apkCopy = new File(work, "base.apk");
                try (InputStream in = new FileInputStream(apkSrc);
                     FileOutputStream out = new FileOutputStream(apkCopy)) {
                    byte[] buf = new byte[8192]; int n; long total = 0;
                    while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
                    logger.ok("APK 复制完成 · " + (total / 1024) + " KB");
                }

                updateUi(progress, stage, 15, "解析 AndroidManifest");
                logger.info("解析 AndroidManifest.xml…");
                List<String> launchers = parseManifest(apkCopy);
                if (launchers.isEmpty()) throw new IllegalStateException("没找到启动器 Activity");
                logger.ok("找到 " + launchers.size() + " 个启动类");

                updateUi(progress, stage, 25, "选择启动类");
                final String selectedActivity = askUserWhichActivity(launchers);
                if (selectedActivity == null) {
                    logger.warn("已取消");
                    runOnUiThread(() -> stage.setText("已取消"));
                    return;
                }
                logger.ok("启动类：" + selectedActivity);

                // 解析全部弹窗包（通用格式，嵌套变体自动展开）
                updateUi(progress, stage, 35, "解析弹窗包");
                List<PopupPackage> pkgs = new ArrayList<>();
                for (File zf : zipSrcs) {
                    List<PopupPackage> parsed = PopupPackage.parseZip(zf,
                            stripZipName(zf), new File(work, "pkg_parse"));
                    if (parsed.isEmpty()) {
                        logger.warn("跳过（未识别出弹窗内容）：" + stripZipName(zf));
                        continue;
                    }
                    for (PopupPackage p : parsed) {
                        if (p.injectable()) {
                            pkgs.add(p);
                            for (String w : p.warnings) logger.warn(p.name + "：" + w);
                        } else {
                            logger.warn("跳过不可注入包：" + p.name
                                    + "（缺 dex 或调用声明）");
                        }
                    }
                }
                if (pkgs.isEmpty()) {
                    throw new IllegalStateException("没有可注入的弹窗包");
                }
                logger.ok("弹窗包就绪 " + pkgs.size() + " 个");

                List<File> popupDexes = new ArrayList<>();
                List<File> assetsDirs = new ArrayList<>();
                List<List<List<String>>> snippetGroups = new ArrayList<>();
                List<List<String>> allSnippets = new ArrayList<>();
                for (PopupPackage p : pkgs) {
                    // 主 dex 走 DexClassLoader 加载链路，其余 dex 直接追加进 APK
                    popupDexes.add(p.dexFiles.get(0));
                    for (int i = 1; i < p.dexFiles.size(); i++) popupDexes.add(p.dexFiles.get(i));
                    if (p.assetsDir != null) assetsDirs.add(p.assetsDir);
                    snippetGroups.add(p.snippets);
                    allSnippets.addAll(p.snippets);
                    String[] e = p.primaryEntry();
                    logger.ok("· " + p.name + " → "
                            + (e == null ? "(片段注入)" : e[0] + "#" + e[1])
                            + " · " + p.snippets.size() + " 片段");
                }

                updateUi(progress, stage, 50, "定位启动类所在 dex");
                File targetDex = findDexContainingClass(apkCopy, selectedActivity, work);
                if (targetDex == null) {
                    throw new IllegalStateException("找不到 " + selectedActivity + " 所在的 dex");
                }
                String dexEntryName = targetDex.getName();
                logger.ok("目标 dex：" + dexEntryName);

                updateUi(progress, stage, 60, "插桩与汇编");
                List<File> extraDexes = new ArrayList<>(popupDexes);
                File patchedDex;
                String licensePriv = null;
                if (licenseOn) {
                    logger.info("一机一码：生成 RSA 密钥对…");
                    String[] kp = LicenseSmali.generateKeyPair();
                    licensePriv = kp[1];
                    logger.info("宿主 onCreate 改写为验证门结构 → 插桩 → 汇编…");
                    File[] r = SmaliInjector.injectWithLicense(targetDex, selectedActivity,
                            LicenseSmali.GATE_CLASS, allSnippets, work, 4, kp[0]);
                    patchedDex = r[0];
                    extraDexes.add(r[1]);
                    logger.ok("验证门已就位（gate dex " + r[1].length() + " B）");
                } else if (isRandom) {
                    logger.info("汇编随机调度器（片段版）…");
                    File dispatcherDex = DispatcherSmali.buildSnippets(snippetGroups, work);
                    extraDexes.add(dispatcherDex);
                    logger.ok("调度器就绪 · " + pkgs.size() + " 个候选弹窗");
                    logger.info("宿主 onCreate 插入调度调用…");
                    patchedDex = SmaliInjector.inject(targetDex, selectedActivity,
                            java.util.Collections.singletonList(
                                    "invoke-static {p0}, " + DispatcherSmali.CLASS_TYPE
                                            + "->dispatch(Landroid/content/Context;)V"),
                            work, 4);
                } else {
                    logger.info("依次插入 " + allSnippets.size() + " 个调用片段…");
                    patchedDex = SmaliInjector.injectSnippets(targetDex, selectedActivity,
                            allSnippets, work, 4);
                }
                logger.ok("smali 汇编完成，生成 patched.dex");

                updateUi(progress, stage, 80, "重打包 APK");
                int maxDex = findMaxDexIndex(apkCopy);
                List<String> extraDexNames = new ArrayList<>();
                for (int i = 0; i < extraDexes.size(); i++) {
                    extraDexNames.add("classes" + (maxDex + 1 + i) + ".dex");
                }
                logger.info("追加 dex：" + extraDexNames);
                File unsignedApk = new File(work, "injected-unsigned.apk");
                List<String> repackLog = new ArrayList<>();
                ApkRepackager.repack(apkCopy, patchedDex, dexEntryName,
                        extraDexes, extraDexNames, assetsDirs, unsignedApk, repackLog);
                for (String line : repackLog) logger.info(line);

                updateUi(progress, stage, 92, "重签名 APK");
                File outApk = new File(getFilesDir(), "injected.apk");
                if (outApk.exists()) outApk.delete();
                ApkSignerService.signApk(unsignedApk, outApk, getApplicationContext(),
                        sp.getString(KEY_KS_PASS, ""), sp.getString(KEY_KS_ALIAS, ""),
                        line -> logger.info(line));

                long cost = System.currentTimeMillis() - t0;
                updateUi(progress, stage, 100, "完成：" + outApk.getAbsolutePath());
                logger.ok("注入完成☆ 耗时 " + cost + " ms");
                logger.ok("输出：" + outApk.getAbsolutePath());
                if (licenseOn && licensePriv != null) {
                    exportLicenseKey(licensePriv, selectedActivity);
                }
                runOnUiThread(() -> snack("出击成功☆" + outApk.getAbsolutePath()));

            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                logger.error("注入失败：" + msg);
                runOnUiThread(() -> {
                    stage.setText("失败：" + msg);
                    snack("失败：" + msg);
                });
            } finally {
                delete(work);
                logger.info("工作目录已清理");
            }
        }).start();
    }

    /** 注入完成后导出一机一码私钥（展示 + 保存副本） */
    private void exportLicenseKey(String privB64, String hostClass) {
        String machineNote = "每个 APK 独立密钥对。宿主设备首次启动会显示机器码，\n"
                + "将机器码和下面的私钥填入「设置 → 一机一码注册机」即可生成激活码。";
        File dir = new File(getFilesDir(), "license_keys");
        dir.mkdirs();
        File keyFile = new File(dir, "license_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                        .format(new Date()) + ".key");
        try (FileOutputStream fo = new FileOutputStream(keyFile)) {
            fo.write(privB64.getBytes("UTF-8"));
        } catch (Exception e) {
            logger.error("私钥保存失败：" + e.getMessage());
        }
        runOnUiThread(() -> {
            LinearLayout holder = columnNoPad();
            holder.setPadding(dp(24), dp(8), dp(24), 0);
            TextView tvNote = new TextView(this);
            tvNote.setTextSize(13);
            tvNote.setText(machineNote);
            holder.addView(tvNote);
            TextView tvKey = new TextView(this);
            tvKey.setTextSize(12);
            tvKey.setTypeface(Typeface.MONOSPACE);
            tvKey.setTextIsSelectable(true);
            tvKey.setText(privB64);
            tvKey.setBackgroundResource(R.drawable.bg_item_soft);
            tvKey.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams klp = lpMatchWrap();
            klp.topMargin = dp(10);
            holder.addView(tvKey, klp);
            TextView tvPath = new TextView(this);
            tvPath.setTextSize(12);
            tvPath.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
            tvPath.setPadding(0, dp(8), 0, 0);
            tvPath.setText("已保存副本：" + keyFile.getAbsolutePath());
            holder.addView(tvPath);
            new AlertDialog.Builder(this)
                    .setTitle("一机一码私钥（请妥善保存）")
                    .setView(holder)
                    .setPositiveButton("复制", (d, w) -> {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager)
                                        getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "license_key", privB64));
                        snack("私钥已复制到剪贴板");
                    })
                    .setNegativeButton("关闭", null)
                    .show();
            logger.ok("私钥已导出：" + keyFile.getAbsolutePath());
        });
    }

    private List<String> parseManifest(File apk) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("AndroidManifest.xml");
            if (e == null) throw new IllegalStateException("APK 缺少 AndroidManifest.xml");
            try (InputStream in = zf.getInputStream(e)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
        }

        ManifestInfo info = new AxmlParser(bos.toByteArray()).parse();
        logger.info("宿主包名：" + info.packageName);
        return info.launcherTargets;
    }

    private File findDexContainingClass(File apk, String className, File workDir) throws Exception {
        String dexType = "L" + className.replace('.', '/') + ";";

        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().matches("classes(\\d*)\\.dex")) continue;

                File tmp = new File(workDir, e.getName());
                try (InputStream in = zf.getInputStream(e);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }

                try {
                    org.jf.dexlib2.dexbacked.DexBackedDexFile dexFile =
                            org.jf.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(
                                    org.jf.dexlib2.Opcodes.getDefault(),
                                    new FileInputStream(tmp));
                    for (org.jf.dexlib2.iface.ClassDef cd : dexFile.getClasses()) {
                        if (cd.getType().equals(dexType)) return tmp;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private int findMaxDexIndex(File apk) throws Exception {
        int max = 0;
        try (ZipFile zf = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.matches("classes\\d*\\.dex")) {
                    String num = n.replaceAll("\\D", "");
                    int idx = num.isEmpty() ? 1 : Integer.parseInt(num);
                    if (idx > max) max = idx;
                }
            }
        }
        return max;
    }

    private String askUserWhichActivity(List<String> activities) {
        final AtomicReference<String> result = new AtomicReference<>(null);
        final AtomicReference<AlertDialog> dialogRef = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);

        runOnUiThread(() -> {
            AlertDialog dlg = new AlertDialog.Builder(MainActivity.this)
                    .setTitle("选择要注入的启动类")
                    .setItems(activities.toArray(new String[0]),
                            (d, which) -> {
                                result.set(activities.get(which));
                                latch.countDown();
                            })
                    .setOnCancelListener(d -> latch.countDown())
                    .show();
            dialogRef.set(dlg);
        });

        try {
            boolean done = latch.await(30, TimeUnit.SECONDS);
            if (!done) {
                runOnUiThread(() -> {
                    AlertDialog d = dialogRef.get();
                    if (d != null && d.isShowing()) d.dismiss();
                });
                logger.warn("选择超时（30 秒），自动使用第一个启动类：" + activities.get(0));
                return activities.get(0);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return activities.get(0);
        }
        String chosen = result.get();
        if (chosen == null) {
            logger.warn("已取消，自动使用第一个启动类：" + activities.get(0));
            return activities.get(0);
        }
        return chosen;
    }

    // =========================================================
    //                      即时预览与多选内置弹窗
    // =========================================================
    private void previewPopupInline(File zipFile) {
        if (zipFile == null || !zipFile.exists()) return;
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "popup_inline_preview");
                delete(dir);
                dir.mkdirs();
                PopupPackage.unzipTolerant(zipFile, dir);

                PopupPackage p = parsePopupQuietly(zipFile);
                if (p == null) throw new IllegalStateException("未识别出弹窗内容");
                String[] entry = p.primaryEntry();
                if (entry == null) throw new IllegalStateException("未识别出调用入口");
                String cls = entry[0].replace('/', '.');
                String mtd = entry[1];

                // 多 dex 包：全部 dex 以 ":" 拼接交给 DexClassLoader
                StringBuilder dexPath = new StringBuilder();
                for (File d : p.dexFiles) {
                    d.setReadable(true, false);
                    d.setWritable(false, false);
                    if (dexPath.length() > 0) dexPath.append(':');
                    dexPath.append(d.getAbsolutePath());
                }

                File opt = new File(getCacheDir(), "dex_inline_opt");
                delete(opt);
                opt.mkdirs();
                DexClassLoader loader = new DexClassLoader(
                        dexPath.toString(), opt.getAbsolutePath(), null, getClassLoader());
                Class<?> clazz = loader.loadClass(cls);

                java.lang.reflect.Method method = clazz.getMethod(mtd, Context.class);
                logger.info("即时预览 · " + cls + "#" + mtd);

                runOnUiThread(() -> {
                    try {
                        method.invoke(null, MainActivity.this);
                        logger.ok("预览弹窗已弹出☆");
                    } catch (Exception e) {
                        Throwable c = e.getCause() != null ? e.getCause() : e;
                        logger.error("预览失败：" + c);
                    }
                });
            } catch (Throwable e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (logger != null) logger.warn("即时预览跳过：" + msg);
            }
        }).start();
    }

    private void useBuiltinPopupMulti(LogConsole log) {
        final List<String> names = BuiltinPopups.names();
        final boolean[] checked = new boolean[names.size()];
        new AlertDialog.Builder(this)
                .setTitle("内置弹窗库（可多选）")
                .setMultiChoiceItems(names.toArray(new String[0]), checked,
                        (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("添加到注入列表", (d, which) -> {
                    List<String> picked = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) picked.add(names.get(i));
                    }
                    if (picked.isEmpty()) {
                        snack("没有勾选任何弹窗");
                        return;
                    }
                    new Thread(() -> {
                        try {
                            File lastBuilt = null;
                            for (String name : picked) {
                                File zip = BuiltinPopups.build(getApplicationContext(), name,
                                        line -> {
                                            if (log != null) log.info(line);
                                        });
                                if (!popupFiles.contains(zip)) popupFiles.add(zip);
                                builtinNames.put(zip, name);
                                lastBuilt = zip;
                            }
                            final File built = lastBuilt;
                            final int count = picked.size();
                            runOnUiThread(() -> {
                                if (log != null) {
                                    log.ok("已添加 " + count + " 个内置弹窗包，共 "
                                            + popupFiles.size() + " 个");
                                }
                                snack("内置弹窗已加入☆（点「编辑」可调参数）");
                                refreshZipCount();
                                refreshPopupList();
                                if (built != null) lastZip = built;
                                savePopupMemory();
                            });
                        } catch (Exception e) {
                            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                            runOnUiThread(() -> {
                                if (log != null) log.error("内置弹窗生成失败：" + msg);
                                snack("生成失败：" + msg);
                            });
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // =========================================================
    //                      弹窗编辑器
    // =========================================================
    private void editPopup(File f) {
        String builtin = builtinNames.get(f);
        if (builtin != null) {
            editBuiltin(builtin, f);
        } else {
            editExternal(f);
        }
    }

    /** 内置弹窗参数编辑：读 popup.conf → 动态表单 → 重建 zip */
    private void editBuiltin(String name, File zipFile) {
        new Thread(() -> {
            Map<String, String> conf = new HashMap<>();
            try {
                File tmp = new File(getCacheDir(), "edit_conf");
                delete(tmp);
                tmp.mkdirs();
                PopupPackage.unzipTolerant(zipFile, tmp);
                File confFile = new File(tmp, "popup.conf");
                if (confFile.exists()) {
                    conf = PopupConfig.parseConf(readText(confFile));
                }
            } catch (Exception ignored) {
            }
            Map<String, String> values = PopupConfig.withDefaults(name, conf);
            runOnUiThread(() -> showBuiltinEditor(name, zipFile, values));
        }).start();
    }

    private void showBuiltinEditor(String name, File zipFile, Map<String, String> values) {
        List<PopupConfig.Field> fields = PopupConfig.fields(name);
        LinearLayout form = columnNoPad();
        form.setPadding(dp(20), dp(8), dp(20), 0);
        List<TextInputEditText> editors = new ArrayList<>();
        for (PopupConfig.Field fld : fields) {
            TextInputLayout til = new TextInputLayout(this);
            til.setHint(fld.label + (fld.multiLine ? "（支持换行）" : ""));
            TextInputEditText et = new TextInputEditText(this);
            if (fld.numeric) {
                et.setInputType(InputType.TYPE_CLASS_NUMBER);
            } else if (fld.multiLine) {
                et.setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                et.setGravity(Gravity.TOP);
                et.setMinLines(2);
                et.setMaxLines(6);
            }
            String v = values.get(fld.key);
            if (v == null) v = "";
            et.setText(v);
            til.addView(et);
            form.addView(til);
            editors.add(et);
        }

        new AlertDialog.Builder(this)
                .setTitle("编辑：" + name)
                .setView(form)
                .setPositiveButton("保存并重建", (d, w) -> {
                    Map<String, String> cfg = new HashMap<>();
                    for (int i = 0; i < fields.size(); i++) {
                        EditText et = editors.get(i);
                        cfg.put(fields.get(i).key,
                                et.getText() == null ? "" : et.getText().toString());
                    }
                    new Thread(() -> {
                        try {
                            BuiltinPopups.build(getApplicationContext(), name,
                                    line -> {
                                        if (logger != null) logger.info(line);
                                    }, cfg);
                            runOnUiThread(() -> {
                                pkgCache.remove(zipFile);
                                snack("已保存并重建☆");
                                refreshPopupList();
                            });
                        } catch (Exception e) {
                            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                            runOnUiThread(() -> {
                                if (logger != null) logger.error("重建失败：" + msg);
                                snack("重建失败：" + msg);
                            });
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 外部弹窗包文本编辑：解压到临时目录 → 选文本 → 修改 → 重打包 */
    private void editExternal(File zipFile) {
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), "edit_external_" + System.currentTimeMillis());
                delete(tmp);
                tmp.mkdirs();
                PopupPackage.unzipTolerant(zipFile, tmp);
                List<File> texts = allTextFiles(tmp, new ArrayList<>());
                runOnUiThread(() -> {
                    if (texts.isEmpty()) {
                        snack("该包没有可编辑的文本文件");
                        return;
                    }
                    showExternalTextPicker(zipFile, texts, tmp);
                });
            } catch (Exception e) {
                runOnUiThread(() -> snack("解析失败：" + e.getMessage()));
            }
        }).start();
    }

    private void showExternalTextPicker(File zipFile, List<File> texts, File baseDir) {
        List<String> names = new ArrayList<>();
        for (File t : texts) {
            names.add(relPath(baseDir, t));
        }
        new AlertDialog.Builder(this)
                .setTitle("编辑文本文件")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    File target = texts.get(which);
                    try {
                        String content = readText(target);
                        showTextEditor(zipFile, target, baseDir, content);
                    } catch (Exception e) {
                        snack("读取失败：" + e.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showTextEditor(File zipFile, File textFile, File baseDir, String content) {
        LinearLayout holder = columnNoPad();
        holder.setPadding(dp(20), dp(8), dp(20), 0);
        TextView tvPath = new TextView(this);
        tvPath.setTextSize(12);
        tvPath.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvPath.setText(relPath(baseDir, textFile));
        holder.addView(tvPath);
        EditText et = new EditText(this);
        et.setText(content);
        et.setMinLines(4);
        et.setMaxLines(14);
        et.setGravity(Gravity.TOP);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        et.setTextSize(12);
        et.setTypeface(Typeface.MONOSPACE);
        holder.addView(et);

        new AlertDialog.Builder(this)
                .setTitle("编辑文本")
                .setView(holder)
                .setPositiveButton("保存并重打包", (d, w) -> {
                    String newContent = et.getText() == null ? "" : et.getText().toString();
                    new Thread(() -> {
                        try {
                            try (FileOutputStream fo = new FileOutputStream(textFile)) {
                                fo.write(newContent.getBytes("UTF-8"));
                            }
                            File rebuilt = new File(zipFile.getParentFile(),
                                    zipFile.getName() + ".tmp");
                            PopupPackage.zipDir(baseDir, rebuilt);
                            if (!zipFile.delete() || !rebuilt.renameTo(zipFile)) {
                                throw new IllegalStateException("替换弹窗包文件失败");
                            }
                            pkgCache.remove(zipFile);
                            runOnUiThread(() -> {
                                snack("已保存并重打包☆");
                                refreshPopupList();
                            });
                        } catch (Exception e) {
                            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                            runOnUiThread(() -> snack("重打包失败：" + msg));
                        }
                    }).start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private List<File> allTextFiles(File dir, List<File> out) {
        File[] cs = dir.listFiles();
        if (cs == null) return out;
        for (File c : cs) {
            if (c.isDirectory()) allTextFiles(c, out);
            else if (c.length() > 0 && c.length() <= 1024 * 1024) out.add(c);
        }
        return out;
    }

    private static String relPath(File base, File f) {
        try {
            String b = base.getCanonicalPath();
            String c = f.getCanonicalPath();
            String rel = c.startsWith(b + File.separator)
                    ? c.substring(b.length() + 1) : f.getName();
            return rel;
        } catch (Exception e) {
            return f.getName();
        }
    }

    // =========================================================
    //                          预览页
    // =========================================================
    private void showPreviewPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = column();
        scroll.addView(root, lpMatchWrap());
        content.removeAllViews();
        content.addView(scroll, lpMatchMatch());

        LinearLayout header = columnNoPad();
        header.addView(h1("弹窗预览"));
        header.addView(sub("选弹窗包，填入口类与方法，实时预览（含 assets 图片）"));
        root.addView(header);

        MaterialCardView card = mdCardOutlined();
        LinearLayout inner = columnNoPad();
        inner.setPadding(dp(20), dp(16), dp(20), dp(16));
        card.addView(inner);

        TextInputLayout tilClass = new TextInputLayout(this);
        tilClass.setHint("入口类，如 com.example.popup.Popup");
        TextInputEditText etClass = new TextInputEditText(this);
        tilClass.addView(etClass);
        inner.addView(tilClass);

        TextInputLayout tilMethod = new TextInputLayout(this);
        tilMethod.setHint("入口方法，如 show");
        TextInputEditText etMethod = new TextInputEditText(this);
        tilMethod.addView(etMethod);
        inner.addView(tilMethod);

        MaterialButton btnZip = mdButtonTonal("选择弹窗包");
        btnZip.setOnClickListener(v -> { pressAnim(v); pickZip(); });
        inner.addView(btnZip);

        MaterialButton btnBuiltinPreview = mdButtonTonal("使用内置弹窗");
        btnBuiltinPreview.setOnClickListener(v -> { pressAnim(v); useBuiltinPopupMulti(previewLogger); });
        inner.addView(btnBuiltinPreview);

        MaterialButton btnAutoFill = mdButtonTonal("自动填入上次选择");
        btnAutoFill.setOnClickListener(v -> {
            pressAnim(v);
            if (lastZip == null || !lastZip.exists()) {
                snack("还没有选择过弹窗包");
                return;
            }
            new Thread(() -> {
                PopupPackage p = parsePopupQuietly(lastZip);
                String[] cm = p == null ? null : p.primaryEntry();
                runOnUiThread(() -> {
                    if (cm == null) {
                        snack("解析失败");
                        return;
                    }
                    etClass.setText(cm[0].replace('/', '.'));
                    etMethod.setText(cm[1]);
                    snack("已填入：" + cm[0] + "#" + cm[1]);
                });
            }).start();
        });
        inner.addView(btnAutoFill);

        MaterialButton btnPreview = mdButtonFilled("预览弹窗");
        LinearLayout.LayoutParams lp = lpMatchWrap();
        lp.topMargin = dp(8);
        inner.addView(btnPreview, lp);

        root.addView(card);

        TextView tvAssets = new TextView(this);
        tvAssets.setTextSize(13);
        tvAssets.setPadding(dp(4), dp(16), dp(4), dp(8));
        tvAssets.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvAssets.setText("assets 资源预览");
        root.addView(tvAssets);

        LinearLayout assetList = columnNoPad();
        assetList.setPadding(0, 0, 0, dp(16));
        root.addView(assetList);

        // 预览页专属日志
        previewLogger = new LogConsole(this);
        LinearLayout.LayoutParams pllp = lpMatchWrap();
        pllp.topMargin = dp(8);
        root.addView(previewLogger.view(), pllp);
        previewLogger.info("等待选择弹窗包…");

        btnPreview.setOnClickListener(v -> {
            pressAnim(v);
            if (lastZip == null || !lastZip.exists()) {
                previewLogger.warn("请先选择弹窗包");
                snack("请先选择弹窗包");
                return;
            }
            String cls = etClass.getText() == null ? "" : etClass.getText().toString().trim();
            String mtd = etMethod.getText() == null ? "" : etMethod.getText().toString().trim();
            if (cls.isEmpty() || mtd.isEmpty()) {
                previewLogger.warn("请填写入口类与方法（可用“自动填入”）");
                snack("请填写入口类与方法");
                return;
            }
            previewLogger.clear();
            previewLogger.info("开始预览 · " + cls + "#" + mtd);
            doPreview(lastZip, cls, mtd, assetList, previewLogger);
        });

        animateInStagger(root);
    }

    private void doPreview(File zipSrc, String cls, String mtd,
                           LinearLayout assetList, LogConsole log) {
        new Thread(() -> {
            try {
                File dir = new File(getCacheDir(), "popup_preview");
                delete(dir);
                dir.mkdirs();
                PopupPackage.unzipTolerant(zipSrc, dir);
                log.ok("弹窗包解压完成");

                File assetsDir = new File(dir, "assets");
                runOnUiThread(() -> {
                    assetList.removeAllViews();
                    if (assetsDir.exists()) {
                        int before = assetList.getChildCount();
                        renderAssets(assetsDir, assetList);
                        int cnt = assetList.getChildCount() - before;
                        log.info("加载 assets 图片 " + cnt + " 张");
                    } else {
                        log.info("无 assets 目录");
                    }
                });

                // 多 dex 包：全部 dex 以 ":" 拼接交给 DexClassLoader
                List<File> dexes = new ArrayList<>();
                File[] cs = dir.listFiles();
                if (cs != null) {
                    for (File c : cs) {
                        if (c.isFile() && c.getName().endsWith(".dex")) dexes.add(c);
                    }
                }
                if (dexes.isEmpty()) throw new IllegalStateException("缺少 classes.dex");

                StringBuilder dexPath = new StringBuilder();
                for (File d : dexes) {
                    // Android 10+ 拒绝加载可写路径中的 dex，必须先置为只读
                    d.setReadable(true, false);
                    d.setWritable(false, false);
                    if (dexPath.length() > 0) dexPath.append(':');
                    dexPath.append(d.getAbsolutePath());
                }

                File opt = new File(getCacheDir(), "dex_opt");
                opt.mkdirs();
                DexClassLoader loader = new DexClassLoader(
                        dexPath.toString(), opt.getAbsolutePath(), null, getClassLoader());
                log.info("DexClassLoader 已创建");

                Class<?> clazz = loader.loadClass(cls);
                log.ok("类加载成功：" + clazz.getName());

                java.lang.reflect.Method m = clazz.getMethod(mtd, Context.class);
                log.info("调用入口：" + mtd + "(Context)");

                final CountDownLatch callLatch = new CountDownLatch(1);
                final AtomicReference<Exception> callError = new AtomicReference<>(null);
                runOnUiThread(() -> {
                    try {
                        m.invoke(null, MainActivity.this);
                        log.ok("弹窗已弹出");
                        snack("弹窗已弹出☆");
                    } catch (Exception e) {
                        callError.set(e);
                    } finally {
                        callLatch.countDown();
                    }
                });
                boolean finished = callLatch.await(30, TimeUnit.SECONDS);
                if (!finished) {
                    throw new IllegalStateException("入口调用超时（30 秒），预览中止");
                }
                Exception err = callError.get();
                if (err != null) throw err;

            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                log.error("预览失败：" + msg);
                runOnUiThread(() -> snack("预览失败：" + msg));
            }
        }).start();
    }

    private void renderAssets(File dir, LinearLayout parent) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                renderAssets(f, parent);
            } else if (isImage(f.getName())) {
                try (InputStream in = new FileInputStream(f)) {
                    android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in);
                    if (bmp != null) {
                        android.widget.ImageView iv = new android.widget.ImageView(this);
                        iv.setImageBitmap(bmp);
                        iv.setAdjustViewBounds(true);
                        LinearLayout.LayoutParams lp = lpMatchWrap();
                        lp.bottomMargin = dp(8);
                        parent.addView(iv, lp);
                        iv.setAlpha(0f);
                        iv.animate().alpha(1f).setDuration(240).start();
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private boolean isImage(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                || n.endsWith(".webp") || n.endsWith(".gif");
    }

    // =========================================================
    //                          公告页
    // =========================================================
    private void showNoticePage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = column();
        scroll.addView(root, lpMatchWrap());
        content.removeAllViews();
        content.addView(scroll, lpMatchMatch());

        LinearLayout header = columnNoPad();
        header.addView(h1("远程公告"));
        header.addView(sub("从服务器拉取最新公告，离线自动回退"));
        root.addView(header);

        MaterialCardView card = mdCardOutlined();
        TextView tvBody = new TextView(this);
        tvBody.setPadding(dp(20), dp(20), dp(20), dp(20));
        tvBody.setTextSize(14);
        tvBody.setLineSpacing(0, 1.35f);
        tvBody.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvBody.setText("加载中…");
        card.addView(tvBody);
        root.addView(card);

        MaterialButton btn = mdButtonTonal("刷新公告");
        root.addView(btn);

        LogConsole netLogger = new LogConsole(this);
        LinearLayout.LayoutParams nlp = lpMatchWrap();
        nlp.topMargin = dp(16);
        root.addView(netLogger.view(), nlp);

        btn.setOnClickListener(v -> {
            pressAnim(v);
            loadNotice(tvBody, netLogger);
        });

        animateInStagger(root);
        loadNotice(tvBody, netLogger);
    }

    private void loadNotice(TextView tv, LogConsole log) {
        new Thread(() -> {
            String url = sp.getString(KEY_NOTICE_URL, "").trim();
            if (url.isEmpty()) {
                log.warn("未配置公告 URL，显示离线公告（可在设置页配置）");
                runOnUiThread(() -> tv.setText(OFFLINE_NOTICE));
                return;
            }
            log.info("拉取公告：" + url);
            String text;
            boolean online = true;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    text = sb.toString();
                }
            } catch (Exception e) {
                online = false;
                text = OFFLINE_NOTICE;
            }
            final String out = text;
            final boolean ok = online;
            if (ok) log.ok("公告拉取成功"); else log.warn("网络失败，已回退到离线公告");
            runOnUiThread(() -> {
                tv.setText(out);
                tv.setAlpha(0f);
                tv.setTranslationY(dp(10));
                tv.animate().alpha(1f).translationY(0f)
                        .setInterpolator(new DecelerateInterpolator())
                        .setDuration(280).start();
            });
        }).start();
    }

    // =========================================================
    //                          设置页（含注册机）
    // =========================================================
    private void showSettingsPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = column();
        scroll.addView(root, lpMatchWrap());
        content.removeAllViews();
        content.addView(scroll, lpMatchMatch());

        LinearLayout header = columnNoPad();
        header.addView(h1("设置"));
        header.addView(sub("公告 URL · 激活密钥 · 签名密钥 · 注册机"));
        root.addView(header);

        MaterialCardView cardNotice = mdCardOutlined();
        LinearLayout innerNotice = columnNoPad();
        innerNotice.setPadding(dp(20), dp(16), dp(20), dp(16));
        cardNotice.addView(innerNotice);

        innerNotice.addView(labelInline("公告 URL"));
        TextInputLayout tilUrl = new TextInputLayout(this);
        tilUrl.setHint("https://…（留空显示离线公告）");
        TextInputEditText etUrl = new TextInputEditText(this);
        etUrl.setText(sp.getString(KEY_NOTICE_URL, ""));
        tilUrl.addView(etUrl);
        innerNotice.addView(tilUrl);

        MaterialButton btnSaveNotice = mdButtonTonal("保存公告 URL");
        innerNotice.addView(btnSaveNotice);
        root.addView(cardNotice);

        MaterialCardView cardKs = mdCardOutlined();
        LinearLayout innerKs = columnNoPad();
        innerKs.setPadding(dp(20), dp(16), dp(20), dp(16));
        cardKs.addView(innerKs);

        innerKs.addView(labelInline("签名密钥"));
        TextView tvKsState = new TextView(this);
        tvKsState.setTextSize(13);
        tvKsState.setPadding(0, dp(4), 0, dp(8));
        tvKsState.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tvKsState.setText(keystoreStateText());
        innerKs.addView(tvKsState);

        TextInputLayout tilPass = new TextInputLayout(this);
        tilPass.setHint("keystore 密码（可留空）");
        TextInputEditText etPass = new TextInputEditText(this);
        etPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tilPass.addView(etPass);
        innerKs.addView(tilPass);

        TextInputLayout tilAlias = new TextInputLayout(this);
        tilAlias.setHint("别名（可留空，默认取第一个）");
        TextInputEditText etAlias = new TextInputEditText(this);
        etAlias.setText(sp.getString(KEY_KS_ALIAS, ""));
        tilAlias.addView(etAlias);
        innerKs.addView(tilAlias);

        MaterialButton btnImport = mdButtonFilled("导入 keystore 文件");
        innerKs.addView(btnImport);

        MaterialButton btnReset = mdButtonTonal("恢复自动生成密钥");
        LinearLayout.LayoutParams rlp = lpMatchWrap();
        rlp.topMargin = dp(8);
        innerKs.addView(btnReset, rlp);
        root.addView(cardKs);

        // 一机一码注册机区块
        MaterialCardView cardRsa = mdCardOutlined();
        LinearLayout innerRsa = columnNoPad();
        innerRsa.setPadding(dp(20), dp(16), dp(20), dp(16));
        cardRsa.addView(innerRsa);

        innerRsa.addView(labelInline("一机一码注册机（RSA）"));
        TextView tvRsaNote = sub("粘贴注入时导出的私钥，输入宿主设备显示的机器码，"
                + "即可生成对应的激活码。");
        tvRsaNote.setPadding(0, 0, 0, dp(8));
        innerRsa.addView(tvRsaNote);

        TextInputLayout tilPriv = new TextInputLayout(this);
        tilPriv.setHint("RSA 私钥（Base64，注入时导出）");
        TextInputEditText etPriv = new TextInputEditText(this);
        etPriv.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etPriv.setGravity(Gravity.TOP);
        etPriv.setMinLines(2);
        etPriv.setMaxLines(6);
        etPriv.setTextSize(12);
        etPriv.setTypeface(Typeface.MONOSPACE);
        tilPriv.addView(etPriv);
        innerRsa.addView(tilPriv);

        TextInputLayout tilMid = new TextInputLayout(this);
        tilMid.setHint("机器码（宿主激活框显示）");
        TextInputEditText etMid = new TextInputEditText(this);
        etMid.setTypeface(Typeface.MONOSPACE);
        tilMid.addView(etMid);
        LinearLayout.LayoutParams mlp = lpMatchWrap();
        mlp.topMargin = dp(8);
        innerRsa.addView(tilMid, mlp);

        MaterialButton btnRsaGen = mdButtonFilled("生成激活码");
        LinearLayout.LayoutParams rg = lpMatchWrap();
        rg.topMargin = dp(12);
        innerRsa.addView(btnRsaGen, rg);

        TextView tvRsaOut = new TextView(this);
        tvRsaOut.setTextSize(14);
        tvRsaOut.setPadding(0, dp(16), 0, 0);
        tvRsaOut.setTextColor(colorAttr(com.google.android.material.R.attr.colorPrimary));
        tvRsaOut.setTextIsSelectable(true);
        tvRsaOut.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        innerRsa.addView(tvRsaOut);
        root.addView(cardRsa);

        btnRsaGen.setOnClickListener(v -> {
            pressAnim(v);
            String priv = etPriv.getText() == null ? "" : etPriv.getText().toString().trim();
            String mid = etMid.getText() == null ? "" : etMid.getText().toString().trim();
            if (priv.isEmpty() || mid.isEmpty()) {
                snack("请填写私钥与机器码");
                return;
            }
            try {
                String code = LicenseSmali.signMachineCode(priv, mid);
                tvRsaOut.setText("激活码：\n" + code);
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("activation", code));
                settingsLogger.ok("激活码已生成并复制：" + code);
                snack("激活码已生成☆（已复制）");
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                settingsLogger.error("生成失败：" + msg);
                tvRsaOut.setText("生成失败：" + msg);
            }
        });

        // 注册机区块（时间窗口令，旧版兼容）
        MaterialCardView cardReg = mdCardOutlined();
        LinearLayout innerReg = columnNoPad();
        innerReg.setPadding(dp(20), dp(16), dp(20), dp(16));
        cardReg.addView(innerReg);

        innerReg.addView(labelInline("注册机"));
        TextInputLayout tilSecret = new TextInputLayout(this);
        tilSecret.setHint("自定义密钥（可留空使用默认）");
        TextInputEditText etSecret = new TextInputEditText(this);
        etSecret.setText(sp.getString(KEY_SECRET, ""));
        tilSecret.addView(etSecret);
        innerReg.addView(tilSecret);

        MaterialButton btnGen = mdButtonFilled("生成激活码");
        innerReg.addView(btnGen);

        TextInputLayout tilCode = new TextInputLayout(this);
        tilCode.setHint("输入激活码校验");
        TextInputEditText etCode = new TextInputEditText(this);
        tilCode.addView(etCode);
        LinearLayout.LayoutParams clp = lpMatchWrap();
        clp.topMargin = dp(12);
        innerReg.addView(tilCode, clp);

        MaterialButton btnVerify = mdButtonTonal("校验激活码");
        innerReg.addView(btnVerify);

        TextView tvOut = new TextView(this);
        tvOut.setTextSize(22);
        tvOut.setGravity(Gravity.CENTER);
        tvOut.setPadding(0, dp(20), 0, 0);
        tvOut.setTextColor(colorAttr(com.google.android.material.R.attr.colorPrimary));
        tvOut.setTextIsSelectable(true);
        tvOut.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        innerReg.addView(tvOut);
        root.addView(cardReg);

        LogConsole setLogger = new LogConsole(this);
        LinearLayout.LayoutParams slp = lpMatchWrap();
        slp.topMargin = dp(12);
        root.addView(setLogger.view(), slp);
        settingsLogger = setLogger;
        setLogger.info("设置就绪☆");

        btnSaveNotice.setOnClickListener(v -> {
            pressAnim(v);
            String url = etUrl.getText() == null ? "" : etUrl.getText().toString().trim();
            sp.edit().putString(KEY_NOTICE_URL, url).apply();
            setLogger.ok(url.isEmpty() ? "公告 URL 已清空（将显示离线公告）" : "公告 URL 已保存：" + url);
            snack("已保存");
        });

        btnImport.setOnClickListener(v -> {
            pressAnim(v);
            String pass = etPass.getText() == null ? "" : etPass.getText().toString();
            String alias = etAlias.getText() == null ? "" : etAlias.getText().toString().trim();
            sp.edit().putString(KEY_KS_PASS, pass).putString(KEY_KS_ALIAS, alias).apply();
            Intent it = new Intent(Intent.ACTION_GET_CONTENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            try {
                ksPicker.launch(Intent.createChooser(it, "选择 keystore 文件"));
            } catch (Exception e) {
                setLogger.error("无法打开文件选择器：" + e.getMessage());
            }
        });

        btnReset.setOnClickListener(v -> {
            pressAnim(v);
            sp.edit().remove(KEY_KS_FILE).remove(KEY_KS_PASS).remove(KEY_KS_ALIAS).apply();
            ksUri = null;
            tvKsState.setText(keystoreStateText());
            setLogger.ok("已恢复自动生成密钥（AndroidKeyStore）");
            snack("已恢复");
        });

        btnGen.setOnClickListener(v -> {
            pressAnim(v);
            String secret = resolveSecret(etSecret);
            sp.edit().putString(KEY_SECRET,
                    etSecret.getText() == null ? "" : etSecret.getText().toString()).apply();
            String code;
            try {
                code = generateCode(secret);
            } catch (Exception ex) {
                settingsLogger.error("生成失败：" + ex.getMessage());
                snack("生成失败：" + ex.getMessage());
                return;
            }
            tvOut.setTextColor(colorAttr(com.google.android.material.R.attr.colorPrimary));
            tvOut.setText(code);
            tvOut.setAlpha(0f);
            tvOut.setScaleX(0.85f);
            tvOut.animate().alpha(1f).scaleX(1f)
                    .setInterpolator(new OvershootInterpolator(2.2f))
                    .setDuration(320).start();
            setLogger.ok("生成激活码：" + code);
            snack("激活码已生成☆");
        });

        btnVerify.setOnClickListener(v -> {
            pressAnim(v);
            String secret = resolveSecret(etSecret);
            String input = etCode.getText() == null ? "" : etCode.getText().toString();
            boolean ok;
            try {
                ok = verifyCode(secret, input);
            } catch (Exception ex) {
                setLogger.error("校验异常：" + ex.getMessage());
                snack("校验异常：" + ex.getMessage());
                return;
            }
            tvOut.setText(ok ? "激活成功" : "激活码无效");
            tvOut.setTextColor(ok ? 0xFF2E7D32 : 0xFFC62828);
            tvOut.setAlpha(0f);
            tvOut.setScaleX(0.9f);
            tvOut.animate().alpha(1f).scaleX(1f)
                    .setInterpolator(new OvershootInterpolator(2f))
                    .setDuration(280).start();
            if (ok) setLogger.ok("校验通过"); else setLogger.error("校验失败");
        });

        animateInStagger(root);
    }

    private String keystoreStateText() {
        String ksFile = sp.getString(KEY_KS_FILE, "");
        String ksAlias = sp.getString(KEY_KS_ALIAS, "");
        if (ksFile.isEmpty()) {
            return "当前：自动生成密钥（AndroidKeyStore，首次签名时创建）";
        }
        return "当前：已导入密钥文件（"
                + ksFile + (ksAlias.isEmpty() ? "" : " · 别名 " + ksAlias) + "）";
    }

    private void promptKeystoreImport() {
        LogConsole log = settingsLogger;
        try {
            File tmp = new File(getCacheDir(), "import_ks.tmp");
            try (InputStream in = getContentResolver().openInputStream(ksUri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }

            String pass = sp.getString(KEY_KS_PASS, "");
            String alias = sp.getString(KEY_KS_ALIAS, "");
            File importDir = getFilesDir();
            String usedAlias = ApkSignerService.importKeystore(tmp, pass, alias, importDir,
                    line -> {
                        if (logger != null) logger.info(line);
                        if (log != null) log.info(line);
                    });

            sp.edit()
                    .putString(KEY_KS_FILE, ApkSignerService.IMPORTED_KS_NAME)
                    .putString(KEY_KS_ALIAS, usedAlias)
                    .apply();
            tmp.delete();
            if (log != null) log.ok("导入完成，签名将使用已导入密钥（别名 " + usedAlias + "）");
            snack("keystore 导入成功");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            if (log != null) log.error("导入失败：" + msg);
            snack("导入失败：" + msg);
        }
        ksUri = null;
    }

    // =========================================================
    //                          工具方法
    // =========================================================
    private String resolveSecret(EditText et) {
        String s = et.getText() == null ? "" : et.getText().toString().trim();
        return s.isEmpty() ? DEFAULT_SECRET : s;
    }

    private String generateCode(String secret) {
        String ts = minuteStamp(System.currentTimeMillis());
        String h = hmac(secret, ts + "|aurora").substring(0, 16).toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(h.charAt(i));
        }
        return sb.toString();
    }

    private boolean verifyCode(String secret, String input) {
        String clean = input.replace("-", "").trim().toUpperCase(Locale.ROOT);
        if (clean.length() != 16) return false;
        long now = System.currentTimeMillis();
        for (long off = -WINDOW_MS; off <= WINDOW_MS; off += 60_000L) {
            String ts = minuteStamp(now + off);
            String expect = hmac(secret, ts + "|aurora").substring(0, 16)
                    .toUpperCase(Locale.ROOT);
            if (expect.equals(clean)) return true;
        }
        return false;
    }

    private String minuteStamp(long ms) {
        return new SimpleDateFormat("yyyyMMddHHmm", Locale.ROOT).format(new Date(ms));
    }

    private String hmac(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败：" + e.getMessage(), e);
        }
    }

    private String shortUri(Uri uri) {
        if (uri == null) return "null";
        String s = uri.getLastPathSegment();
        return s == null ? uri.toString() : s;
    }

    private String readText(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int r = in.read(b);
            return new String(b, 0, Math.max(r, 0), StandardCharsets.UTF_8);
        }
    }

    private void delete(File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) delete(k);
        }
        f.delete();
    }

    // =========================================================
    //                     View / Layout 工具
    // =========================================================
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(24), dp(20), dp(24));
        return l;
    }

    private LinearLayout columnNoPad() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView h1(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(26);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface));
        return tv;
    }

    private TextView sub(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        tv.setLineSpacing(0, 1.3f);
        tv.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        tv.setPadding(0, dp(6), 0, dp(20));
        return tv;
    }

    private TextView labelInline(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(12);
        tv.setLetterSpacing(0.08f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, 0, 0, dp(6));
        tv.setTextColor(colorAttr(com.google.android.material.R.attr.colorPrimary));
        return tv;
    }

    /** MD3 Outlined Card */
    private MaterialCardView mdCardOutlined() {
        MaterialCardView c = new MaterialCardView(this);
        c.setRadius(dp(20));
        c.setCardElevation(0f);
        c.setStrokeWidth(dp(1));
        c.setStrokeColor(colorAttr(com.google.android.material.R.attr.colorOutline));
        c.setCardBackgroundColor(colorAttr(com.google.android.material.R.attr.colorSurface));
        LinearLayout.LayoutParams lp = lpMatchWrap();
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    private MaterialButton mdButtonFilled(String text) {
        MaterialButton b = new MaterialButton(this);
        b.setText(text);
        b.setCornerRadius(dp(14));
        b.setLayoutParams(lpMatchWrap());
        return b;
    }

    private MaterialButton mdButtonTonal(String text) {
        MaterialButton b = new MaterialButton(new android.view.ContextThemeWrapper(this,
                com.google.android.material.R.style.Widget_Material3_Button_TonalButton),
                null, 0);
        b.setText(text);
        b.setCornerRadius(dp(14));
        b.setLayoutParams(lpMatchWrap());
        return b;
    }

    private int colorAttr(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private FrameLayout.LayoutParams lpMatchMatch() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void snack(String msg) {
        View v = findViewById(android.R.id.content);
        Snackbar.make(v, msg, Snackbar.LENGTH_SHORT).show();
    }

    // =========================================================
    //                          动效
    // =========================================================
    private void pressAnim(View v) {
        v.animate()
                .scaleX(0.94f).scaleY(0.94f)
                .setDuration(80)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> v.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(220)
                        .setInterpolator(new OvershootInterpolator(2.5f))
                        .start())
                .start();
    }

    private void animateIn(View v, long delayMs) {
        v.setAlpha(0f);
        v.setTranslationY(dp(24));
        v.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(380)
                .setInterpolator(new DecelerateInterpolator(1.8f))
                .start();
    }

    private void animateInStagger(ViewGroup container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            animateIn(container.getChildAt(i), i * 55L);
        }
    }

    private void animateBottomIcon() {
        View icon = bottomNav.getChildAt(0);
        if (icon != null) {
            icon.setScaleX(0.85f);
            icon.setScaleY(0.85f);
            icon.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(new OvershootInterpolator(2.2f))
                    .setDuration(260).start();
        }
    }

    private void updateUi(LinearProgressIndicator p, TextView t, int percent, String stage) {
        runOnUiThread(() -> {
            p.setProgressCompat(percent, true);
            t.animate().alpha(0.4f).setDuration(90)
                    .withEndAction(() -> {
                        t.setText(stage + " · " + percent + "%");
                        t.animate().alpha(1f).setDuration(120).start();
                    }).start();
        });
    }

    private void pickApk() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/vnd.android.package-archive");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        apkPicker.launch(i);
    }

    private void pickZip() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        zipPicker.launch(i);
    }

    // =========================================================
    //                          LogConsole
    // =========================================================
    /**
     * MD3 风格日志控制台：双色主题（浅色粉白 / 深色夜紫）+ 等宽字体 + 分级着色。
     */
    public static class LogConsole {

        public enum Level { INFO, WARN, ERROR, SUCCESS }

        private static class Entry {
            final long time;
            final Level level;
            final String msg;
            Entry(Level l, String m) {
                time = System.currentTimeMillis();
                level = l;
                msg = m;
            }
        }

        private final LinearLayout root;
        private final TextView tvLog;
        private final NestedScrollView scroll;
        private final List<Entry> entries = new ArrayList<>();
        private final Context ctx;
        private final boolean night;

        public LogConsole(Context context) {
            this.ctx = context;
            int uiMode = context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            this.night = uiMode == Configuration.UI_MODE_NIGHT_YES;

            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            if (night) {
                bg.setColor(0xFF221A33);
                bg.setStroke(dp(1), 0xFF3A2E52);
            } else {
                bg.setColor(0xFFFFF0F5);
                bg.setStroke(dp(1), 0xFFF3C6D8);
            }
            bg.setCornerRadius(dp(18));
            root.setBackground(bg);
            root.setPadding(dp(14), dp(12), dp(14), dp(14));

            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = new TextView(context);
            title.setText(night ? "● 运行日志" : "● 樱花日志");
            title.setTextSize(12);
            title.setLetterSpacing(0.1f);
            title.setTypeface(null, Typeface.BOLD);
            title.setTextColor(night ? 0xFFB0BEC5 : 0xFF8D5E72);
            header.addView(title, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            MaterialButton btnClear = new MaterialButton(context, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnClear.setText("清空");
            btnClear.setTextSize(10);
            btnClear.setTextColor(night ? 0xFFB0BEC5 : 0xFF8D5E72);
            btnClear.setStrokeColor(android.content.res.ColorStateList.valueOf(
                    night ? 0xFF3A2E52 : 0xFFF3C6D8));
            btnClear.setMinHeight(0);
            btnClear.setMinimumHeight(0);
            btnClear.setMinimumWidth(0);
            btnClear.setPadding(dp(14), dp(4), dp(14), dp(4));
            btnClear.setCornerRadius(dp(10));
            btnClear.setOnClickListener(v -> clear());
            header.addView(btnClear);

            root.addView(header);

            View divider = new View(context);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            dlp.topMargin = dp(8);
            dlp.bottomMargin = dp(8);
            divider.setBackgroundColor(night ? 0xFF3A2E52 : 0xFFF3C6D8);
            root.addView(divider, dlp);

            scroll = new NestedScrollView(context);
            scroll.setFillViewport(false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(240));
            root.addView(scroll, slp);

            tvLog = new TextView(context);
            tvLog.setTextSize(11.5f);
            tvLog.setTypeface(Typeface.MONOSPACE);
            tvLog.setTextIsSelectable(true);
            tvLog.setLineSpacing(0, 1.3f);
            tvLog.setTextColor(night ? 0xFFE8EAED : 0xFF4E342E);
            scroll.addView(tvLog);
        }

        public View view() { return root; }

        public void clear() {
            entries.clear();
            tvLog.setText("");
        }

        public void info(String m)  { add(Level.INFO, m); }
        public void warn(String m)  { add(Level.WARN, m); }
        public void error(String m) { add(Level.ERROR, m); }
        public void ok(String m)    { add(Level.SUCCESS, m); }

        private void add(Level level, String msg) {
            Entry e = new Entry(level, msg);
            entries.add(e);
            postAppend(e);
        }

        private void postAppend(Entry e) {
            runOnUi(() -> {
                String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                        .format(new Date(e.time));
                String tag = tagOf(e.level);
                String color = colorOf(e.level, night);

                SpannableStringBuilder sb = new SpannableStringBuilder();

                int s = sb.length();
                sb.append(time).append("  ");
                sb.setSpan(new ForegroundColorSpan(night ? 0xFF9AA0A6 : 0xFFB08CA0),
                        s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                s = sb.length();
                sb.append(tag).append("  ");
                sb.setSpan(new ForegroundColorSpan(
                                android.graphics.Color.parseColor(color)),
                        s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), s, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                s = sb.length();
                sb.append(e.msg).append('\n');
                sb.setSpan(new ForegroundColorSpan(night ? 0xFFE8EAED : 0xFF4E342E),
                        s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                tvLog.append(sb);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
        }

        private void runOnUi(Runnable r) {
            if (ctx instanceof Activity) {
                ((Activity) ctx).runOnUiThread(r);
            } else {
                r.run();
            }
        }

        private static String tagOf(Level l) {
            switch (l) {
                case WARN: return "WARN ";
                case ERROR: return "ERROR";
                case SUCCESS: return " OK  ";
                default: return "INFO ";
            }
        }

        private static String colorOf(Level l, boolean night) {
            switch (l) {
                case WARN: return night ? "#FFB74D" : "#F57C00";
                case ERROR: return night ? "#EF5350" : "#D32F2F";
                case SUCCESS: return night ? "#66BB6A" : "#2E7D32";
                default: return night ? "#64B5F6" : "#BA68C8";
            }
        }

        private int dp(int v) {
            return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
        }
    }
}
