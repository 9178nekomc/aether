package com.ai.github.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 现代化富色彩终端 TUI 渲染引擎。
 * 提供：
 * - 渐变 ASCII Logo 与欢迎仪表盘（Dashboard）
 * - 实时顶部/底部状态栏（显示模型、内置Git分支与工作区状态、安全/YOLO模式、Token消耗）
 * - Unicode 圆角框线面板（╭─╮│╰─╯）
 * - 彩色 Unified Diff 逐行对比视图（红删绿增 + 行号对齐）
 * - 终端 Markdown 语法高亮渲染（代码块、表格、标题、引用、加粗）
 * - 异步 Braille 动态旋转加载器（Spinner + 实时计时）
 */
public class TuiRenderer {

    private TuiTheme theme;
    private int terminalWidth = 96;

    public TuiRenderer(TuiTheme theme) {
        this.theme = theme != null ? theme : TuiTheme.get("claude");
        detectTerminalWidth();
    }

    public void setTheme(TuiTheme theme) {
        this.theme = theme;
    }

    public TuiTheme getTheme() {
        return theme;
    }

    private void detectTerminalWidth() {
        try {
            String cols = System.getenv("COLUMNS");
            if (cols != null && !cols.isBlank()) {
                int parsed = Integer.parseInt(cols.trim());
                if (parsed >= 60 && parsed <= 200) {
                    this.terminalWidth = parsed;
                }
            }
        } catch (Exception ignored) {
        }
    }

    public void clearScreen() {
        System.out.print("\u001B[2J\u001B[H");
        System.out.flush();
    }

    /**
     * 渲染启动主视觉 Banner 与核心状态概览面板。
     */
    public void printWelcomeBanner(ConfigManager config, String githubUser, String gitSummary) {
        String[] logo = {
                "  ██████╗ ██╗████████╗██╗  ██╗██╗   ██╗██████╗      █████╗  ██████╗ ███████╗███╗   ██╗████████╗",
                " ██╔════╝ ██║╚══██╔══╝██║  ██║██║   ██║██╔══██╗    ██╔══██╗██╔════╝ ██╔════╝████╗  ██║╚══██╔══╝",
                " ██║  ███╗██║   ██║   ███████║██║   ██║██████╔╝    ███████║██║  ███╗█████╗  ██╔██╗ ██║   ██║   ",
                " ██║   ██║██║   ██║   ██╔══██║██║   ██║██╔══██╗    ██╔══██║██║   ██║██╔══╝  ██║╚██╗██║   ██║   ",
                " ╚██████╔╝██║   ██║   ██║  ██║╚██████╔╝██████╔╝    ██║  ██║╚██████╔╝███████╗██║ ╚████║   ██║   ",
                "  ╚═════╝ ╚═╝   ╚═╝   ╚═╝  ╚═╝ ╚═════╝ ╚═════╝     ╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚═╝  ╚═══╝   ╚═╝   "
        };

        System.out.println();
        for (String line : logo) {
            System.out.println(TuiTheme.BOLD + theme.gradientText(line));
        }

        String subtitle = "   ⚡ Autonomous Coding & GitHub Engine v" + AppInfo.version()
                + "  •  Built-in Pure-Java Git  •  " + AppInfo.COMMAND + " CLI ⚡";
        System.out.println(theme.secondary + TuiTheme.BOLD + subtitle + TuiTheme.RESET);
        System.out.println();

        List<String> infoLines = new ArrayList<>();
        String userDisplay = (githubUser == null || githubUser.isBlank())
                ? theme.warning + "未连接 (离线/仅本地代码与内置Git模式)" + TuiTheme.RESET
                : theme.success + "@" + githubUser + TuiTheme.RESET;
        String modeBadge = switch (config.getPermissionMode()) {
            case YOLO -> TuiTheme.bgRgb(220, 38, 38) + TuiTheme.rgb(255, 255, 255) + TuiTheme.BOLD + " ⚡ YOLO MODE (全自动免确认执行) " + TuiTheme.RESET;
            case STRICT -> TuiTheme.bgRgb(90, 60, 160) + TuiTheme.rgb(255, 255, 255) + TuiTheme.BOLD + " 🔒 STRICT MODE (每次调用都确认) " + TuiTheme.RESET;
            case SAFE -> TuiTheme.bgRgb(30, 136, 229) + TuiTheme.rgb(255, 255, 255) + TuiTheme.BOLD + " 🛡️ SAFE MODE (敏感操作前询问确认) " + TuiTheme.RESET;
        };

        infoLines.add(theme.accent + "👤 GitHub 身份 : " + TuiTheme.RESET + userDisplay
                + "    " + theme.accent + "🎨 当前主题 : " + TuiTheme.RESET + theme.primary + theme.displayName + TuiTheme.RESET);
        infoLines.add(theme.accent + "🧠 AI 模型引擎 : " + TuiTheme.RESET + theme.text + config.getOpenaiModel() + TuiTheme.RESET
                + theme.muted + " (" + config.getOpenaiEndpoint() + ")" + TuiTheme.RESET);
        infoLines.add(theme.accent + "📂 工作区目录  : " + TuiTheme.RESET + theme.info + config.getWorkspaceDir() + TuiTheme.RESET
                + "    " + theme.accent + "🌿 内置 Git : " + TuiTheme.RESET + gitSummary);
        infoLines.add(theme.accent + "🔐 执行安全策略: " + TuiTheme.RESET + modeBadge
                + theme.muted + "  (mode=" + config.getPermissionMode().id
                + ", 越界写入: " + (config.isAllowOutsideWorkspace() ? "允许" : "禁止") + ")" + TuiTheme.RESET);
        infoLines.add(theme.muted + "📌 启动命令: " + theme.accent + AppInfo.COMMAND + TuiTheme.RESET
                + theme.muted + " [目录]  •  配置: " + config.getConfigPath() + TuiTheme.RESET);
        infoLines.add(theme.muted + "💡 快捷指令: 输入 " + theme.accent + "/help" + theme.muted + " 查看命令面板 | "
                + theme.accent + "/yolo" + theme.muted + " 切换YOLO模式 | "
                + theme.accent + "/theme" + theme.muted + " 切换配色 | "
                + theme.accent + "/git <cmd>" + theme.muted + " 执行内置Git" + TuiTheme.RESET);

        printBox("🚀 控制台状态总览 (SYSTEM DASHBOARD)", infoLines, theme.primary);
    }

