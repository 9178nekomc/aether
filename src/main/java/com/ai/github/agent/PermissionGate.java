package com.ai.github.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

/**
 * 安全权限门控与 YOLO 模式控制器（Human-in-the-Loop Approval Gate）。
 *
 * 支持三级操作风险分类：
 * 1. READ_ONLY   - 只读操作（如 read_file, list_dir, search_files, git status/diff/log, GitHub 搜索/查看）：
 *                  默认自动放行（也可通过配置设为每次询问）。
 * 2. MUTATING    - 写入/变更操作（如 write_file, edit_file, git add/commit/checkout/push, 创建仓库/Issue/PR/Star）：
 *                  非 YOLO 模式下会弹出彩色预览卡片（含文件 Diff 对比），询问用户是否允许执行。
 * 3. DESTRUCTIVE - 高危/破坏性操作（如 delete_file, gh_delete_repo, git reset --hard, run_shell）：
 *                  使用红色高亮警告面板提示风险。
 *
 * 用户在确认提示中可选择：
 *   [y / Enter] 允许本次操作
 *   [n]         拒绝执行，并可输入反馈意见让 Agent 调整方案
 *   [a]         本次会话内自动允许该工具（Always allow this tool in session）
 *   [!]         立即开启 ⚡ YOLO 模式（后续所有操作全自动免确认执行）
 */
public class PermissionGate {

    public enum RiskLevel {
        READ_ONLY,
        MUTATING,
        DESTRUCTIVE
    }

    public static class Decision {
        public final boolean approved;
        public final String userFeedback;

        public Decision(boolean approved, String userFeedback) {
            this.approved = approved;
            this.userFeedback = userFeedback;
        }

