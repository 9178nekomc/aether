package com.ai.github.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 极简主义终端 TUI 色彩主题系统（支持 ANSI TrueColor 与亮/暗双模式）。
 *
 * 内置三大色系 × 亮色/暗色 共 6 套原创无版权风险简约主题：
 * 1. sky-dark      - 淡蓝纯白简约（暗色版，默认）：冰川淡蓝 + 纯净白，清爽极简
 * 2. sky-light     - 淡蓝纯白简约（亮色版）：晴空淡蓝 + 柔白底深蓝字，适合浅色终端
 * 3. amber-dark    - 暖橙琥珀简约（暗色版）：暖阳琥珀橙 + 象牙白
 * 4. amber-light   - 暖橙琥珀简约（亮色版）：陶土暖橙 + 深褐正文，适合浅色终端
 * 5. emerald-dark  - 翡翠森绿简约（暗色版）：薄荷翡翠绿 + 纯净白
 * 6. emerald-light - 翡翠森绿简约（亮色版）：深林翠绿 + 深灰正文，适合浅色终端
 */
public class TuiTheme {

    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String ITALIC = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";

    public final String name;
    public final String displayName;
    public final boolean isLightMode;
    public final String primary;       // 主色调（核心边框、树干、主标题）
    public final String secondary;     // 辅助色调（副标题、次级标签）
    public final String accent;        // 强调色（命令名、参数键、高亮值）
    public final String success;       // 成功/新增行/Cache命中
    public final String warning;       // 警告/询问/YOLO提示
    public final String danger;        // 危险/删除行/错误
    public final String info;          // 信息/工具节点
    public final String muted;         // 树枝线条/行号/次要说明
    public final String text;          // 正文纯白或深色文本
    public final int[] gradientStart;  // 渐变起始 RGB
    public final int[] gradientEnd;    // 渐变结束 RGB

    public TuiTheme(String name, String displayName, boolean isLightMode,
                    String primary, String secondary, String accent,
                    String success, String warning, String danger,
                    String info, String muted, String text,
                    int[] gradientStart, int[] gradientEnd) {
        this.name = name;
        this.displayName = displayName;
        this.isLightMode = isLightMode;
        this.primary = primary;
        this.secondary = secondary;
        this.accent = accent;
        this.success = success;
        this.warning = warning;
        this.danger = danger;
        this.info = info;
        this.muted = muted;
        this.text = text;
        this.gradientStart = gradientStart;
        this.gradientEnd = gradientEnd;
    }

    public static String rgb(int r, int g, int b) {
        return String.format("\u001B[38;2;%d;%d;%dm", r, g, b);
    }

    public static String bgRgb(int r, int g, int b) {
        return String.format("\u001B[48;2;%d;%d;%dm", r, g, b);
    }

    /**
     * 生成平滑的 RGB 渐变字符串（用于极简标题栏与状态分割线）。
     */
    public String gradientText(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int len = Math.max(1, input.length() - 1);
        for (int i = 0; i < input.length(); i++) {
            double ratio = (double) i / len;
            int r = (int) (gradientStart[0] + ratio * (gradientEnd[0] - gradientStart[0]));
            int g = (int) (gradientStart[1] + ratio * (gradientEnd[1] - gradientStart[1]));
            int b = (int) (gradientStart[2] + ratio * (gradientEnd[2] - gradientStart[2]));
            sb.append(rgb(r, g, b)).append(input.charAt(i));
        }
        sb.append(RESET);
        return sb.toString();
    }

    private static final Map<String, TuiTheme> THEMES = new LinkedHashMap<>();