    /**
     * 渲染每次输入前的紧凑状态栏（Status Bar）。
     */
    public void printStatusBar(ConfigManager config, String gitBranchInfo, int turnCount, int estimatedTokens) {
        String modeTag = switch (config.getPermissionMode()) {
            case YOLO -> theme.danger + TuiTheme.BOLD + "⚡ YOLO:ON" + TuiTheme.RESET;
            case STRICT -> theme.secondary + TuiTheme.BOLD + "🔒 STRICT:ASK" + TuiTheme.RESET;
            case SAFE -> theme.success + TuiTheme.BOLD + "🛡️ SAFE:ASK" + TuiTheme.RESET;
        };

        String bar = theme.muted + "╭─[" + TuiTheme.RESET
                + theme.primary + TuiTheme.BOLD + "🤖 " + config.getOpenaiModel() + TuiTheme.RESET
                + theme.muted + "]─[" + TuiTheme.RESET
                + theme.info + "🌿 " + gitBranchInfo + TuiTheme.RESET
                + theme.muted + "]─[" + TuiTheme.RESET
                + modeTag
                + theme.muted + "]─[" + TuiTheme.RESET
                + theme.secondary + "📊 Turn:" + turnCount + " ~" + estimatedTokens + " tok" + TuiTheme.RESET
                + theme.muted + "]─[" + TuiTheme.RESET
                + theme.text + "📁 " + config.getWorkspaceDir() + TuiTheme.RESET
                + theme.muted + "]" + TuiTheme.RESET;
        System.out.println(bar);
    }

    /**
     * 打印提示符（紧接在状态栏下方）。
     */
    public void printPrompt(boolean yoloMode) {
        String icon = yoloMode ? theme.danger + "⚡" : theme.primary + "❯";
        System.out.print(theme.muted + "╰─" + icon + TuiTheme.BOLD + " " + TuiTheme.RESET);
        System.out.flush();
    }

