package com.example.injector.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置弹窗的参数定义与 popup.conf 读写。
 *
 * conf 格式：每行 key=value，空行与 # 开头注释行忽略；
 * 值中的换行以字面量 \n 存储。
 */
public class PopupConfig {

    public static class Field {
        public final String key;
        public final String label;
        public final String def;
        public final boolean multiLine;
        public final boolean numeric;

        public Field(String key, String label, String def, boolean multiLine, boolean numeric) {
            this.key = key;
            this.label = label;
            this.def = def;
            this.multiLine = multiLine;
            this.numeric = numeric;
        }
    }

    private static final Map<String, List<Field>> SCHEMA = new LinkedHashMap<>();

    private static void reg(String name, Field... fs) {
        List<Field> list = new ArrayList<>();
        for (Field f : fs) list.add(f);
        SCHEMA.put(name, list);
    }

    private static final Field F_CANCELABLE = new Field("cancelable", "可点击外部关闭", "1", false, false);

    static {
        reg("你好弹窗",
                new Field("title", "标题", "Injector 内置弹窗", false, false),
                new Field("content", "内容", "你好！这是由 Injector 注入的测试弹窗。\\n看到我说明注入链路已经完全打通。", true, false),
                new Field("btn1", "按钮文字", "知道了", false, false),
                F_CANCELABLE);
        reg("多按钮弹窗",
                new Field("title", "标题", "内置多按钮弹窗", false, false),
                new Field("content", "内容", "三个按钮都未绑定监听器，点击任意按钮自动关闭。", true, false),
                new Field("btn1", "按钮1文字", "确定", false, false),
                new Field("btn2", "按钮2文字", "取消", false, false),
                new Field("btn3", "按钮3文字", "中性", false, false),
                F_CANCELABLE);
        reg("图片弹窗",
                new Field("title", "标题", "内置图片弹窗", false, false),
                new Field("content", "说明文字", "图片来自弹窗包 assets/xypopups/banner.png。", true, false),
                new Field("btn1", "按钮文字", "知道了", false, false),
                F_CANCELABLE);
        reg("输入弹窗",
                new Field("title", "标题", "内置输入弹窗", false, false),
                new Field("hint", "输入框提示", "在这里输入点什么…", false, false),
                new Field("btn1", "按钮文字", "读取输入", false, false),
                new Field("echo", "回显前缀", "你输入了：", false, false),
                F_CANCELABLE);
        reg("更新提示",
                new Field("title", "标题", "发现新版本 v2.0", false, false),
                new Field("content", "内容", "修复了已知问题，优化了使用体验。\\n现在要更新吗？", true, false),
                new Field("btn1", "主按钮文字", "立即更新", false, false),
                new Field("btn2", "次按钮文字", "稍后再说", false, false),
                new Field("update_url", "主按钮跳转地址（留空则无动作）", "", false, false),
                F_CANCELABLE);
        reg("二次元公告",
                new Field("title", "标题", "☆今日公告☆", false, false),
                new Field("content", "内容", "欢迎来到樱花小屋~\\n今天也要元气满满哦！", true, false),
                new Field("btn1", "按钮文字", "收下啦☆", false, false),
                F_CANCELABLE);
        reg("倒计时公告",
                new Field("title", "标题", "倒计时公告", false, false),
                new Field("seconds", "倒计时秒数", "10", false, true),
                F_CANCELABLE);
        reg("进度条弹窗",
                new Field("title", "标题", "请稍候", false, false),
                new Field("text", "提示文字", "努力加载中…", false, false),
                new Field("ms", "加载时长（毫秒）", "3000", false, true),
                F_CANCELABLE);
    }

    public static List<Field> fields(String builtinName) {
        List<Field> fs = SCHEMA.get(builtinName);
        return fs == null ? new ArrayList<Field>() : new ArrayList<>(fs);
    }

    public static Map<String, String> defaults(String builtinName) {
        Map<String, String> out = new LinkedHashMap<>();
        List<Field> fs = SCHEMA.get(builtinName);
        if (fs != null) for (Field f : fs) out.put(f.key, decode(f.def));
        return out;
    }

    /** 合并：defaults 打底，conf 中的键覆盖 */
    public static Map<String, String> withDefaults(String builtinName, Map<String, String> overrides) {
        Map<String, String> out = defaults(builtinName);
        if (overrides != null) {
            for (Map.Entry<String, String> e : overrides.entrySet()) {
                if (out.containsKey(e.getKey()) && e.getValue() != null) out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    public static String toConf(String builtinName, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("# popup.conf - 内置弹窗参数（Injector 生成）\n");
        for (Field f : fields(builtinName)) {
            String v = values.get(f.key);
            if (v == null) v = f.def;
            sb.append(f.key).append("=").append(encode(v)).append("\n");
        }
        return sb.toString();
    }

    public static Map<String, String> parseConf(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            out.put(t.substring(0, eq), decode(t.substring(eq + 1)));
        }
        return out;
    }

    private static String encode(String v) {
        return v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String decode(String v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                if (n == 'n') sb.append('\n');
                else if (n == 'r') sb.append('\r');
                else if (n == '\\') sb.append('\\');
                else sb.append(n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
