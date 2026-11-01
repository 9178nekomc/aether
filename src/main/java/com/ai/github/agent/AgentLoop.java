package com.ai.github.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 核心 ReAct 多步自主推理与工具调度主循环（AgentLoop v2.0）。
 *
 * 对标 Claude Code / OpenAI Codex CLI 架构：
 * 1. 支持多步自主工具调用链（Explore -> Read -> Plan -> Edit with Diff Preview -> Built-in Git -> GitHub API）。
 * 2. 集成 PermissionGate：在 SAFE 模式下对任何写入/修改操作展示彩色 Diff 预览并询问用户确认；在 ⚡ YOLO 模式下全自动连续执行。
 * 3. 内置丰富的斜杠命令系统（/help, /yolo, /theme, /model, /git, /tree, /status, /config, /prompt, /memory, /compact, /clear, /doctor）。
 */
public class AgentLoop {

    private final ConfigManager config;
    private final AIClient aiClient;
    private final GitHubApiClient githubClient;
    private final GitOperations gitOps;
    private final GitHubActions actions;
    private final FileSystemTools fsTools;
    private final TuiRenderer tui;
    private final PermissionGate permissionGate;
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<AIClient.ChatMessage> history = new ArrayList<>();
    private String githubLogin;
    private int turnCount = 0;

    public AgentLoop(ConfigManager config,
                     AIClient aiClient,
                     GitHubApiClient githubClient,
                     GitOperations gitOps,
                     GitHubActions actions,
                     FileSystemTools fsTools,
                     TuiRenderer tui,
                     PermissionGate permissionGate,
                     String githubLogin) {
        this.config = config;
        this.aiClient = aiClient;
        this.githubClient = githubClient;
        this.gitOps = gitOps;
        this.actions = actions;
        this.fsTools = fsTools;
        this.tui = tui;
        this.permissionGate = permissionGate;
        this.githubLogin = githubLogin;
    }

    public int getTurnCount() {
        return turnCount;
    }

    /**
     * 处理用户输入（自动识别 `/` 斜杠控制台命令、直接 `git ...` 内置命令或自然语言 Agent 任务）。
     */
    public void handle(String userInput) {
        String trimmed = userInput.trim();
        if (trimmed.startsWith("/")) {
            handleSlashCommand(trimmed);
            return;
        }
        // 允许用户在输入框直接敲 `git status` / `git log --oneline` 立即走内置 JGit 引擎快速响应
        if (trimmed.equals("git") || trimmed.startsWith("git ")) {
            executeDirectBuiltinGit(trimmed);
            return;
        }
        if (trimmed.equals("帮助") || trimmed.equalsIgnoreCase("help")) {
            printHelp();
            return;
        }

        runReActAgentTurn(trimmed);
    }