    /**
     * 绘制带标题的 Unicode 圆角边框面板。
     */
    public void printBox(String title, List<String> lines, String borderColor) {
        int innerWidth = Math.max(68, terminalWidth - 4);
        String topTitle = " " + title + " ";
        int titleVisLen = visibleLength(topTitle);
        int rightDashes = Math.max(2, innerWidth - titleVisLen - 2);

        System.out.println(borderColor + "╭──" + TuiTheme.BOLD + topTitle + TuiTheme.RESET
                + borderColor + "─".repeat(rightDashes) + "╮" + TuiTheme.RESET);

        for (String rawLine : lines) {
            for (String wrapped : wrapLine(rawLine, innerWidth - 2)) {
                int vis = visibleLength(wrapped);
                int pad = Math.max(0, innerWidth - 2 - vis);
                System.out.println(borderColor + "│ " + TuiTheme.RESET + wrapped + " ".repeat(pad) + borderColor + " │" + TuiTheme.RESET);
            }
        }

        System.out.println(borderColor + "╰" + "─".repeat(innerWidth) + "╯" + TuiTheme.RESET);
    }

    /**
     * 渲染工具调用卡片（显示工具名称、关键参数摘要）。
     */
    public void printToolCallBanner(int stepIndex, String toolName, String summary, PermissionGate.RiskLevel risk) {
        String riskBadge = switch (risk) {
            case READ_ONLY -> theme.info + "[READ]" + TuiTheme.RESET;
            case MUTATING -> theme.warning + "[WRITE/MUTATE]" + TuiTheme.RESET;
            case DESTRUCTIVE -> theme.danger + TuiTheme.BOLD + "[HIGH-RISK]" + TuiTheme.RESET;
        };
        System.out.println();
        System.out.println(theme.primary + "┌─ 🔧 " + TuiTheme.BOLD + "Step #" + stepIndex + " 调用工具: "
                + theme.accent + toolName + " " + riskBadge + TuiTheme.RESET);
        if (summary != null && !summary.isBlank()) {
            System.out.println(theme.primary + "│  " + theme.muted + "参数摘要: " + theme.text + summary + TuiTheme.RESET);
        }
        System.out.println(theme.primary + "└" + "─".repeat(Math.min(72, terminalWidth - 4)) + TuiTheme.RESET);
    }

    /**
     * 渲染工具执行结果摘要。
     */
    public void printToolResult(String toolName, boolean ok, String preview, long elapsedMs) {
        String icon = ok ? theme.success + "✔" : theme.danger + "✖";
        String color = ok ? theme.success : theme.danger;
        System.out.println("  " + icon + " " + color + TuiTheme.BOLD + toolName + TuiTheme.RESET
                + theme.muted + " (" + elapsedMs + " ms)" + TuiTheme.RESET);
        if (preview != null && !preview.isBlank()) {
            String[] lines = preview.split("\r?\n");
            int maxLines = Math.min(lines.length, 14);
            for (int i = 0; i < maxLines; i++) {
                String line = lines[i];
                if (line.length() > 120) {
                    line = line.substring(0, 117) + "...";
                }
                System.out.println(theme.muted + "    │ " + TuiTheme.RESET + colorizeDiffOrLogLine(line));
            }
            if (lines.length > maxLines) {
                System.out.println(theme.muted + "    └── ... (共 " + lines.length + " 行，已折叠其余 " + (lines.length - maxLines) + " 行)" + TuiTheme.RESET);
            }
        }
    }

    /**
     * 渲染文件修改前后的彩色 Unified Diff 对比面板（仿 Claude Code 编辑预览效果）。
     */
    public void printDiffPreview(String filePath, String oldContent, String newContent) {
        List<String> diffLines = computeSimpleDiff(oldContent, newContent, 45);
        List<String> styled = new ArrayList<>();
        for (String line : diffLines) {
            styled.add(colorizeDiffOrLogLine(line));
        }
        printBox("📝 变更预览 (DIFF PREVIEW): " + filePath, styled, theme.warning);
    }

    private String colorizeDiffOrLogLine(String line) {
        if (line.startsWith("+++") || line.startsWith("---")) {
            return theme.accent + TuiTheme.BOLD + line + TuiTheme.RESET;
        } else if (line.startsWith("@@")) {
            return theme.info + line + TuiTheme.RESET;
        } else if (line.startsWith("+")) {
            return theme.success + line + TuiTheme.RESET;
        } else if (line.startsWith("-")) {
            return theme.danger + line + TuiTheme.RESET;
        }
        return theme.text + line + TuiTheme.RESET;
    }

