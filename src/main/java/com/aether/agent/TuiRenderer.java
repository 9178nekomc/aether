package com.aether.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极简主义富色彩终端 TUI 渲染引擎（v3.0 树型操作流 + 实时 Token/缓存命中面板）。
 *
 * 设计亮点：
 * 1. 默认采用清爽的“淡蓝加纯白”极简配色，支持 3 大色系 × 亮/暗 共 6 套无版权风险主题。
 * 2. 树型结构操作轨迹（Execution Tree `╭─ / │ / ├─ / ╰─`）：
 *    清晰展示模型执行的每一步思考（Thought）、工具调用（Tool Call）、执行耗时、输入/输出 Token 消耗量与缓存命中率（Cache Hit %）。
 * 3. 状态栏实时显示：当前供应商/协议/模型、内置 Git 分支状态、SAFE/YOLO 模式、累计 Token 与缓存命中率、当前会话 ID。
 */
public class TuiRenderer {

    private TuiTheme theme;
    private int terminalWidth = 96;

    public TuiRenderer(TuiTheme theme) {
        this.theme = theme != null ? theme : TuiTheme.get("sky-dark");
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
                if (parsed >= 68 && parsed <= 200) {
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
     * 清屏并渲染简约清爽的顶部主控面板（淡蓝 + 纯白极简风格）。
     */
    public void printWelcomeBanner(ConfigManager config, String githubUser, String gitSummary,
                                   int skillCount, int mcpCount, String sessionId) {
        clearScreen();
        System.out.println();
        // 标题栏宽度动态计算，避免版本号/产品名变化后右边框错位
        final int bannerWidth = Math.max(72, Math.min(terminalWidth - 2, 92));
        String label = "  ╭─── ✦ " + AppInfo.APP_NAME.toUpperCase(Locale.ROOT)
                + " · MINIMALIST TUI WORKBENCH ✦";
        String titleBar = label + "─".repeat(Math.max(2, bannerWidth - label.length() - 1)) + "╮";
        System.out.println(TuiTheme.BOLD + theme.gradientText(titleBar));

        String subContent = theme.text + TuiTheme.BOLD + "Built-in Pure-Java Git" + TuiTheme.RESET
                + theme.muted + "  •  " + theme.secondary + "Multi-Protocol LLM" + TuiTheme.RESET
                + theme.muted + "  •  " + theme.info + "Skills & MCP Ready" + TuiTheme.RESET
                + theme.muted + "  •  " + theme.accent + "Tree Step Trace" + TuiTheme.RESET;
        int subPad = Math.max(0, bannerWidth - 4 - visibleLength(subContent));
        System.out.println(theme.primary + "  │ " + subContent + " ".repeat(subPad) + theme.primary + "│" + TuiTheme.RESET);
        System.out.println(theme.primary + "  ╰" + "─".repeat(bannerWidth - 4) + "╯" + TuiTheme.RESET);

        String userDisplay = (githubUser == null || githubUser.isBlank())
                ? theme.muted + "Local Mode (未绑定远端 Token)" + TuiTheme.RESET
                : theme.success + "@" + githubUser + TuiTheme.RESET;
        String modeBadge = switch (config.getPermissionMode()) {
            case YOLO -> theme.warning + TuiTheme.BOLD + "⚡ YOLO (全自动免确认)" + TuiTheme.RESET;
            case STRICT -> theme.danger + TuiTheme.BOLD + "🔒 STRICT (每次调用都确认)" + TuiTheme.RESET;
            case SAFE -> theme.primary + TuiTheme.BOLD + "🛡️ SAFE (修改前展示Diff并询问)" + TuiTheme.RESET;
        };

        List<String> infoLines = new ArrayList<>();
        infoLines.add(theme.primary + "供应商/协议 : " + TuiTheme.RESET + theme.text + TuiTheme.BOLD + config.getProviderId().toUpperCase() + TuiTheme.RESET
                + theme.muted + " [" + config.getProtocol() + "]  •  " + TuiTheme.RESET
                + theme.primary + "模型: " + TuiTheme.RESET + theme.accent + config.getOpenaiModel() + TuiTheme.RESET);
        infoLines.add(theme.primary + "GitHub 账号 : " + TuiTheme.RESET + userDisplay
                + theme.muted + "  •  " + TuiTheme.RESET
                + theme.primary + "内置 Git: " + TuiTheme.RESET + theme.info + gitSummary + TuiTheme.RESET
                + theme.muted + "  •  " + TuiTheme.RESET
                + theme.primary + "工作区: " + TuiTheme.RESET + theme.text + config.getWorkspaceDir() + TuiTheme.RESET);
        infoLines.add(theme.primary + "扩展与会话  : " + TuiTheme.RESET + theme.text + "Skills(" + skillCount + ")  MCP(" + mcpCount + ")" + TuiTheme.RESET
                + theme.muted + "  •  " + TuiTheme.RESET
                + theme.primary + "会话ID: " + TuiTheme.RESET + theme.secondary + sessionId + TuiTheme.RESET
                + theme.muted + "  •  " + TuiTheme.RESET
                + theme.primary + "主题: " + TuiTheme.RESET + theme.text + theme.displayName + TuiTheme.RESET);
        infoLines.add(theme.primary + "执行策略    : " + TuiTheme.RESET + modeBadge);
        infoLines.add(theme.muted + "快捷提示    : 输入 " + theme.primary + TuiTheme.BOLD + "/" + TuiTheme.RESET + theme.muted + " 打开交互命令菜单 │ "
                + theme.accent + "/resume" + theme.muted + " 恢复历史对话 │ "
                + theme.accent + "/theme" + theme.muted + " 换主题 │ "
                + theme.accent + "/provider" + theme.muted + " 换供应商 │ "
                + theme.accent + "/yolo" + theme.muted + " 切换模式" + TuiTheme.RESET);

        printBox("✦ 工作台概览 (OVERVIEW)", infoLines, theme.primary);
    }

    /**
     * 渲染每次输入前的极简双层状态栏（含实时 Token 消耗与缓存命中率）。
     */
    public void printStatusBar(ConfigManager config, String gitBranchInfo, int turnCount, AIClient aiClient, String sessionId) {
        String modeTag = switch (config.getPermissionMode()) {
            case YOLO -> theme.warning + TuiTheme.BOLD + "⚡YOLO" + TuiTheme.RESET;
            case STRICT -> theme.danger + TuiTheme.BOLD + "🔒STRICT" + TuiTheme.RESET;
            case SAFE -> theme.primary + "🛡️SAFE" + TuiTheme.RESET;
        };

        int totalTok = aiClient.getTotalEstimatedTokens();
        int cacheTok = aiClient.getCumulativeCacheHitTokens();
        double hitRate = aiClient.getCumulativeCacheHitRate();

        String bar = theme.muted + "╭─[" + TuiTheme.RESET
                + theme.primary + TuiTheme.BOLD + config.getProviderId() + ":" + config.getOpenaiModel() + TuiTheme.RESET
                + theme.muted + "]─[" + TuiTheme.RESET
                + theme.info + "🌿 " + gitBranchInfo + TuiTheme.RESET
                + theme.muted + "]─[" + TuiTheme.RESET
                + modeTag
                + theme.muted + "]─[" + TuiTheme.RESET
                + theme.text + String.format("Tok: %,d (⚡Cache: %,d / %.0f%%)", totalTok, cacheTok, hitRate) + TuiTheme.RESET
                + theme.muted + "]─[" + TuiTheme.RESET
                + theme.secondary + sessionId + TuiTheme.RESET
                + theme.muted + "]" + TuiTheme.RESET;
        System.out.println(bar);
    }

    public void printPrompt(boolean yoloMode) {
        String icon = yoloMode ? theme.warning + "⚡" : theme.primary + "❯";
        System.out.print(theme.muted + "╰─" + icon + TuiTheme.BOLD + " " + TuiTheme.RESET);
        System.out.flush();
    }

    // =========================================================================
    // 树型结构执行轨迹渲染器 (TREE-STRUCTURED EXECUTION TRACE)
    // =========================================================================

    public void printTreeTurnStart(int turnIndex, String sessionId, String userTask) {
        String shortTask = userTask.length() > 64 ? userTask.substring(0, 61) + "..." : userTask;
        System.out.println();
        System.out.println(theme.primary + TuiTheme.BOLD + "╭─ 🌳 Execution Tree · Turn #" + turnIndex
                + TuiTheme.RESET + theme.muted + " [" + sessionId + "] ── " + theme.text + shortTask + TuiTheme.RESET);
    }

    public void printTreeStepHeader(int stepIndex, int maxSteps, AIClient.StepUsage usage) {
        System.out.println(theme.primary + "│" + TuiTheme.RESET);
        System.out.println(theme.primary + "├─ 🧠 " + TuiTheme.BOLD + "Step " + stepIndex + "/" + maxSteps + " · Model Reasoning"
                + TuiTheme.RESET + theme.muted + " [" + usage.formatCompact() + "]" + TuiTheme.RESET);
    }

    public void printTreeThought(String thought, boolean hasToolsBelow) {
        if (thought == null || thought.isBlank() || thought.equals("Synthesizing direct response")) {
            return;
        }
        String branch = hasToolsBelow ? "├─" : "╰─";
        System.out.println(theme.primary + "│  " + theme.muted + branch + " 💭 "
                + TuiTheme.ITALIC + theme.secondary + thought + TuiTheme.RESET);
    }

    public void printTreeToolStart(int toolIdx, int totalTools, String toolName, String argsSummary, PermissionGate.RiskLevel risk) {
        String riskBadge = switch (risk) {
            case READ_ONLY -> theme.info + "[READ]" + TuiTheme.RESET;
            case MUTATING -> theme.warning + "[WRITE]" + TuiTheme.RESET;
            case DESTRUCTIVE -> theme.danger + TuiTheme.BOLD + "[HIGH-RISK]" + TuiTheme.RESET;
        };
        System.out.println(theme.primary + "│  ├─ 🔧 " + TuiTheme.BOLD + "Tool [" + toolIdx + "/" + totalTools + "] "
                + theme.accent + toolName + TuiTheme.RESET + " " + riskBadge
                + (argsSummary.isEmpty() ? "" : theme.muted + " ── " + theme.text + argsSummary + TuiTheme.RESET));
    }

    public void printTreeToolResult(int toolIdx, int totalTools, String toolName, boolean ok, String output, long elapsedMs) {
        String connector = (toolIdx == totalTools) ? "   " : "│  ";
        String statusIcon = ok ? theme.success + "✔ OK" : theme.danger + "✖ FAILED";
        String firstLineSummary = summarizeOutputLines(output);

        System.out.println(theme.primary + "│  " + connector + theme.muted + "╰─ " + statusIcon
                + theme.muted + " (" + elapsedMs + "ms) ── " + theme.text + firstLineSummary + TuiTheme.RESET);

        if (output != null && !output.isBlank()) {
            String[] lines = output.split("\r?\n");
            int showCount = Math.min(lines.length, 8);
            for (int i = 0; i < showCount; i++) {
                String line = lines[i];
                if (line.length() > 110) {
                    line = line.substring(0, 107) + "...";
                }
                System.out.println(theme.primary + "│  " + connector + "   "
                        + theme.muted + "│ " + TuiTheme.RESET + colorizeDiffOrLogLine(line));
            }
            if (lines.length > showCount) {
                System.out.println(theme.primary + "│  " + connector + "   "
                        + theme.muted + "╰─ ... (+" + (lines.length - showCount) + " more lines)" + TuiTheme.RESET);
            }
        }
    }

    public void printTreeTurnFooter(int stepsUsed, AIClient aiClient, String sessionId) {
        System.out.println(theme.primary + "│" + TuiTheme.RESET);
        System.out.printf("%s╰─ 🏁 %sTurn Complete%s %s(%d steps │ Session Tok: in=%,d, out=%,d, ⚡cache=%,d [%.1f%%] │ 💾 Saved: %s)%s%n",
                theme.primary,
                theme.success + TuiTheme.BOLD, TuiTheme.RESET,
                theme.muted,
                stepsUsed,
                aiClient.getCumulativeInputTokens(),
                aiClient.getCumulativeOutputTokens(),
                aiClient.getCumulativeCacheHitTokens(),
                aiClient.getCumulativeCacheHitRate(),
                sessionId,
                TuiTheme.RESET);
    }

    private String summarizeOutputLines(String output) {
        if (output == null || output.isBlank()) {
            return "(empty output)";
        }
        String[] lines = output.split("\r?\n");
        String first = lines[0].trim();
        if (first.length() > 68) {
            first = first.substring(0, 65) + "...";
        }
        return lines.length == 1 ? first : first + " (" + lines.length + " lines)";
    }

    /**
     * 绘制带标题的极简圆角框线面板。
     */
    public void printBox(String title, List<String> lines, String borderColor) {
        int innerWidth = Math.max(68, terminalWidth - 4);
        String topTitle = " " + title + " ";
        int titleVisLen = visibleLength(topTitle);
        int rightDashes = Math.max(2, innerWidth - titleVisLen - 2);

        System.out.println(borderColor + "╭──" + TuiTheme.BOLD + topTitle + TuiTheme.RESET
                + borderColor + "─".repeat(rightDashes) + "╮" + TuiTheme.RESET);

        for (String rawLine : lines) {
            int vis = visibleLength(rawLine);
            int pad = Math.max(0, innerWidth - 2 - vis);
            System.out.println(borderColor + "│ " + TuiTheme.RESET + rawLine + " ".repeat(pad) + borderColor + " │" + TuiTheme.RESET);
        }

        System.out.println(borderColor + "╰" + "─".repeat(innerWidth) + "╯" + TuiTheme.RESET);
    }

    /**
     * 渲染文件修改前后的彩色 Unified Diff 对比面板。
     */
    public void printDiffPreview(String filePath, String oldContent, String newContent) {
        List<String> diffLines = computeSimpleDiff(oldContent, newContent, 40);
        List<String> styled = new ArrayList<>();
        for (String line : diffLines) {
            styled.add(colorizeDiffOrLogLine(line));
        }
        printBox("📝 变更预览 (DIFF PREVIEW): " + filePath, styled, theme.primary);
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

    public static List<String> computeSimpleDiff(String oldText, String newText, int maxOutputLines) {
        List<String> out = new ArrayList<>();
        String[] oldLines = (oldText == null || oldText.isEmpty()) ? new String[0] : oldText.split("\r?\n", -1);
        String[] newLines = (newText == null || newText.isEmpty()) ? new String[0] : newText.split("\r?\n", -1);

        if (oldLines.length == 0) {
            out.add("@@ New File (" + newLines.length + " lines) @@");
            for (int i = 0; i < Math.min(newLines.length, maxOutputLines); i++) {
                out.add(String.format("+ %4d │ %s", i + 1, newLines[i]));
            }
            if (newLines.length > maxOutputLines) {
                out.add("... (+" + (newLines.length - maxOutputLines) + " more lines) ...");
            }
            return out;
        }

        int i = 0, j = 0, shown = 0;
        while ((i < oldLines.length || j < newLines.length) && shown < maxOutputLines) {
            if (i < oldLines.length && j < newLines.length && oldLines[i].equals(newLines[j])) {
                i++;
                j++;
            } else {
                out.add(String.format("@@ -%d +%d @@", Math.max(1, i), j + 1));
                shown++;
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
            out.add("  (No changes)");
        }
        return out;
    }

    /**
     * 将 AI 的 Markdown 回答渲染为极简淡蓝+纯白风格的富文本面板。
     */
    public void renderMarkdownResponse(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return;
        }
        System.out.println();
        System.out.println(theme.primary + "╭── " + TuiTheme.BOLD + "✦ Agent Response" + TuiTheme.RESET
                + theme.primary + " " + "─".repeat(Math.max(10, terminalWidth - 22)) + TuiTheme.RESET);

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
                        theme.primary, theme.muted, codeLineNum++, theme.secondary, line, TuiTheme.RESET);
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
                System.out.println(theme.primary + "│ " + theme.primary + "  • " + TuiTheme.RESET + formatInlineMarkdown(trimmed.substring(2)));
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
        String formatted = text.replaceAll("`([^`]+)`", theme.primary + TuiTheme.BOLD + "$1" + TuiTheme.RESET + theme.text);
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
                System.out.printf("\r  %s│  %s %s%s %s(%.1fs)%s  ",
                        theme.primary,
                        theme.primary + TuiTheme.BOLD + frame,
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
                System.out.print("\r" + " ".repeat(90) + "\r");
                System.out.flush();
            }
        }
    }

    public static int visibleLength(String s) {
        if (s == null) return 0;
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
}