    /**
     * 执行完整的多步 ReAct 推理与工具调用循环。
     */
    private void runReActAgentTurn(String userInput) {
        turnCount++;
        history.add(new AIClient.ChatMessage("user", userInput));

        int maxSteps = Math.max(1, config.getMaxAgentSteps());
        TuiTheme theme = tui.getTheme();

        for (int step = 1; step <= maxSteps; step++) {
            String systemPrompt = SystemPromptBuilder.buildSystemPrompt(config, gitOps, githubLogin);

            JsonNode responseJson;
            TuiRenderer.Spinner spinner = tui.startSpinner("Agent 正在思考与规划 (Step " + step + "/" + maxSteps + ")...");
            try {
                responseJson = aiClient.chatReActStep(systemPrompt, history, config.getTemperature());
            } catch (Exception e) {
                spinner.stop();
                tui.printError("AI 推理请求失败: " + e.getMessage());
                return;
            } finally {
                spinner.stop();
            }

            String thought = responseJson.path("thought").asText("");
            if (!thought.isBlank() && !thought.equals("Direct response from model")) {
                System.out.println(theme.muted + "  💭 " + TuiTheme.ITALIC + thought + TuiTheme.RESET);
            }

            JsonNode toolCalls = responseJson.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty()) {
                // 记录模型的工具调用决策到上下文
                history.add(new AIClient.ChatMessage("assistant", responseJson.toString()));

                ArrayNode toolResultsArray = mapper.createArrayNode();
                for (JsonNode call : toolCalls) {
                    String toolName = call.path("tool").asText("");
                    JsonNode args = call.path("args");
                    String summary = summarizeToolArgs(toolName, args);
                    PermissionGate.RiskLevel risk = permissionGate.classifyToolRisk(toolName, summary);

                    tui.printToolCallBanner(step, toolName, summary, risk);

                    // 如果是文件写或编辑操作，在询问用户前先渲染彩色 Unified Diff 对比预览！
                    renderPreApprovalDiffIfNeeded(toolName, args);

                    PermissionGate.Decision decision = permissionGate.checkPermission(toolName, summary, risk);
                    ObjectNode resultEntry = mapper.createObjectNode();
                    resultEntry.put("tool", toolName);

                    if (!decision.approved) {
                        String rejectMsg = "REJECTED_BY_USER: " + decision.userFeedback;
                        resultEntry.put("status", "rejected");
                        resultEntry.put("output", rejectMsg);
                        toolResultsArray.add(resultEntry);
                        tui.printToolResult(toolName, false, rejectMsg, 0);
                        continue;
                    }

                    long t0 = System.currentTimeMillis();
                    try {
                        String output = executeTool(toolName, args);
                        long elapsed = System.currentTimeMillis() - t0;
                        resultEntry.put("status", "success");
                        resultEntry.put("output", truncateForContext(output, 12000));
                        tui.printToolResult(toolName, true, output, elapsed);
                    } catch (Exception ex) {
                        long elapsed = System.currentTimeMillis() - t0;
                        String err = "ERROR: " + ex.getMessage();
                        resultEntry.put("status", "error");
                        resultEntry.put("output", err);
                        tui.printToolResult(toolName, false, err, elapsed);
                    }
                    toolResultsArray.add(resultEntry);
                }

                // 将工具执行观测结果（Observation）回传给下一轮 ReAct 推理
                history.add(new AIClient.ChatMessage("user",
                        "TOOL_EXECUTION_RESULTS:\n" + toolResultsArray.toPrettyString()
                                + "\n请根据上述工具执行结果继续下一步操作；若任务已完成，请返回包含 `final_answer` 的 JSON。"));
                continue;
            }

            // 如果没有 tool_calls，提取 final_answer 展示给用户并结束本轮
            String finalAnswer = responseJson.path("final_answer").asText("");
            if (finalAnswer.isBlank()) {
                finalAnswer = responseJson.toPrettyString();
            }
            history.add(new AIClient.ChatMessage("assistant", finalAnswer));
            tui.renderMarkdownResponse(finalAnswer);
            trimHistoryIfTooLong();
            return;
        }