    /**
     * 计算轻量级逐行对比 Diff，用于在写入或编辑文件前向用户展示可视化变更。
     */
    public static List<String> computeSimpleDiff(String oldText, String newText, int maxOutputLines) {
        List<String> out = new ArrayList<>();
        String[] oldLines = (oldText == null || oldText.isEmpty()) ? new String[0] : oldText.split("\r?\n", -1);
        String[] newLines = (newText == null || newText.isEmpty()) ? new String[0] : newText.split("\r?\n", -1);

        if (oldLines.length == 0) {
            out.add("@@ 新建文件 (共 " + newLines.length + " 行) @@");
            for (int i = 0; i < Math.min(newLines.length, maxOutputLines); i++) {
                out.add(String.format("+ %4d │ %s", i + 1, newLines[i]));
            }
            if (newLines.length > maxOutputLines) {
                out.add("... 其余 " + (newLines.length - maxOutputLines) + " 行新增内容已省略 ...");
            }
            return out;
        }

        // 基于 LCS 窗口的简洁行级对比
        int i = 0, j = 0;
        int shown = 0;
        while ((i < oldLines.length || j < newLines.length) && shown < maxOutputLines) {
            if (i < oldLines.length && j < newLines.length && oldLines[i].equals(newLines[j])) {
                i++;
                j++;
            } else {
                int contextStart = Math.max(0, i - 1);
                out.add(String.format("@@ -%d +%d @@", contextStart + 1, j + 1));
                shown++;
                // 查找下一个同步锚点
                int syncOld = -1, syncNew = -1;
                outer:
                for (int lookAhead = 1; lookAhead <= 12; lookAhead++) {
                    for (int oi = i; oi <= Math.min(oldLines.length - 1, i + lookAhead); oi++) {
                        for (int nj = j; nj <= Math.min(newLines.length - 1, j + lookAhead); nj++) {
                            if (oldLines[oi].equals(newLines[nj])) {
                                syncOld = oi;
                                syncNew = nj;
                                break outer;
                            }
                        }
                    }
                }
                if (syncOld == -1) {
                    syncOld = Math.min(oldLines.length, i + 8);
                    syncNew = Math.min(newLines.length, j + 8);
                }
                while (i < syncOld && shown < maxOutputLines) {
                    out.add(String.format("- %4d │ %s", i + 1, oldLines[i]));
                    i++;
                    shown++;
                }
                while (j < syncNew && shown < maxOutputLines) {
                    out.add(String.format("+ %4d │ %s", j + 1, newLines[j]));
                    j++;
                    shown++;
                }
            }
        }
        if (out.isEmpty()) {
            out.add("  (文件内容未发生变化)");
        } else if (i < oldLines.length || j < newLines.length) {
            out.add("... (更多差异已省略，完整变更共涉及新文件 " + newLines.length + " 行) ...");
        }
        return out;
    }