    static {
        // 1. 淡蓝 + 纯白简约（暗色版，默认首选）
        THEMES.put("sky-dark", new TuiTheme(
                "sky-dark", "淡蓝纯白简约 · 暗色 (Sky White Dark)", false,
                rgb(125, 211, 252),  // 清透淡天蓝 (#7DD3FC)
                rgb(186, 230, 253),  // 冰川浅蓝 (#BAE6FD)
                rgb(224, 242, 254),  // 极浅冰白蓝 (#E0F2FE)
                rgb(110, 231, 183),  // 薄荷清绿
                rgb(253, 224, 71),   // 柔和明黄
                rgb(252, 165, 165),  // 柔和珊瑚红
                rgb(56, 189, 248),   // 晴空蓝
                rgb(148, 163, 184),  // 银灰蓝线条
                rgb(255, 255, 255),  // 纯白正文 (#FFFFFF)
                new int[]{125, 211, 252},
                new int[]{255, 255, 255}
        ));

        // 2. 淡蓝 + 纯白简约（亮色版，适合白底终端）
        THEMES.put("sky-light", new TuiTheme(
                "sky-light", "淡蓝纯白简约 · 亮色 (Sky White Light)", true,
                rgb(2, 132, 199),    // 清朗天蓝 (#0284C7)
                rgb(14, 165, 233),   // 亮天蓝 (#0EA5E9)
                rgb(3, 105, 161),    // 深海蓝强调 (#0369A1)
                rgb(5, 150, 105),    // 翠绿
                rgb(217, 119, 6),    // 琥珀黄
                rgb(220, 38, 38),    // 鲜明红
                rgb(37, 99, 235),    // 钴蓝
                rgb(100, 116, 139),  // 冷灰线条
                rgb(15, 23, 42),     // 深石板蓝黑正文 (#0F172A)
                new int[]{2, 132, 199},
                new int[]{56, 189, 248}
        ));

        // 3. 暖橙琥珀简约（暗色版）
        THEMES.put("amber-dark", new TuiTheme(
                "amber-dark", "暖橙琥珀简约 · 暗色 (Amber Dark)", false,
                rgb(251, 146, 60),   // 暖橙 (#FB923C)
                rgb(253, 186, 116),  // 浅琥珀 (#FDBA74)
                rgb(254, 215, 170),  // 暖杏白 (#FED7AA)
                rgb(110, 231, 183),  // 翠绿
                rgb(250, 204, 21),   // 金黄
                rgb(248, 113, 113),  // 珊瑚红
                rgb(125, 211, 252),  // 淡蓝点缀
                rgb(156, 163, 175),  // 暖灰线条
                rgb(255, 255, 255),  // 纯白正文
                new int[]{251, 146, 60},
                new int[]{254, 243, 199}
        ));

        // 4. 暖橙琥珀简约（亮色版）
        THEMES.put("amber-light", new TuiTheme(
                "amber-light", "暖橙琥珀简约 · 亮色 (Amber Light)", true,
                rgb(217, 119, 6),    // 深琥珀橙 (#D97706)
                rgb(234, 88, 12),    // 暖赤橙 (#EA580C)
                rgb(180, 83, 9),     // 古铜橙 (#B45309)
                rgb(22, 163, 74),    // 森林绿
                rgb(202, 138, 4),    // 芥末金
                rgb(220, 38, 38),    // 绯红
                rgb(2, 132, 199),    // 晴空蓝
                rgb(120, 113, 108),  // 暖砂灰线条
                rgb(28, 25, 23),     // 深炭黑正文
                new int[]{217, 119, 6},
                new int[]{245, 158, 11}
        ));

        // 5. 翡翠森绿简约（暗色版）
        THEMES.put("emerald-dark", new TuiTheme(
                "emerald-dark", "翡翠森绿简约 · 暗色 (Emerald Dark)", false,
                rgb(52, 211, 153),   // 翡翠薄荷绿 (#34D399)
                rgb(110, 231, 183),  // 浅薄荷 (#6EE7B7)
                rgb(167, 243, 208),  // 冰晶绿白 (#A7F3D0)
                rgb(74, 222, 128),   // 亮绿
                rgb(251, 191, 36),   // 琥珀黄
                rgb(248, 113, 113),  // 珊瑚红
                rgb(125, 211, 252),  // 淡天蓝
                rgb(148, 163, 184),  // 冷灰线条
                rgb(255, 255, 255),  // 纯白正文
                new int[]{52, 211, 153},
                new int[]{255, 255, 255}
        ));

        // 6. 翡翠森绿简约（亮色版）
        THEMES.put("emerald-light", new TuiTheme(
                "emerald-light", "翡翠森绿简约 · 亮色 (Emerald Light)", true,
                rgb(5, 150, 105),    // 翡翠深绿 (#059669)
                rgb(16, 185, 129),   // 翠绿 (#10B981)
                rgb(4, 120, 87),     // 森林暗绿 (#047857)
                rgb(22, 163, 74),    // 成功绿
                rgb(217, 119, 6),    // 警告橙
                rgb(220, 38, 38),    // 危险红
                rgb(2, 132, 199),    // 天空蓝
                rgb(100, 116, 139),  // 冷灰线条
                rgb(15, 23, 42),     // 深色正文
                new int[]{5, 150, 105},
                new int[]{16, 185, 129}
        ));
    }

    public static TuiTheme get(String name) {
        if (name == null || name.isBlank()) {
            return THEMES.get("sky-dark");
        }
        String key = name.trim().toLowerCase();
        // 兼容简写映射
        if (key.equals("sky") || key.equals("blue")) return THEMES.get("sky-dark");
        if (key.equals("amber") || key.equals("orange")) return THEMES.get("amber-dark");
        if (key.equals("emerald") || key.equals("green")) return THEMES.get("emerald-dark");
        return THEMES.getOrDefault(key, THEMES.get("sky-dark"));
    }

    public static Map<String, TuiTheme> allThemes() {
        return THEMES;
    }
}