        tui.printWarning("已达到单次任务最大推理步数 (" + maxSteps + " 步)。可通过 `/config set max_steps 40` 调高上限，或输入“继续”让 Agent 接着执行。");
    }

    /**
     * 在执行文件修改前向用户展示可视化彩色 Unified Diff 面板。
     */
    private void renderPreApprovalDiffIfNeeded(String toolName, JsonNode args) {
        try {
            if ("write_file".equals(toolName)) {
                String path = args.path("path").asText("");
                String newContent = args.path("content").asText("");
                String oldContent = fsTools.readRawContentIfExists(path);
                tui.printDiffPreview(path, oldContent, newContent);
            } else if ("edit_file".equals(toolName)) {
                String path = args.path("path").asText("");
                String oldText = args.path("old_text").asText("");
                String newText = args.path("new_text").asText("");
                boolean replaceAll = args.path("replace_all").asBoolean(false);
                String oldContent = fsTools.readRawContentIfExists(path);
                String previewNew = fsTools.previewEditedContent(path, oldText, newText, replaceAll);
                tui.printDiffPreview(path, oldContent, previewNew);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 路由并执行全部 30 个内置工具。
     */
    private String executeTool(String toolName, JsonNode args) throws Exception {
        return switch (toolName) {
            // --- A. 本地文件系统与代码编辑工具 ---
            case "list_dir" -> fsTools.listDirectoryTree(
                    args.path("path").asText("."),
                    args.path("max_depth").asInt(3));

            case "read_file" -> fsTools.readFile(
                    args.path("path").asText(""),
                    args.path("start_line").asInt(1),
                    args.path("end_line").asInt(300));

            case "search_files" -> fsTools.searchFiles(
                    args.path("path").asText("."),
                    args.path("query").asText(""),
                    args.path("glob").asText(""));

            case "write_file" -> fsTools.writeFile(
                    args.path("path").asText(""),
                    args.path("content").asText(""));

            case "edit_file" -> fsTools.editFile(
                    args.path("path").asText(""),
                    args.path("old_text").asText(""),
                    args.path("new_text").asText(""),
                    args.path("replace_all").asBoolean(false));

            case "delete_file" -> fsTools.deleteFile(args.path("path").asText(""));

            // --- B. 内置纯 Java JGit 引擎工具（零外部 Git 依赖） ---
            case "git_command" -> {
                String cmd = args.path("command").asText("status");
                String cwdStr = args.path("cwd").asText(".");
                File dir = fsTools.resolvePath(cwdStr).toFile();
                yield gitOps.executeGitCommand(dir, cmd, githubLogin, githubClient.getTokenForGitOverHttp());
            }

            // --- C. GitHub 官方 REST API 工具 ---
            case "gh_create_project_and_publish" -> actions.createProjectAndPublish(args.path("task").asText(""));

            case "gh_create_repo" -> {
                JsonNode res = githubClient.createRepository(
                        args.path("name").asText(),
                        args.path("description").asText(""),
                        args.path("private").asBoolean(false));
                yield "仓库创建成功: " + res.path("html_url").asText() + " (clone_url: " + res.path("clone_url").asText() + ")";
            }

            case "gh_delete_repo" -> {
                String owner = resolveOwner(args.path("owner").asText(""));
                String repo = args.path("repo").asText("");
                boolean ok = githubClient.deleteRepository(owner, repo);
                yield ok ? "已删除仓库: " + owner + "/" + repo : "删除仓库失败: " + owner + "/" + repo;
            }

            case "gh_fork_repo" -> {
                JsonNode res = githubClient.forkRepository(args.path("owner").asText(), args.path("repo").asText());
                yield "已发起 Fork: " + res.path("html_url").asText();
            }

            case "gh_list_user_repos" -> actions.listUserReposFormatted(
                    args.path("username").asText(""),
                    args.path("limit").asInt(15));

            case "gh_search_repos" -> actions.searchRepositoriesFormatted(
                    args.path("query").asText(""),
                    args.path("limit").asInt(10));

            case "gh_browse_trending" -> actions.browseAndDiscover(args.path("language").asText(""));

            case "gh_get_readme" -> githubClient.getReadmeText(
                    resolveOwner(args.path("owner").asText("")),
                    args.path("repo").asText(""));

            case "gh_get_remote_file" -> githubClient.getRemoteFileContent(
                    resolveOwner(args.path("owner").asText("")),
                    args.path("repo").asText(""),
                    args.path("path").asText(""),
                    args.path("ref").asText(""));

            case "gh_put_remote_file" -> {
                JsonNode res = githubClient.createOrUpdateFile(
                        resolveOwner(args.path("owner").asText("")),
                        args.path("repo").asText(""),
                        args.path("path").asText(""),
                        args.path("message").asText("Update file via GitHub Agent"),
                        args.path("content").asText(""),
                        args.path("branch").asText("main"),
                        args.path("sha").asText(""));
                yield "远端文件已更新: " + res.path("content").path("html_url").asText();
            }

            case "gh_search_code" -> {
                JsonNode res = githubClient.searchCode(args.path("query").asText(""), args.path("limit").asInt(10));
                JsonNode items = res.path("items");
                if (!items.isArray() || items.isEmpty()) {
                    yield "未搜索到匹配代码。";
                }
                List<String> lines = new ArrayList<>();
                for (JsonNode item : items) {
                    lines.add(String.format("- [%s] %s (%s)",
                            item.path("repository").path("full_name").asText(),
                            item.path("path").asText(),
                            item.path("html_url").asText()));
                }
                yield String.join("\n", lines);
            }

            case "gh_list_issues" -> actions.listIssuesFormatted(
                    resolveOwner(args.path("owner").asText("")),
                    args.path("repo").asText(""),
                    args.path("state").asText("open"),
                    args.path("limit").asInt(10));

            case "gh_create_issue" -> {
                JsonNode res = githubClient.createIssue(
                        resolveOwner(args.path("owner").asText("")),
                        args.path("repo").asText(""),
                        args.path("title").asText(""),
                        args.path("body").asText(""),
                        null);
                yield "Issue 已创建 (#" + res.path("number").asInt() + "): " + res.path("html_url").asText();
            }

            case "gh_comment_issue" -> {
                JsonNode res = githubClient.createIssueComment(
                        resolveOwner(args.path("owner").asText("")),
                        args.path("repo").asText(""),
                        args.path("number").asInt(1),
                        args.path("body").asText(""));
                yield "评论已发布: " + res.path("html_url").asText();
            }

            case "gh_list_prs" -> actions.listPullRequestsFormatted(
                    resolveOwner(args.path("owner").asText("")),
                    args.path("repo").asText(""),
                    args.path("state").asText("open"),
                    args.path("limit").asInt(10));

            case "gh_create_pr" -> {
                JsonNode res = githubClient.createPullRequest(
                        resolveOwner(args.path("owner").asText("")),
                        args.path("repo").asText(""),
                        args.path("title").asText(""),
                        args.path("body").asText(""),
                        args.path("head").asText(""),
                        args.path("base").asText("main"));
                yield "Pull Request 已创建 (#" + res.path("number").asInt() + "): " + res.path("html_url").asText();
            }

            case "gh_create_release" -> {
                JsonNode res = githubClient.createRelease(
                        resolveOwner(args.path("owner").asText("")),
                        args.path("repo").asText(""),
                        args.path("tag").asText("v1.0.0"),
                        args.path("name").asText(""),
                        args.path("body").asText(""),
                        args.path("prerelease").asBoolean(false));
                yield "Release 已发布: " + res.path("html_url").asText();
            }

            case "gh_star_repo" -> {
                String owner = args.path("owner").asText("");
                String repo = args.path("repo").asText("");
                String query = args.path("query").asText("");
                if (!owner.isBlank() && !repo.isBlank()) {
                    yield actions.starDirect(owner, repo);
                }
                yield actions.starProjectByQuery(query);
            }

            case "gh_unstar_repo" -> actions.unstarDirect(args.path("owner").asText(""), args.path("repo").asText(""));

            case "gh_follow_user" -> actions.followUserByName(args.path("username").asText(""));

            case "gh_unfollow_user" -> actions.unfollowUserByName(args.path("username").asText(""));

            case "gh_user_profile" -> actions.formatUserProfile(args.path("username").asText(githubLogin));

            // --- D. 本地构建与命令执行工具 ---
            case "run_shell" -> runLocalShellCommand(
                    args.path("command").asText(""),
                    args.path("cwd").asText("."));

            default -> "未知工具名称: `" + toolName + "`。请检查工具列表并重试。";
        };
    }

    private String runLocalShellCommand(String command, String cwdStr) throws Exception {
        if (command == null || command.isBlank()) {
            return "命令为空";
        }
        // 如果模型不小心用 run_shell 调了 git，自动转发给内置纯 Java JGit 引擎！
        String trimmed = command.trim();
        if (trimmed.equals("git") || trimmed.startsWith("git ")) {
            File dir = fsTools.resolvePath(cwdStr).toFile();
            return "[自动路由至内置 JGit 引擎]\n" + gitOps.executeGitCommand(dir, trimmed, githubLogin, githubClient.getTokenForGitOverHttp());
        }

        File workDir = fsTools.resolvePath(cwdStr).toFile();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb = isWindows
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        pb.directory(workDir);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append("\n");
            }
        }
        boolean finished = process.waitFor(90, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            return "命令执行超时 (超过 90 秒): " + command + "\n已输出:\n" + out;
        }
        return "Exit Code: " + process.exitValue() + "\n" + out.toString().trim();
    }

    private String resolveOwner(String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate.trim();
        }
        return (githubLogin != null && !githubLogin.isBlank()) ? githubLogin : "";
    }

    private String summarizeToolArgs(String toolName, JsonNode args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        return switch (toolName) {
            case "read_file" -> String.format("path=%s (lines %d..%d)",
                    args.path("path").asText(), args.path("start_line").asInt(1), args.path("end_line").asInt(300));
            case "write_file" -> String.format("path=%s (%d chars)",
                    args.path("path").asText(), args.path("content").asText("").length());
            case "edit_file" -> String.format("path=%s (replace %d chars -> %d chars)",
                    args.path("path").asText(),
                    args.path("old_text").asText("").length(),
                    args.path("new_text").asText("").length());
            case "git_command" -> "git " + args.path("command").asText() + " (cwd=" + args.path("cwd").asText(".") + ")";
            case "run_shell" -> args.path("command").asText();
            default -> {
                String raw = args.toString();
                yield raw.length() > 110 ? raw.substring(0, 107) + "..." : raw;
            }
        };
    }

    private String truncateForContext(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n... [输出过长，已截断其余 " + (text.length() - maxChars) + " 字符]";
    }

    private void trimHistoryIfTooLong() {
        while (history.size() > 36) {
            history.remove(0);
        }
    }

    /**
     * 处理 TUI 斜杠快捷指令（/help, /yolo, /theme, /model, /git, /tree, /status, /config, /prompt, /memory, /compact, /clear, /doctor）。
     */
    private void handleSlashCommand(String rawCmd) {
        String[] parts = rawCmd.trim().split("\\s+", 3);
        String cmd = parts[0].toLowerCase();
        String arg1 = parts.length > 1 ? parts[1] : "";
        String arg2 = parts.length > 2 ? parts[2] : "";

        try {
            switch (cmd) {
                case "/help", "/?" -> printHelp();

                case "/yolo" -> {
                    boolean next;
                    if (arg1.equalsIgnoreCase("on") || arg1.equalsIgnoreCase("true")) {
                        next = true;
                    } else if (arg1.equalsIgnoreCase("off") || arg1.equalsIgnoreCase("false")) {
                        next = false;
                    } else {
                        next = !config.isYoloMode();
                    }
                    config.setYoloMode(next);
                    config.save();
                    new TrustStore().trust(Path.of(config.getWorkspaceDir()).toAbsolutePath().normalize(),
                            next ? TrustStore.PermissionMode.YOLO : TrustStore.PermissionMode.SAFE);
                    if (next) {
                        tui.printWarning("⚡ YOLO 模式已【开启】！Agent 将全自动连续执行文件修改、内置Git与GitHub操作（无需逐步确认）。");
                    } else {
                        tui.printSuccess("🛡️ 已切换回【SAFE 安全确认模式】！修改文件或执行 Git/GitHub 变更前将展示彩色 Diff 并询问确认。");
                    }
                }

                case "/theme" -> {
                    if (arg1.isBlank()) {
                        List<String> lines = new ArrayList<>();
                        for (TuiTheme t : TuiTheme.allThemes().values()) {
                            String active = t.name.equals(tui.getTheme().name) ? "  ◀ [当前启用]" : "";
                            lines.add(t.primary + "● " + TuiTheme.BOLD + String.format("%-12s", t.name)
                                    + TuiTheme.RESET + t.secondary + t.displayName + active + TuiTheme.RESET);
                        }
                        lines.add("");
                        lines.add(tui.getTheme().muted + "用法: 输入 `/theme <名称>` 立即切换（如 `/theme cyberpunk` 或 `/theme codex`）" + TuiTheme.RESET);
                        tui.printBox("🎨 可用 TUI 色彩主题列表 (AVAILABLE THEMES)", lines, tui.getTheme().primary);
                    } else {
                        TuiTheme selected = TuiTheme.get(arg1);
                        tui.setTheme(selected);
                        config.setTuiTheme(selected.name);
                        config.save();
                        tui.printSuccess("已切换 TUI 主题为: " + selected.displayName + " (" + selected.name + ")");
                    }
                }

                case "/model" -> {
                    if (arg1.isBlank()) {
                        tui.printInfo("当前 AI 模型: " + config.getOpenaiModel() + " (用法: `/model <新模型名>`，例如 `/model claude-3-7-sonnet` 或 `/model gpt-4o`)");
                    } else {
                        String newModel = (arg2.isBlank() ? arg1 : arg1 + " " + arg2).trim();
                        config.setOpenaiModel(newModel);
                        aiClient.setModel(newModel);
                        config.save();
                        tui.printSuccess("已切换 AI 模型为: " + newModel);
                    }
                }

                case "/git" -> {
                    String subGit = rawCmd.substring(4).trim();
                    executeDirectBuiltinGit(subGit.isEmpty() ? "status" : subGit);
                }

                case "/tree" -> {
                    String target = arg1.isBlank() ? "." : arg1;
                    String tree = fsTools.listDirectoryTree(target, 3);
                    tui.printBox("📂 工作区目录结构 (" + target + ")", List.of(tree.split("\r?\n")), tui.getTheme().info);
                }

                case "/status" -> {
                    File ws = new File(config.getWorkspaceDir()).getAbsoluteFile();
                    String gitStat = gitOps.executeGitCommand(ws, "status -s", githubLogin, githubClient.getTokenForGitOverHttp());
                    List<String> lines = List.of(
                            "AI 模型      : " + config.getOpenaiModel() + " (" + config.getOpenaiEndpoint() + ")",
                            "GitHub 账号  : " + (githubLogin == null || githubLogin.isBlank() ? "未连接" : "@" + githubLogin),
                            "权限模式     : " + config.getPermissionMode().id + "（" + config.getPermissionMode().description + "）",
                            "越界写入     : " + (config.isAllowOutsideWorkspace() ? "允许（--allow-outside）" : "已限制在工作区内"),
                            "工作区路径   : " + ws.getAbsolutePath(),
                            "配置文件     : " + config.getConfigPath(),
                            "对话轮数     : " + turnCount + " 轮 (历史消息 " + history.size() + " 条, 累计约 " + aiClient.getTotalEstimatedTokens() + " tokens)",
                            "内置Git状态  :\n" + gitStat
                    );
                    tui.printBox("📊 系统与工作区实时状态 (SYSTEM STATUS)", lines, tui.getTheme().primary);
                }

                case "/config" -> {
                    if (arg1.equalsIgnoreCase("set") && !arg2.isBlank()) {
                        String[] kv = arg2.split("\\s+", 2);
                        if (kv.length < 2) {
                            tui.printWarning("用法: `/config set <key> <value>`，例如 `/config set workspace my-code`");
                        } else if (config.setDynamicProperty(kv[0], kv[1])) {
                            aiClient.setEndpoint(config.getOpenaiEndpoint());
                            aiClient.setApiKey(config.getOpenaiKey());
                            aiClient.setModel(config.getOpenaiModel());
                            fsTools.setWorkspaceRoot(config.getWorkspaceDir());
                            actions.setWorkspaceDir(config.getWorkspaceDir());
                            tui.setTheme(TuiTheme.get(config.getTuiTheme()));
                            tui.printSuccess("已更新并持久化配置: " + kv[0] + " = " + kv[1]);
                        } else {
                            tui.printError("未知的配置项: " + kv[0] + " (支持: endpoint, key, model, temperature, max_steps, token, username, workspace, theme, yolo, auto_approve_read, prompt_file)");
                        }
                    } else {
                        List<String> cfgLines = List.of(
                                "openai.endpoint   = " + config.getOpenaiEndpoint(),
                                "openai.model      = " + config.getOpenaiModel(),
                                "openai.temperature= " + config.getTemperature(),
                                "agent.max_steps   = " + config.getMaxAgentSteps(),
                                "github.username   = " + config.getGithubUsername(),
                                "workspace.dir     = " + config.getWorkspaceDir(),
                                "tui.theme         = " + config.getTuiTheme(),
                                "agent.yolo        = " + config.isYoloMode(),
                                "custom_prompt     = " + config.getCustomPromptFile(),
                                "",
                                "💡 修改配置用法: `/config set <key> <value>` (例如 `/config set max_steps 30`)"
                        );
                        tui.printBox("⚙️ 当前配置项 (CONFIGURATION)", cfgLines, tui.getTheme().secondary);
                    }
                }

                case "/prompt" -> {
                    String sysPrompt = SystemPromptBuilder.buildSystemPrompt(config, gitOps, githubLogin);
                    tui.printBox("📜 当前生效的 Claude Code / Codex 级 System Prompt",
                            List.of(sysPrompt.split("\r?\n")), tui.getTheme().secondary);
                }

                case "/memory" -> {
                    Path agentMd = Path.of(config.getWorkspaceDir()).resolve(config.getCustomPromptFile());
                    if (arg1.equalsIgnoreCase("init")) {
                        String template = """
                                # Project Custom Instructions (AGENT.md)
                                - 代码风格：保持简洁、模块化、完善的异常处理与中文注释。
                                - Git 规范：使用 Conventional Commits (feat:, fix:, refactor:, docs:, test:)。
                                - 构建与验证：每次修改关键逻辑后请检查语法完整性。
                                """;
                        Files.writeString(agentMd, template, StandardCharsets.UTF_8);
                        tui.printSuccess("已初始化项目级自定义提示词文件: " + agentMd.toAbsolutePath());
                    } else {
                        String existing = config.loadCustomProjectInstructions();
                        if (existing.isBlank()) {
                            tui.printInfo("当前未检测到 `" + config.getCustomPromptFile() + "`。输入 `/memory init` 可一键生成项目自定义规范模板！");
                        } else {
                            tui.printBox("🧠 已加载的项目自定义提示词 (" + config.getCustomPromptFile() + ")",
                                    List.of(existing.split("\r?\n")), tui.getTheme().info);
                        }
                    }
                }

                case "/compact" -> {
                    int before = history.size();
                    if (before <= 4) {
                        tui.printInfo("当前上下文消息较少 (" + before + " 条)，无需压缩。");
                    } else {
                        List<AIClient.ChatMessage> kept = new ArrayList<>(history.subList(before - 4, before));
                        history.clear();
                        history.addAll(kept);
                        tui.printSuccess("已压缩早期对话历史（保留最近 4 条核心上下文，释放 Token 空间）。");
                    }
                }

                case "/clear" -> {
                    history.clear();
                    permissionGate.clearSessionAllowlist();
                    tui.clearScreen();
                    File ws = new File(config.getWorkspaceDir()).getAbsoluteFile();
                    tui.printWelcomeBanner(config, githubLogin, gitOps.getQuickStatusBadge(ws));
                    tui.printSuccess("已清空对话上下文与会话临时白名单。");
                }

                case "/doctor" -> Doctor.print(tui, config, githubLogin);

                case "/mode" -> {
                    if (arg1.isBlank()) {
                        List<String> lines = new ArrayList<>();
                        for (TrustStore.PermissionMode m : TrustStore.PermissionMode.values()) {
                            String mark = m == config.getPermissionMode() ? "  ◀ [当前生效]" : "";
                            lines.add(tui.getTheme().primary + "● " + TuiTheme.BOLD + String.format("%-7s", m.id)
                                    + TuiTheme.RESET + tui.getTheme().text + m.description + mark + TuiTheme.RESET);
                        }
                        lines.add("");
                        lines.add(tui.getTheme().muted + "用法: `/mode safe|strict|yolo`（会同时更新当前目录的信任记录）" + TuiTheme.RESET);
                        tui.printBox("🛡️ 权限模式 (PERMISSION MODE)", lines, tui.getTheme().primary);
                    } else {
                        TrustStore.PermissionMode target = switch (arg1.toLowerCase()) {
                            case "safe", "1" -> TrustStore.PermissionMode.SAFE;
                            case "strict", "readonly", "2" -> TrustStore.PermissionMode.STRICT;
                            case "yolo", "3" -> TrustStore.PermissionMode.YOLO;
                            default -> null;
                        };
                        if (target == null) {
                            tui.printWarning("未知模式: " + arg1 + "（可选 safe / strict / yolo）");
                        } else {
                            config.applyPermissionMode(target);
                            config.save();
                            new TrustStore().trust(Path.of(config.getWorkspaceDir()).toAbsolutePath().normalize(), target);
                            if (target == TrustStore.PermissionMode.YOLO) {
                                tui.printWarning("⚡ 已切换为 YOLO：后续文件写入、Git 提交与 GitHub 操作将直接执行，不再逐次询问。");
                            } else {
                                tui.printSuccess("已切换权限模式为 " + target.id + "（" + target.description + "）");
                            }
                        }
                    }
                }

                case "/trust" -> {
                    Path ws = Path.of(config.getWorkspaceDir()).toAbsolutePath().normalize();
                    TrustStore store = new TrustStore();
                    TrustStore.Entry entry = store.entryFor(ws);
                    if (arg1.equalsIgnoreCase("outside")) {
                        boolean next = arg2.equalsIgnoreCase("on") || arg2.equalsIgnoreCase("true") || arg2.isEmpty()
                                ? !config.isAllowOutsideWorkspace()
                                : false;
                        config.setAllowOutsideWorkspace(next);
                        config.save();
                        fsTools.setAllowOutside(next);
                        if (next) {
                            tui.printWarning("已允许读写工作区之外的路径，请确认你清楚 Agent 正在访问什么。");
                        } else {
                            tui.printSuccess("已恢复工作区边界：工作区之外的写入将被拒绝。");
                        }
                    } else if (arg1.equalsIgnoreCase("revoke")) {
                        boolean removed = store.revoke(ws);
                        tui.printWarning(removed
                                ? "已撤销该目录的信任记录，下次启动会重新询问是否允许访问。"
                                : "该目录本来就没有信任记录。");
                    } else {
                        List<String> lines = new ArrayList<>();
                        lines.add("工作区目录   : " + ws);
                        lines.add("信任状态     : " + (entry == null ? "未授权" : "已授权（" + entry.trustedAt + "）"));
                        lines.add("权限模式     : " + config.getPermissionMode().id + "（" + config.getPermissionMode().description + "）");
                        lines.add("越界写入     : " + (config.isAllowOutsideWorkspace() ? "允许" : "已限制在工作区内"));
                        lines.add("信任记录文件 : " + store.getFile());
                        List<TrustStore.Entry> all = store.listTrusted();
                        lines.add("");
                        lines.add("已授权目录共 " + all.size() + " 个" + (all.isEmpty() ? "" : "："));
                        int shown = 0;
                        for (TrustStore.Entry e : all) {
                            if (shown++ >= 5) {
                                lines.add("  … 其余 " + (all.size() - 5) + " 个（见记录文件）");
                                break;
                            }
                            lines.add("  • " + e.dir + "  [" + e.mode + "]");
                        }
                        lines.add("");
                        lines.add("用法: `/trust outside on|off` 切换越界写入 | `/trust revoke` 撤销本目录授权");
                        tui.printBox("🔐 工作区信任与权限 (WORKSPACE TRUST)", lines, tui.getTheme().info);
                    }
                }

                case "/about", "/version" -> {
                    List<String> lines = new ArrayList<>();
                    lines.add(AppInfo.fullTitle());
                    lines.add("启动命令 : " + AppInfo.COMMAND + " [目录]   （短别名: " + AppInfo.SHORT_COMMAND + "）");
                    lines.add("配置目录 : " + AppInfo.homeDir());
                    lines.add("配置文件 : " + config.getConfigPath());
                    lines.add("信任记录 : " + AppInfo.trustFile());
                    lines.add("内置能力 : 30 项工具 / 纯 Java JGit / GitHub REST API / 多步 ReAct");
                    tui.printBox("ℹ️ 关于 (ABOUT)", lines, tui.getTheme().secondary);
                }

                default -> tui.printWarning("未知斜杠指令: `" + cmd + "`。输入 `/help` 查看所有支持的指令。");
            }
        } catch (Exception e) {
            tui.printError("指令执行出错: " + e.getMessage());
        }
    }

    private void executeDirectBuiltinGit(String gitCmd) {
        try {
            File ws = new File(config.getWorkspaceDir()).getAbsoluteFile();
            String output = gitOps.executeGitCommand(ws, gitCmd, githubLogin, githubClient.getTokenForGitOverHttp());
            tui.printBox("🌿 内置 JGit 执行结果: git " + gitCmd.replaceFirst("^git\\s+", ""),
                    List.of(output.split("\r?\n")), tui.getTheme().info);
        } catch (Exception e) {
            tui.printError("内置 Git 执行失败: " + e.getMessage());
        }
    }

    public void printHelp() {
        TuiTheme th = tui.getTheme();
        List<String> lines = List.of(
                th.secondary + TuiTheme.BOLD + "【一、自然语言全能 Agent（支持多步读写文件 + 内置Git + GitHub API）】" + TuiTheme.RESET,
                "  • " + th.accent + "读取与分析代码" + th.text + " : \"帮我看看当前工作区有哪些文件，读取 Main.java 并分析架构\"",
                "  • " + th.accent + "精准编辑与重构" + th.text + " : \"给项目的 UserService.java 增加参数校验，然后用 git diff 给我看改动\"",
                "  • " + th.accent + "内置 Git 操作 " + th.text + " : \"检查当前 git status，把修改提交为 'feat: add validation' 并查看 git log\"",
                "  • " + th.accent + "全栈生成并发布" + th.text + " : \"帮我创建一个带 JWT 登录的 Spring Boot 项目并推送到我的 GitHub\"",
                "  • " + th.accent + "GitHub 远端协作" + th.text + " : \"帮我搜索高星 Java 爬虫并 Star\" / \"给某仓库提个 Issue 或 PR\" / \"关注 torvalds\"",
                "",
                th.secondary + TuiTheme.BOLD + "【二、TUI 快捷斜杠命令面板（Slash Commands）】" + TuiTheme.RESET,
                "  • " + th.accent + "/mode [safe|strict|yolo]" + th.text + " : 切换权限模式（写操作是否逐次确认 / 只读是否也确认 / 全自动）",
                "  • " + th.accent + "/yolo [on|off]" + th.text + " : 一键开关 ⚡ YOLO 全自动免确认模式（等价于 /mode yolo）",
                "  • " + th.accent + "/trust         " + th.text + " : 查看本目录信任状态、已授权目录，或 `/trust outside on` 放行越界写入",
                "  • " + th.accent + "/theme <name> " + th.text + " : 实时切换 TUI 配色主题（支持: claude, cyberpunk, codex, dracula, nord）",
                "  • " + th.accent + "/git <subcmd> " + th.text + " : 直接调用内置纯 Java JGit 引擎（如 `/git status`, `/git log --oneline`, `/git diff`）",
                "  • " + th.accent + "/tree [dir]   " + th.text + " : 可视化树形展示工作区目录与文件大小",
                "  • " + th.accent + "/model <name> " + th.text + " : 实时切换底层大模型（如 `/model claude-3-7-sonnet` 或 `/model gpt-4o`）",
                "  • " + th.accent + "/config       " + th.text + " : 查看或热更新配置项（如 `/config set max_steps 30`）",
                "  • " + th.accent + "/memory [init]" + th.text + " : 查看或生成项目级 `AGENT.md` 自定义规范指令（对标 CLAUDE.md）",
                "  • " + th.accent + "/prompt       " + th.text + " : 查看当前完整的 Claude Code / Codex 级 System Prompt",
                "  • " + th.accent + "/status       " + th.text + " : 查看当前模型、内置 Git 分支、权限模式与 Token 统计",
                "  • " + th.accent + "/about        " + th.text + " : 查看版本号、启动命令与配置文件位置",
                "  • " + th.accent + "/compact      " + th.text + " : 压缩长对话历史上下文，节省 Token",
                "  • " + th.accent + "/clear        " + th.text + " : 清屏并重置当前对话会话",
                "  • " + th.accent + "/doctor       " + th.text + " : 运行内置环境与 JGit / API 连通性自检",
                "  • " + th.accent + "exit / 退出   " + th.text + " : 安全退出程序"
        );
        tui.printBox("🧭 " + AppInfo.fullTitle() + " 指令与能力全景指南", lines, th.primary);
    }
}