    /**
     * 将 AI 的 Markdown 回答渲染为带色彩的终端富文本。
     */
    public void renderMarkdownResponse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return;
        }
        System.out.println();
        System.out.println(theme.primary + "╭── " + TuiTheme.BOLD + "🤖 Agent 响应" + TuiTheme.RESET
                + theme.primary + " " + "─".repeat(Math.max(10, terminalWidth - 20)) + TuiTheme.RESET);

        String[] lines = markdown.split("\r?\n");
        boolean inCodeBlock = false;
        int codeLineNum = 1;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock;
                if (inCodeBlock) {
                    String lang = trimmed.substring(3).trim();
                    codeLineNum = 1;
                    System.out.println(theme.primary + "│ " + theme.muted + "┌── [Code"
                            + (lang.isEmpty() ? "" : ": " + lang) + "] " + "─".repeat(36) + TuiTheme.RESET);
                } else {
                    System.out.println(theme.primary + "│ " + theme.muted + "└──" + "─".repeat(46) + TuiTheme.RESET);
                }
                continue;
            }

            if (inCodeBlock) {
                System.out.printf("%s│ %s│ %3d │ %s%s%s%n",
                        theme.primary, theme.muted, codeLineNum++, theme.accent, line, TuiTheme.RESET);
                continue;
            }

            if (trimmed.startsWith("### ")) {
                System.out.println(theme.primary + "│ " + theme.secondary + TuiTheme.BOLD + "▍ " + trimmed.substring(4) + TuiTheme.RESET);
            } else if (trimmed.startsWith("## ")) {
                System.out.println(theme.primary + "│ " + theme.primary + TuiTheme.BOLD + "▌ " + trimmed.substring(3) + TuiTheme.RESET);
            } else if (trimmed.startsWith("# ")) {
                System.out.println(theme.primary + "│ " + TuiTheme.BOLD + theme.gradientText("█ " + trimmed.substring(2)));
            } else if (trimmed.startsWith("> ")) {
                System.out.println(theme.primary + "│ " + theme.muted + "┃ " + TuiTheme.ITALIC + theme.info + trimmed.substring(2) + TuiTheme.RESET);
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                System.out.println(theme.primary + "│ " + theme.accent + "  • " + TuiTheme.RESET + formatInlineMarkdown(trimmed.substring(2)));
            } else {
                System.out.println(theme.primary + "│ " + TuiTheme.RESET + formatInlineMarkdown(line));
            }
        }

        System.out.println(theme.primary + "╰" + "─".repeat(Math.max(16, terminalWidth - 4)) + TuiTheme.RESET);
    }

    private String formatInlineMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 高亮行内代码 `code` 与加粗 **bold**
        String formatted = text.replaceAll("`([^`]+)`", theme.accent + TuiTheme.BOLD + "$1" + TuiTheme.RESET + theme.text);
        formatted = formatted.replaceAll("\\*\\*([^*]+)\\*\\*", theme.secondary + TuiTheme.BOLD + "$1" + TuiTheme.RESET + theme.text);
        return theme.text + formatted + TuiTheme.RESET;
    }

    public void printSuccess(String msg) {
        System.out.println(theme.success + TuiTheme.BOLD + "  ✔ " + msg + TuiTheme.RESET);
    }

    public void printWarning(String msg) {
        System.out.println(theme.warning + TuiTheme.BOLD + "  ⚠ " + msg + TuiTheme.RESET);
    }

    public void printError(String msg) {
        System.out.println(theme.danger + TuiTheme.BOLD + "  ✖ " + msg + TuiTheme.RESET);
    }

    public void printInfo(String msg) {
        System.out.println(theme.info + "  ℹ " + msg + TuiTheme.RESET);
    }

    /**
     * 启动异步 Braille 旋转进度动画，在 AI 思考或网络请求期间提供流畅视觉反馈。
     */
    public Spinner startSpinner(String label) {
        Spinner spinner = new Spinner(label, theme);
        spinner.start();
        return spinner;
    }

    public static class Spinner {
        private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
        private final String label;
        private final TuiTheme theme;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread worker;
        private final long startTime = System.currentTimeMillis();

        public Spinner(String label, TuiTheme theme) {
            this.label = label;
            this.theme = theme;
            this.worker = new Thread(this::runLoop, "tui-spinner");
            this.worker.setDaemon(true);
        }

        public void start() {
            worker.start();
        }

        private void runLoop() {
            int idx = 0;
            while (running.get()) {
                double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                String frame = FRAMES[idx % FRAMES.length];
                System.out.printf("\r  %s%s %s%s %s(%.1fs)%s  ",
                        theme.primary + TuiTheme.BOLD, frame,
                        theme.text, label,
                        theme.muted, elapsed, TuiTheme.RESET);
                System.out.flush();
                idx++;
                try {
                    Thread.sleep(90);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        public void stop() {
            if (running.compareAndSet(true, false)) {
                try {
                    worker.join(250);
                } catch (InterruptedException ignored) {
                }
                System.out.print("\r" + " ".repeat(88) + "\r");
                System.out.flush();
            }
        }
    }

    /**
     * 计算去除 ANSI 转义序列后的终端可见字符宽度（中文/全角字符按 2 列计算）。
     */
    public static int visibleLength(String s) {
        if (s == null) {
            return 0;
        }
        String stripped = s.replaceAll("\u001B\\[[;\\d]*m", "");
        int len = 0;
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c >= 0x2E80 && c <= 0x9FFF || c >= 0xFF00 && c <= 0xFFEF) {
                len += 2;
            } else {
                len += 1;
            }
        }
        return len;
    }

    private List<String> wrapLine(String line, int maxVisWidth) {
        List<String> res = new ArrayList<>();
        if (line == null) {
            res.add("");
            return res;
        }
        if (visibleLength(line) <= maxVisWidth) {
            res.add(line);
            return res;
        }
        // 若包含复杂 ANSI 颜色且超长，直接保留原行防止截断转义符
        res.add(line);
        return res;
    }
}
