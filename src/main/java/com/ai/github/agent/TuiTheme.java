package com.ai.github.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 终端 TUI 色彩主题系统（支持 ANSI TrueColor / 256色与优雅降级）。
 * 内置 5 套高颜值主题：
 * 1. claude    - Claude Code 风格暖琥珀与陶土橙配深灰底色
 * 2. cyberpunk - 赛博霓虹（青蓝 + 品红 + 荧光紫）
 * 3. codex     - OpenAI Codex 风格翡翠绿与极客青
 * 4. dracula   - 经典德古拉暗紫与粉青配色
 * 5. nord      - 极地冰蓝与冷灰护眼配色
 */
public class TuiTheme {

    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String DIM = "\u001B[2m";
    public static final String ITALIC = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";

    public final String name;
    public final String displayName;
    public final String primary;       // 主色调（边框、标题、高亮）
    public final String secondary;     // 辅助色调（副标题、标签）
    public final String accent;        // 强调色（命令、重点参数）
    public final String success;       // 成功/新增行（绿色系）
    public final String warning;       // 警告/询问/YOLO提示（琥珀/橙色系）
    public final String danger;        // 危险/删除行/错误（红色系）
    public final String info;          // 信息/工具调用（青蓝色系）
    public final String muted;         // 次要文本/行号/分隔线（暗灰）
    public final String text;          // 正文颜色
    public final int[] gradientStart;  // 渐变起始 RGB
    public final int[] gradientEnd;    // 渐变结束 RGB

    public TuiTheme(String name, String displayName,
                    String primary, String secondary, String accent,
                    String success, String warning, String danger,
                    String info, String muted, String text,
                    int[] gradientStart, int[] gradientEnd) {
        this.name = name;
        this.displayName = displayName;
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
     * 生成平滑的 RGB 渐变字符串（用于 Banner 标题与分隔条）。
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
        THEMES.put("claude", new TuiTheme(
                "claude", "Claude Code 暖珀主题",
                rgb(217, 119, 87),   // 陶土暖橙
                rgb(235, 168, 115),  // 柔和金珀
                rgb(245, 194, 107),  // 亮金黄
                rgb(98, 209, 132),   // 翠绿
                rgb(245, 166, 35),   // 琥珀黄
                rgb(239, 83, 80),    // 珊瑚红
                rgb(114, 184, 242),  // 天空蓝
                rgb(125, 133, 144),  // 石板灰
                rgb(230, 237, 243),  // 柔和白
                new int[]{217, 119, 87},
                new int[]{245, 194, 107}
        ));

        THEMES.put("cyberpunk", new TuiTheme(
                "cyberpunk", "Cyberpunk 赛博霓虹",
                rgb(0, 240, 255),    // 霓虹青
                rgb(255, 0, 160),    // 赛博品红
                rgb(189, 147, 249),  // 电光紫
                rgb(0, 255, 159),    // 荧光绿
                rgb(255, 214, 0),    // 赛博黄
                rgb(255, 42, 109),   // 激光红
                rgb(5, 217, 232),    // 冰青色
                rgb(108, 117, 147),  // 暗蓝灰
                rgb(235, 245, 255),  // 亮冰白
                new int[]{0, 240, 255},
                new int[]{255, 0, 160}
        ));

        THEMES.put("codex", new TuiTheme(
                "codex", "OpenAI Codex 翡翠极客",
                rgb(16, 163, 127),   // OpenAI 翡翠绿
                rgb(56, 189, 248),   // 极客天蓝
                rgb(167, 243, 208),  // 薄荷亮绿
                rgb(52, 211, 153),   // 成功绿
                rgb(251, 191, 36),   // 警告黄
                rgb(248, 113, 113),  // 危险红
                rgb(96, 165, 250),   // 信息蓝
                rgb(113, 113, 122),  // 锌灰
                rgb(244, 244, 245),  // 纯净白
                new int[]{16, 163, 127},
                new int[]{56, 189, 248}
        ));

        THEMES.put("dracula", new TuiTheme(
                "dracula", "Dracula 德古拉暗紫",
                rgb(189, 147, 249),  // 德古拉紫
                rgb(255, 121, 198),  // 魅惑粉
                rgb(139, 233, 253),  // 冰川青
                rgb(80, 250, 123),   // 幽灵绿
                rgb(255, 184, 108),  // 南瓜橙
                rgb(255, 85, 85),    // 鲜血红
                rgb(139, 233, 253),  // 冰川青
                rgb(98, 114, 164),   // 暮光蓝灰
                rgb(248, 248, 242),  // 象牙白
                new int[]{189, 147, 249},
                new int[]{255, 121, 198}
        ));

        THEMES.put("nord", new TuiTheme(
                "nord", "Nord 极地冰蓝",
                rgb(136, 192, 208),  // 极地冰青
                rgb(129, 161, 193),  // 峡湾蓝
                rgb(180, 142, 173),  // 极光紫
                rgb(163, 190, 140),  // 苔原绿
                rgb(235, 203, 139),  // 暖阳黄
                rgb(191, 97, 106),   // 浆果红
                rgb(94, 129, 172),   // 深海蓝
                rgb(108, 122, 147),  // 寒霜灰
                rgb(236, 239, 244),  // 雪白
                new int[]{136, 192, 208},
                new int[]{180, 142, 173}
        ));
    }

    public static TuiTheme get(String name) {
        if (name == null) {
            return THEMES.get("claude");
        }
        return THEMES.getOrDefault(name.trim().toLowerCase(), THEMES.get("claude"));
    }

    public static Map<String, TuiTheme> allThemes() {
        return THEMES;
    }
}