        public static Decision allow() {
            return new Decision(true, "");
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason == null || reason.isBlank() ? "用户拒绝了该操作。" : reason);
        }
    }

    private final ConfigManager config;
    private final TuiRenderer tui;
    private final Scanner scanner;
    private final Set<String> sessionAllowedTools = new HashSet<>();

    public PermissionGate(ConfigManager config, TuiRenderer tui, Scanner scanner) {
        this.config = config;
        this.tui = tui;
        this.scanner = scanner;
    }

    public RiskLevel classifyToolRisk(String toolName, String rawArgsSummary) {
        if (toolName == null) {
            return RiskLevel.MUTATING;
        }
        return switch (toolName) {
            case "read_file", "list_dir", "search_files",
                 "gh_search_repos", "gh_browse_trending", "gh_get_repo",
                 "gh_get_readme", "gh_get_file", "gh_list_user_repos",
                 "gh_list_issues", "gh_list_prs", "gh_search_code", "gh_user_profile" -> RiskLevel.READ_ONLY;
            case "git_command" -> classifyGitSubcommandRisk(rawArgsSummary);
            case "delete_file", "gh_delete_repo", "run_shell" -> RiskLevel.DESTRUCTIVE;
            default -> RiskLevel.MUTATING;
        };
    }

    private RiskLevel classifyGitSubcommandRisk(String rawGitCmd) {
        if (rawGitCmd == null) {
            return RiskLevel.MUTATING;
        }
        String lower = rawGitCmd.trim().toLowerCase();
        if (lower.startsWith("git ")) {
            lower = lower.substring(4).trim();
        }
        if (lower.startsWith("status") || lower.startsWith("diff") || lower.startsWith("log")
                || lower.startsWith("show") || lower.startsWith("branch") && (lower.equals("branch") || lower.contains("-a") || lower.contains("-l"))
                || lower.startsWith("remote -v") || lower.equals("remote") || lower.startsWith("tag -l") || lower.equals("tag")) {
            return RiskLevel.READ_ONLY;
        }
        if (lower.contains("--hard") || lower.contains("--force") || lower.contains(" -f") || lower.startsWith("rm ")) {
            return RiskLevel.DESTRUCTIVE;
        }
        return RiskLevel.MUTATING;
    }

    /**
     * 检查并询问用户是否批准执行指定工具调用。
     */
    public Decision checkPermission(String toolName, String actionDescription, RiskLevel risk) {
        TuiTheme theme = tui.getTheme();

        // 1. 只读操作且开启了自动放行只读工具
        if (risk == RiskLevel.READ_ONLY && config.isAutoApproveRead()) {
            return Decision.allow();
        }

        // 2. YOLO 模式开启时：除了极端破坏性黑名单外全部自动放行
        if (config.isYoloMode()) {
            if (isCatastrophicCommand(toolName, actionDescription) && !config.isAllowCatastrophicInYolo()) {
                tui.printWarning("⚡ YOLO 模式安全护栏触发：检测到高危破坏性指令，仍需人工确认！");
            } else {
                System.out.println(theme.warning + "  ⚡ [YOLO 自动批准] " + TuiTheme.RESET
                        + theme.accent + toolName + TuiTheme.RESET + theme.muted + " -> " + actionDescription + TuiTheme.RESET);
                return Decision.allow();
            }
        }

        // 3. 当前会话已添加到白名单的工具
        if (sessionAllowedTools.contains(toolName) && risk != RiskLevel.DESTRUCTIVE) {
            System.out.println(theme.info + "  ✔ [会话白名单自动批准] " + TuiTheme.RESET + theme.accent + toolName + TuiTheme.RESET);
            return Decision.allow();
        }

        // 4. 弹出交互式 TUI 审批面板
        List<String> lines = new ArrayList<>();
        String levelBadge = switch (risk) {
            case READ_ONLY -> theme.info + "🟢 只读查询 (READ_ONLY)" + TuiTheme.RESET;
            case MUTATING -> theme.warning + "🟡 状态变更/写入 (MUTATING)" + TuiTheme.RESET;
            case DESTRUCTIVE -> theme.danger + TuiTheme.BOLD + "🔴 高危/破坏性操作 (DESTRUCTIVE)" + TuiTheme.RESET;
        };

        lines.add(theme.accent + "请求工具 : " + TuiTheme.RESET + TuiTheme.BOLD + toolName + TuiTheme.RESET + "   (" + levelBadge + ")");
        lines.add(theme.accent + "操作详情 : " + TuiTheme.RESET + theme.text + actionDescription + TuiTheme.RESET);
        lines.add("");
        lines.add(theme.success + "[Y/回车]" + theme.text + " 允许执行   "
                + theme.danger + "[N]" + theme.text + " 拒绝并指导AI   "
                + theme.info + "[A]" + theme.text + " 本次会话始终允许此工具   "
                + theme.warning + "[!]" + theme.text + " 开启⚡YOLO模式" + TuiTheme.RESET);

        String borderColor = (risk == RiskLevel.DESTRUCTIVE) ? theme.danger : theme.warning;
        tui.printBox("🛡️ 操作执行前安全确认 (HUMAN-IN-THE-LOOP APPROVAL)", lines, borderColor);

        System.out.print(borderColor + "  ❓ 请选择 [Y/n/a/!]: " + TuiTheme.RESET);
        System.out.flush();

        if (!scanner.hasNextLine()) {
            return Decision.deny("终端输入流已关闭");
        }
        String answer = scanner.nextLine().trim();

        if (answer.isEmpty() || answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes") || answer.equals("允许")) {
            tui.printSuccess("已批准执行 " + toolName);
            return Decision.allow();
        }

        if (answer.equals("!")) {
            config.setYoloMode(true);
            tui.printWarning("⚡ 已开启 YOLO 模式！后续所有文件读写、Git提交与GitHub操作将全自动执行（输入 /yolo 可随时关闭）。");
            return Decision.allow();
        }

        if (answer.equalsIgnoreCase("a") || answer.equalsIgnoreCase("always")) {
            sessionAllowedTools.add(toolName);
            tui.printInfo("已将工具 `" + toolName + "` 加入本次会话白名单。");
            return Decision.allow();
        }

        // 用户拒绝执行，允许补充拒绝原因或修改建议
        System.out.print(theme.muted + "  💬 (可选) 告诉 AI 拒绝原因或替代方案 (直接回车跳过): " + TuiTheme.RESET);
        System.out.flush();
        String feedback = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        String reason = feedback.isEmpty()
                ? "用户拒绝了工具 `" + toolName + "` 的执行请求。"
                : "用户拒绝了该操作，并给出指示: " + feedback;
        tui.printWarning("已取消执行 " + toolName);
        return Decision.deny(reason);
    }

    private boolean isCatastrophicCommand(String toolName, String desc) {
        if ("gh_delete_repo".equals(toolName)) {
            return true;
        }
        if ("run_shell".equals(toolName) && desc != null) {
            String lower = desc.toLowerCase();
            return lower.contains("rm -rf /") || lower.contains("mkfs") || lower.contains("format ") || lower.contains("shutdown");
        }
        return false;
    }

    public void clearSessionAllowlist() {
        sessionAllowedTools.clear();
    }
}
