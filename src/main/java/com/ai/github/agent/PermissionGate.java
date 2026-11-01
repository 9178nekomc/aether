package com.ai.github.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;

/**
 * 安全权限门控与 YOLO 模式控制器（PermissionGate v3.0）。
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
            return new Decision(false, reason == null || reason.isBlank() ? "User rejected this operation." : reason);
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
                 "list_skills", "load_skill", "mcp_list_tools",
                 "gh_search_repos", "gh_browse_trending", "gh_get_repo",
                 "gh_get_readme", "gh_get_remote_file", "gh_list_user_repos",
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
                || lower.startsWith("show") || (lower.startsWith("branch") && (lower.equals("branch") || lower.contains("-a") || lower.contains("-l")))
                || lower.startsWith("remote -v") || lower.equals("remote") || lower.startsWith("tag -l") || lower.equals("tag")) {
            return RiskLevel.READ_ONLY;
        }
        if (lower.contains("--hard") || lower.contains("--force") || lower.contains(" -f") || lower.startsWith("rm ")) {
            return RiskLevel.DESTRUCTIVE;
        }
        return RiskLevel.MUTATING;
    }

    public Decision checkPermission(String toolName, String actionDescription, RiskLevel risk) {
        TuiTheme theme = tui.getTheme();

        // STRICT 模式：连只读工具也逐次确认（对标启动器层的 strict 语义）
        boolean strict = config.getPermissionMode() == TrustStore.PermissionMode.STRICT;
        if (risk == RiskLevel.READ_ONLY && config.isAutoApproveRead() && !strict) {
            return Decision.allow();
        }

        if (config.isYoloMode()) {
            if (isCatastrophicCommand(toolName, actionDescription) && !config.isAllowCatastrophicInYolo()) {
                tui.printWarning("⚡ YOLO 安全护栏触发：检测到高危指令，需人工确认！");
            } else {
                System.out.println(theme.primary + "│     " + theme.warning + "⚡ [YOLO Auto-Approved] "
                        + TuiTheme.RESET + theme.accent + toolName + TuiTheme.RESET);
                return Decision.allow();
            }
        }

        if (sessionAllowedTools.contains(toolName) && risk != RiskLevel.DESTRUCTIVE) {
            System.out.println(theme.primary + "│     " + theme.info + "✔ [Session Allowlist] "
                    + TuiTheme.RESET + theme.accent + toolName + TuiTheme.RESET);
            return Decision.allow();
        }

        List<String> lines = new ArrayList<>();
        String levelBadge = switch (risk) {
            case READ_ONLY -> theme.info + "READ_ONLY" + TuiTheme.RESET;
            case MUTATING -> theme.warning + "WRITE / MUTATE" + TuiTheme.RESET;
            case DESTRUCTIVE -> theme.danger + TuiTheme.BOLD + "HIGH-RISK / DESTRUCTIVE" + TuiTheme.RESET;
        };

        lines.add(theme.primary + "请求工具 : " + TuiTheme.RESET + TuiTheme.BOLD + toolName + TuiTheme.RESET + "  [" + levelBadge + "]");
        lines.add(theme.primary + "操作摘要 : " + TuiTheme.RESET + theme.text + actionDescription + TuiTheme.RESET);
        lines.add("");
        lines.add(theme.success + "[Y/回车]" + theme.text + " 允许执行   "
                + theme.danger + "[N]" + theme.text + " 拒绝并指导AI   "
                + theme.info + "[A]" + theme.text + " 本次会话始终允许该工具   "
                + theme.warning + "[!]" + theme.text + " 开启⚡YOLO模式" + TuiTheme.RESET);

        String borderColor = (risk == RiskLevel.DESTRUCTIVE) ? theme.danger : theme.primary;
        tui.printBox("🛡️ 操作执行前确认 (APPROVAL REQUIRED)", lines, borderColor);

        System.out.print(borderColor + "  ❓ 请确认 [Y/n/a/!]: " + TuiTheme.RESET);
        System.out.flush();

        if (!scanner.hasNextLine()) {
            return Decision.deny("Input stream closed");
        }
        String answer = scanner.nextLine().trim();

        if (answer.isEmpty() || answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes") || answer.equals("允许")) {
            return Decision.allow();
        }

        if (answer.equals("!")) {
            config.setYoloMode(true);
            tui.printWarning("⚡ 已开启 YOLO 全自动模式！后续操作将免确认自动执行（输入 /yolo 可随时切换）。");
            return Decision.allow();
        }

        if (answer.equalsIgnoreCase("a") || answer.equalsIgnoreCase("always")) {
            sessionAllowedTools.add(toolName);
            tui.printInfo("已将 `" + toolName + "` 加入本次会话白名单。");
            return Decision.allow();
        }

        System.out.print(theme.muted + "  💬 (可选) 输入修改意见或拒绝原因 (直接回车跳过): " + TuiTheme.RESET);
        System.out.flush();
        String feedback = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        String reason = feedback.isEmpty()
                ? "User rejected execution of `" + toolName + "`."
                : "User rejected `" + toolName + "` with instructions: " + feedback;
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
