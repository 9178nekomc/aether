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
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * 核心 ReAct 树型执行主循环与交互式 `/` 命令中心（AgentLoop v3.0）。
 *
 * 核心升级：
 * 1. 树型操作轨迹（Tree View Execution Trace）：每一步推理与每一个工具调用均以清晰的树状结构展示，
 *    实时打印该步的输入 Token、缓存命中 Token 与命中率（Cache Hit %）、输出 Token 以及耗时。
 * 2. 自动保存与一键恢复（Session Persistence）：每步完成后自动持久化至 `.agent-sessions/`，支持 `/resume` 或 `/sessions` 恢复任意历史对话。
 * 3. 原生支持 Skills 技能包与 MCP (Model Context Protocol) 扩展服务。
 * 4. 输入 `/` 直接呼出清屏式交互命令面板（换主题、换供应商/协议/模型、查 Skill、查 MCP、恢复会话、退出等）。
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
    private final SkillManager skillManager;
    private final McpClientManager mcpManager;
    private final SessionManager sessionManager;
    private final Scanner scanner;
    private final ObjectMapper mapper = new ObjectMapper();

    private final List<AIClient.ChatMessage> history = new ArrayList<>();
    private String githubLogin;
    private int turnCount = 0;
    private boolean exitRequested = false;

    public AgentLoop(ConfigManager config,
                     AIClient aiClient,
                     GitHubApiClient githubClient,
                     GitOperations gitOps,
                     GitHubActions actions,
                     FileSystemTools fsTools,
                     TuiRenderer tui,
                     PermissionGate permissionGate,
                     SkillManager skillManager,
                     McpClientManager mcpManager,
                     SessionManager sessionManager,
                     Scanner scanner,
                     String githubLogin) {
        this.config = config;
        this.aiClient = aiClient;
        this.githubClient = githubClient;
        this.gitOps = gitOps;
        this.actions = actions;
        this.fsTools = fsTools;
        this.tui = tui;
        this.permissionGate = permissionGate;
        this.skillManager = skillManager;
        this.mcpManager = mcpManager;
        this.sessionManager = sessionManager;
        this.scanner = scanner;
        this.githubLogin = githubLogin;
    }

    public int getTurnCount() {
        return turnCount;
    }

    public boolean isExitRequested() {
        return exitRequested;
    }

    public void restoreSessionBySelector(String selector) {
        try {
            SessionManager.RestoredSession rs = sessionManager.loadSession(selector);
            this.history.clear();
            this.history.addAll(rs.messages);
            this.turnCount = rs.turnCount;
            this.aiClient.restoreTokenStats(rs.inputTokens, rs.outputTokens, rs.cacheHitTokens);
            tui.printSuccess("已恢复历史会话 [" + rs.sessionId + "] \"" + rs.title + "\" (共 " + rs.turnCount + " 轮, 历史消息 " + rs.messages.size() + " 条)");
            if (!rs.treeLogSummary.isEmpty()) {
                tui.printBox("🌳 已恢复会话的历史执行树摘要 (RESTORED EXECUTION LOG)", rs.treeLogSummary, tui.getTheme().info);
            }
        } catch (Exception e) {
            tui.printWarning("未能恢复会话 (" + e.getMessage() + ")，已开启新会话。");
        }
    }

    /**
     * 处理用户输入：支持单独输入 `/` 弹出清屏式交互命令中心、`/xxx` 斜杠命令、直接 `git ...` 内置命令或自然语言任务。
     */
    public void handle(String userInput) {
        String trimmed = userInput.trim();
        if (trimmed.equals("/")) {
            openInteractiveSlashMenu();
            return;
        }
        if (trimmed.startsWith("/")) {
            handleSlashCommand(trimmed);
            return;
        }
        if (trimmed.equals("git") || trimmed.startsWith("git ")) {
            executeDirectBuiltinGit(trimmed);
            return;
        }
        if (trimmed.equals("帮助") || trimmed.equalsIgnoreCase("help")) {
            openInteractiveSlashMenu();
            return;
        }

        runReActAgentTurn(trimmed);
    }

    /**
     * 执行多步 ReAct 推理并以树型结构（Tree View）展示每一步思考、Token/缓存消耗与工具执行结果，每步自动保存进度。
     */
    private void runReActAgentTurn(String userInput) {
        turnCount++;
        history.add(new AIClient.ChatMessage("user", userInput));

        int maxSteps = Math.max(1, config.getMaxAgentSteps());
        String sessId = sessionManager.getCurrentSessionId();

        tui.printTreeTurnStart(turnCount, sessId, userInput);
        sessionManager.recordTreeStep("Turn #" + turnCount + " User: " + (userInput.length() > 60 ? userInput.substring(0, 60) + "..." : userInput));

        for (int step = 1; step <= maxSteps; step++) {
            String systemPrompt = SystemPromptBuilder.buildSystemPrompt(
                    config, gitOps, githubLogin, skillManager, mcpManager
            );

            AIClient.ReActStepResult stepResult;
            TuiRenderer.Spinner spinner = tui.startSpinner("Step " + step + "/" + maxSteps + " · Waiting for " + config.getProviderId() + "/" + config.getOpenaiModel() + "...");
            try {
                stepResult = aiClient.chatReActStepWithUsage(systemPrompt, history, config.getTemperature());
            } catch (Exception e) {
                spinner.stop();
                tui.printError("模型推理调用失败: " + e.getMessage());
                sessionManager.saveProgress(config, aiClient, turnCount, history);
                return;
            } finally {
                spinner.stop();
            }

            JsonNode responseJson = stepResult.json;
            AIClient.StepUsage usage = stepResult.usage;

            // 1. 渲染树型步骤头（含精确 Token 消耗与缓存命中率）
            tui.printTreeStepHeader(step, maxSteps, usage);

            String thought = responseJson.path("thought").asText("");
            JsonNode toolCalls = responseJson.get("tool_calls");
            boolean hasTools = toolCalls != null && toolCalls.isArray() && !toolCalls.isEmpty();

            // 2. 渲染树型思考节点
            tui.printTreeThought(thought, hasTools);
            sessionManager.recordTreeStep(String.format("  ├─ Step %d [%s]: %s", step, usage.formatCompact(), thought));

            if (hasTools) {
                history.add(new AIClient.ChatMessage("assistant", responseJson.toString()));

                ArrayNode toolResultsArray = mapper.createArrayNode();
                int totalTools = toolCalls.size();
                int toolIdx = 0;

                for (JsonNode call : toolCalls) {
                    toolIdx++;
                    String toolName = call.path("tool").asText("");
                    JsonNode args = call.path("args");
                    String summary = summarizeToolArgs(toolName, args);
                    PermissionGate.RiskLevel risk = permissionGate.classifyToolRisk(toolName, summary);

                    // 3. 渲染树型工具节点起点
                    tui.printTreeToolStart(toolIdx, totalTools, toolName, summary, risk);

                    // 写文件或编辑文件前渲染彩色 Diff 预览
                    renderPreApprovalDiffIfNeeded(toolName, args);

                    PermissionGate.Decision decision = permissionGate.checkPermission(toolName, summary, risk);
                    ObjectNode resultEntry = mapper.createObjectNode();
                    resultEntry.put("tool", toolName);

                    if (!decision.approved) {
                        String rejectMsg = "REJECTED_BY_USER: " + decision.userFeedback;
                        resultEntry.put("status", "rejected");
                        resultEntry.put("output", rejectMsg);
                        toolResultsArray.add(resultEntry);
                        tui.printTreeToolResult(toolIdx, totalTools, toolName, false, rejectMsg, 0);
                        sessionManager.recordTreeStep("  │  ╰─ Tool " + toolName + " -> REJECTED");
                        continue;
                    }

                    long t0 = System.currentTimeMillis();
                    try {
                        String output = executeTool(toolName, args);
                        long elapsed = System.currentTimeMillis() - t0;
                        resultEntry.put("status", "success");
                        resultEntry.put("output", truncateForContext(output, 12000));
                        tui.printTreeToolResult(toolIdx, totalTools, toolName, true, output, elapsed);
                        sessionManager.recordTreeStep("  │  ╰─ Tool " + toolName + " (" + summary + ") -> OK in " + elapsed + "ms");
                    } catch (Exception ex) {
                        long elapsed = System.currentTimeMillis() - t0;
                        String err = "ERROR: " + ex.getMessage();
                        resultEntry.put("status", "error");
                        resultEntry.put("output", err);
                        tui.printTreeToolResult(toolIdx, totalTools, toolName, false, err, elapsed);
                        sessionManager.recordTreeStep("  │  ╰─ Tool " + toolName + " -> ERROR: " + ex.getMessage());
                    }
                    toolResultsArray.add(resultEntry);
                }

                history.add(new AIClient.ChatMessage("user",
                        "TOOL_EXECUTION_RESULTS:\n" + toolResultsArray.toPrettyString()
                                + "\nContinue next step or return `final_answer` JSON if complete."));

                // 每一步工具执行完立即保存会话进度
                sessionManager.saveProgress(config, aiClient, turnCount, history);
                continue;
            }

            // 4. 树型收尾 + 渲染最终回答 + 自动保存进度
            String finalAnswer = responseJson.path("final_answer").asText("");
            if (finalAnswer.isBlank()) {
                finalAnswer = responseJson.toPrettyString();
            }
            history.add(new AIClient.ChatMessage("assistant", finalAnswer));
            trimHistoryIfTooLong();
            sessionManager.saveProgress(config, aiClient, turnCount, history);

            tui.printTreeTurnFooter(step, aiClient, sessId);
            tui.renderMarkdownResponse(finalAnswer);
            return;
        }

        sessionManager.saveProgress(config, aiClient, turnCount, history);
        tui.printWarning("已达到单轮最大步数 (" + maxSteps + " 步)，进度已自动保存至 " + sessId + "。可输入“继续”接着执行。");
    }

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
     * 路由并执行全部 34 个工具（含文件、内置JGit、GitHub REST API、Skills、MCP、Shell）。
     */
    private String executeTool(String toolName, JsonNode args) throws Exception {
        return switch (toolName) {
            // --- A. 本地文件系统与代码编辑 ---
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

            // --- B. 内置纯 Java JGit 引擎（零外部 Git 依赖） ---
            case "git_command" -> {
                String cmd = args.path("command").asText("status");
                String cwdStr = args.path("cwd").asText(".");
                File dir = fsTools.resolvePath(cwdStr).toFile();
                yield gitOps.executeGitCommand(dir, cmd, githubLogin, githubClient.getTokenForGitOverHttp());
            }

            // --- C. Skills 技能包工具 ---
            case "list_skills" -> skillManager.buildCompactSkillIndexForPrompt();
            case "load_skill" -> skillManager.loadSkillContentForTool(args.path("name").asText(""));

            // --- D. MCP (Model Context Protocol) 工具 ---
            case "mcp_list_tools" -> mcpManager.listToolsFromServer(args.path("server").asText("all"));
            case "mcp_call_tool" -> mcpManager.callMcpTool(
                    args.path("server").asText(""),
                    args.path("tool").asText(""),
                    args.get("arguments"));

            // --- E. GitHub 官方 REST API 工具 ---
            case "gh_create_project_and_publish" -> actions.createProjectAndPublish(args.path("task").asText(""));

            case "gh_create_repo" -> {
                JsonNode res = githubClient.createRepository(
                        args.path("name").asText(),
                        args.path("description").asText(""),
                        args.path("private").asBoolean(false));
                yield "Created repository: " + res.path("html_url").asText() + " (clone_url: " + res.path("clone_url").asText() + ")";
            }

            case "gh_delete_repo" -> {
                String owner = resolveOwner(args.path("owner").asText(""));
                String repo = args.path("repo").asText("");
                boolean ok = githubClient.deleteRepository(owner, repo);
                yield ok ? "Deleted repository: " + owner + "/" + repo : "Failed to delete repository: " + owner + "/" + repo;
            }

            case "gh_fork_repo" -> {
                JsonNode res = githubClient.forkRepository(args.path("owner").asText(), args.path("repo").asText());
                yield "Forked repository: " + res.path("html_url").asText();
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
                yield "Updated remote file: " + res.path("content").path("html_url").asText();
            }

            case "gh_search_code" -> {
                JsonNode res = githubClient.searchCode(args.path("query").asText(""), args.path("limit").asInt(10));
                JsonNode items = res.path("items");
                if (!items.isArray() || items.isEmpty()) {
                    yield "No code matches found.";
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
                yield "Created Issue #" + res.path("number").asInt() + ": " + res.path("html_url").asText();
            }

            case "gh_comment_issue" -> {
                JsonNode res = githubClient.createIssueComment(
                        resolveOwner(args.path("owner").asText("")),
                        args.path("repo").asText(""),
                        args.path("number").asInt(1),
                        args.path("body").asText(""));
                yield "Added comment: " + res.path("html_url").asText();
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
                yield "Created PR #" + res.path("number").asInt() + ": " + res.path("html_url").asText();
            }

            case "gh_create_release" -> {
                JsonNode res = githubClient.createRelease(
                        resolveOwner(args.path("owner").asText("")),
                        args.path("repo").asText(""),
                        args.path("tag").asText("v1.0.0"),
                        args.path("name").asText(""),
                        args.path("body").asText(""),
                        args.path("prerelease").asBoolean(false));
                yield "Published Release: " + res.path("html_url").asText();
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

            // --- F. 本地 Shell 构建/测试工具 ---
            case "run_shell" -> runLocalShellCommand(
                    args.path("command").asText(""),
                    args.path("cwd").asText("."));

            default -> "Unknown tool: `" + toolName + "`";
        };
    }

    private String runLocalShellCommand(String command, String cwdStr) throws Exception {
        if (command == null || command.isBlank()) {
            return "Empty command";
        }
        String trimmed = command.trim();
        if (trimmed.equals("git") || trimmed.startsWith("git ")) {
            File dir = fsTools.resolvePath(cwdStr).toFile();
            return "[Routed to built-in JGit]\n" + gitOps.executeGitCommand(dir, trimmed, githubLogin, githubClient.getTokenForGitOverHttp());
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
            return "Timed out (>90s): " + command + "\nOutput:\n" + out;
        }
        return "Exit Code: " + process.exitValue() + "\n" + out.toString().trim();
    }

    // =========================================================================
    // 交互式 `/` 命令面板与斜杠命令路由器（每个引导面板均先清屏）
    // =========================================================================

    private void openInteractiveSlashMenu() {
        tui.clearScreen();
        TuiTheme th = tui.getTheme();
        List<String> menu = List.of(
                th.primary + TuiTheme.BOLD + "[1]  /theme" + TuiTheme.RESET + th.text + "     切换简约主题 (淡蓝纯白 / 暖橙琥珀 / 翡翠森绿 × 亮暗色)",
                th.primary + TuiTheme.BOLD + "[2]  /provider" + TuiTheme.RESET + th.text + "  切换 AI 模型供应商、通信协议与官方端点 (13家内置供应商)",
                th.primary + TuiTheme.BOLD + "[3]  /model" + TuiTheme.RESET + th.text + "     快速切换当前模型名称",
                th.primary + TuiTheme.BOLD + "[4]  /resume" + TuiTheme.RESET + th.text + "    浏览历史保存的对话进度并一键恢复 (/sessions)",
                th.primary + TuiTheme.BOLD + "[5]  /skills" + TuiTheme.RESET + th.text + "    查看、启用/禁用或新建本地 Skill 技能包",
                th.primary + TuiTheme.BOLD + "[6]  /mcp" + TuiTheme.RESET + th.text + "       管理与测试 MCP (Model Context Protocol) 工具服务",
                th.primary + TuiTheme.BOLD + "[7]  /yolo" + TuiTheme.RESET + th.text + "      切换 🛡️ SAFE 确认模式 与 ⚡ YOLO 全自动模式",
                th.primary + TuiTheme.BOLD + "[8]  /git" + TuiTheme.RESET + th.text + "       打开内置纯 Java JGit 快捷命令执行器",
                th.primary + TuiTheme.BOLD + "[9]  /tree" + TuiTheme.RESET + th.text + "      查看工作区目录树结构",
                th.primary + TuiTheme.BOLD + "[10] /status" + TuiTheme.RESET + th.text + "    查看系统状态、Token 消耗与 Prompt 缓存命中率统计",
                th.primary + TuiTheme.BOLD + "[11] /prompt" + TuiTheme.RESET + th.text + "    查看当前生效的精炼英文 System Prompt",
                th.primary + TuiTheme.BOLD + "[12] /clear" + TuiTheme.RESET + th.text + "     开启新会话并清屏",
                th.primary + TuiTheme.BOLD + "[13] /mode" + TuiTheme.RESET + th.text + "      切换权限模式 safe(默认) / strict(全确认) / yolo(全自动)",
                th.primary + TuiTheme.BOLD + "[14] /trust" + TuiTheme.RESET + th.text + "     查看工作区授权与越界写入策略 (/trust outside on|off)",
                th.primary + TuiTheme.BOLD + "[15] /doctor" + TuiTheme.RESET + th.text + "    运行环境自检（Java / 工作区 / 配置 / 信任 / API）",
                th.primary + TuiTheme.BOLD + "[16] /about" + TuiTheme.RESET + th.text + "     查看版本、安装目录与启动命令",
                th.danger + TuiTheme.BOLD + "[q]  /exit" + TuiTheme.RESET + th.text + "      保存当前会话进度并退出程序",
                th.muted + "[0/回车]        返回对话主界面" + TuiTheme.RESET
        );
        tui.printBox("✦ 交互式命令中心 (COMMAND PALETTE · 输入序号或斜杠命令)", menu, th.primary);
        System.out.print(th.primary + "  ❯ 请选择 [0-16 / q]: " + TuiTheme.RESET);
        System.out.flush();

        if (!scanner.hasNextLine()) return;
        String choice = scanner.nextLine().trim();
        switch (choice.toLowerCase()) {
            case "", "0" -> refreshDashboardHeader();
            case "1" -> handleSlashCommand("/theme");
            case "2" -> handleSlashCommand("/provider");
            case "3" -> handleSlashCommand("/model");
            case "4" -> handleSlashCommand("/resume");
            case "5" -> handleSlashCommand("/skills");
            case "6" -> handleSlashCommand("/mcp");
            case "7" -> handleSlashCommand("/yolo");
            case "8" -> {
                System.out.print(th.primary + "  ❯ 输入内置 Git 子命令 (如 `status`, `log --oneline`, `diff`): " + TuiTheme.RESET);
                String g = scanner.hasNextLine() ? scanner.nextLine().trim() : "status";
                executeDirectBuiltinGit(g.isEmpty() ? "status" : g);
            }
            case "9" -> handleSlashCommand("/tree");
            case "10" -> handleSlashCommand("/status");
            case "11" -> handleSlashCommand("/prompt");
            case "12" -> handleSlashCommand("/clear");
            case "13" -> handleSlashCommand("/mode");
            case "14" -> handleSlashCommand("/trust");
            case "15" -> handleSlashCommand("/doctor");
            case "16" -> handleSlashCommand("/about");
            case "q", "exit", "quit", "/exit", "/quit" -> handleSlashCommand("/exit");
            default -> {
                if (choice.startsWith("/")) {
                    handleSlashCommand(choice);
                } else {
                    handle(choice);
                }
            }
        }
    }

    private void handleSlashCommand(String rawCmd) {
        String[] parts = rawCmd.trim().split("\\s+", 3);
        String cmd = parts[0].toLowerCase();
        String arg1 = parts.length > 1 ? parts[1] : "";
        String arg2 = parts.length > 2 ? parts[2] : "";

        try {
            switch (cmd) {
                case "/help", "/?", "/menu" -> openInteractiveSlashMenu();

                case "/exit", "/quit" -> {
                    sessionManager.saveProgress(config, aiClient, turnCount, history);
                    tui.printSuccess("会话进度已保存至 `" + sessionManager.getCurrentSessionId() + "`。再见！");
                    this.exitRequested = true;
                }

                case "/yolo" -> {
                    boolean next = arg1.isBlank() ? !config.isYoloMode()
                            : (arg1.equalsIgnoreCase("on") || arg1.equalsIgnoreCase("true"));
                    config.applyPermissionMode(next
                            ? TrustStore.PermissionMode.YOLO
                            : TrustStore.PermissionMode.SAFE);
                    config.save();
                    if (next) {
                        tui.printWarning("⚡ 已切换为 YOLO 全自动免确认模式！");
                    } else {
                        tui.printSuccess("🛡️ 已切换回 SAFE 确认模式（修改前展示 Diff 并询问）。");
                    }
                }

                case "/mode", "/permission" -> {
                    if (arg1.isBlank()) {
                        List<String> lines = new ArrayList<>();
                        lines.add("当前模式: " + config.getPermissionMode().id + "（" + config.getPermissionMode().description + "）");
                        lines.add("");
                        lines.add("[1] safe   " + TrustStore.PermissionMode.SAFE.description);
                        lines.add("[2] strict " + TrustStore.PermissionMode.STRICT.description);
                        lines.add("[3] yolo   " + TrustStore.PermissionMode.YOLO.description);
                        lines.add("");
                        lines.add("用法: `/mode safe|strict|yolo` 直接切换。");
                        tui.printBox("🛡️ 权限模式 (PERMISSION MODE)", lines, tui.getTheme().primary);
                    } else {
                        TrustStore.PermissionMode target = TrustStore.PermissionMode.fromId(arg1);
                        config.applyPermissionMode(target);
                        config.save();
                        TrustStore store = new TrustStore();
                        store.trust(Path.of(config.getWorkspaceDir()), target);
                        if (target == TrustStore.PermissionMode.YOLO) {
                            tui.printWarning("⚡ 已切换为 YOLO 全自动模式：文件写入、Git 与 GitHub 操作将不再逐次确认。");
                        } else {
                            tui.printSuccess("已切换权限模式为 " + target.id + "（" + target.description + "）");
                        }
                    }
                }

                case "/trust" -> {
                    Path ws = Path.of(config.getWorkspaceDir()).toAbsolutePath().normalize();
                    TrustStore store = new TrustStore();
                    if (arg1.equalsIgnoreCase("outside")) {
                        boolean on = arg2.isBlank() ? !config.isAllowOutsideWorkspace()
                                : (arg2.equalsIgnoreCase("on") || arg2.equalsIgnoreCase("true"));
                        config.setAllowOutsideWorkspace(on);
                        config.save();
                        fsTools.setAllowOutside(on);
                        if (on) {
                            tui.printWarning("⚠ 已允许读写工作区之外的路径（越界写入不再被拒绝）。");
                        } else {
                            tui.printSuccess("✔ 已限制为仅工作区内可写。");
                        }
                    } else if (arg1.equalsIgnoreCase("revoke")) {
                        if (store.revoke(ws)) {
                            tui.printSuccess("已撤销该目录的信任记录，下次启动会重新询问授权。");
                        } else {
                            tui.printInfo("该目录本来就没有信任记录。");
                        }
                    } else {
                        TrustStore.Entry entry = store.entryFor(ws);
                        List<String> lines = new ArrayList<>();
                        lines.add("工作区目录   : " + ws);
                        lines.add("信任状态     : " + (entry == null ? "未授权" : "已授权（" + entry.trustedAt + "）"));
                        lines.add("权限模式     : " + config.getPermissionMode().id + "（" + config.getPermissionMode().description + "）");
                        lines.add("越界写入     : " + (config.isAllowOutsideWorkspace() ? "允许" : "已限制在工作区内"));
                        lines.add("信任记录文件 : " + store.getFile());
                        lines.add("");
                        List<TrustStore.Entry> all = store.listTrusted();
                        lines.add("已授权目录共 " + all.size() + " 个：");
                        for (TrustStore.Entry e : all) {
                            lines.add("  • " + e.dir + "  [" + e.mode + "]");
                        }
                        lines.add("");
                        lines.add("用法: `/trust outside on|off` 切换越界写入 | `/trust revoke` 撤销本目录授权");
                        tui.printBox("🔐 工作区信任与权限 (WORKSPACE TRUST)", lines, tui.getTheme().primary);
                    }
                }

                case "/doctor" -> Doctor.print(tui, config, githubLogin);

                case "/about" -> {
                    List<String> lines = new ArrayList<>();
                    lines.add(AppInfo.fullTitle());
                    lines.add("");
                    lines.add("安装目录 : " + AppInfo.homeDir());
                    lines.add("配置文件 : " + config.getConfigPath());
                    lines.add("信任记录 : " + AppInfo.trustFile());
                    lines.add("启动命令 : " + AppInfo.COMMAND + " / " + AppInfo.SHORT_COMMAND);
                    lines.add("供应商   : " + config.getProviderId() + "（协议 " + config.getProtocol() + "）");
                    lines.add("模型     : " + config.getOpenaiModel());
                    lines.add("");
                    lines.add("单命令启动、工作区信任授权与安装脚本属于 v2.1+ 启动器层，v3.0 沿用。");
                    tui.printBox("ℹ️ 关于 (ABOUT)", lines, tui.getTheme().secondary);
                }

                case "/theme" -> {
                    if (arg1.isBlank()) {
                        config.runWizardStep6Theme(scanner, 1, 1);
                        config.save();
                        tui.setTheme(TuiTheme.get(config.getTuiTheme()));
                        refreshDashboardHeader();
                        tui.printSuccess("已应用主题: " + tui.getTheme().displayName);
                    } else {
                        TuiTheme selected = TuiTheme.get(arg1);
                        tui.setTheme(selected);
                        config.setTuiTheme(selected.name);
                        config.save();
                        refreshDashboardHeader();
                        tui.printSuccess("已应用主题: " + selected.displayName);
                    }
                }

                case "/provider", "/setup" -> {
                    config.runModelAndProviderSetupOnly(scanner);
                    aiClient.setEndpoint(config.getOpenaiEndpoint());
                    aiClient.setApiKey(config.getOpenaiKey());
                    aiClient.setModel(config.getOpenaiModel());
                    aiClient.setProtocol(config.getProtocol());
                    refreshDashboardHeader();
                    tui.printSuccess("已更新供应商与模型配置: " + config.getProviderId() + " / " + config.getOpenaiModel() + " [" + config.getProtocol() + "]");
                }

                case "/model" -> {
                    if (arg1.isBlank()) {
                        ProviderRegistry.ProviderSpec spec = ProviderRegistry.get(config.getProviderId());
                        tui.clearScreen();
                        List<String> lines = new ArrayList<>();
                        lines.add("当前供应商: " + spec.displayName + " (" + config.getProtocol() + ")");
                        lines.add("当前模型  : " + config.getOpenaiModel());
                        lines.add("推荐模型  : " + String.join(", ", spec.recommendedModels));
                        tui.printBox("🤖 切换模型 (/model)", lines, tui.getTheme().primary);
                        System.out.print(tui.getTheme().primary + "  ❯ 输入新模型名 (直接回车取消): " + TuiTheme.RESET);
                        String m = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
                        if (!m.isEmpty()) {
                            config.setOpenaiModel(m);
                            aiClient.setModel(m);
                            config.save();
                            tui.printSuccess("模型已更新为: " + m);
                        }
                    } else {
                        String newModel = (arg2.isBlank() ? arg1 : arg1 + " " + arg2).trim();
                        config.setOpenaiModel(newModel);
                        aiClient.setModel(newModel);
                        config.save();
                        tui.printSuccess("模型已更新为: " + newModel);
                    }
                }

                case "/sessions", "/resume" -> runInteractiveSessionsAndResume(arg1);

                case "/skills", "/skill" -> runInteractiveSkillsMenu(arg1, arg2);

                case "/mcp" -> runInteractiveMcpMenu(arg1, arg2);

                case "/git" -> {
                    String subGit = rawCmd.substring(4).trim();
                    executeDirectBuiltinGit(subGit.isEmpty() ? "status" : subGit);
                }

                case "/tree" -> {
                    String target = arg1.isBlank() ? "." : arg1;
                    String tree = fsTools.listDirectoryTree(target, 3);
                    tui.printBox("📂 工作区目录树 (" + target + ")", List.of(tree.split("\r?\n")), tui.getTheme().info);
                }

                case "/status" -> {
                    tui.clearScreen();
                    File ws = new File(config.getWorkspaceDir()).getAbsoluteFile();
                    String gitStat = gitOps.executeGitCommand(ws, "status -s", githubLogin, githubClient.getTokenForGitOverHttp());
                    List<String> lines = List.of(
                            "当前会话 ID   : " + sessionManager.getCurrentSessionId() + " (" + sessionManager.getCurrentTitle() + ")",
                            "AI 供应商     : " + config.getProviderId() + " │ 协议: " + config.getProtocol() + " │ 端点: " + config.getOpenaiEndpoint(),
                            "AI 模型       : " + config.getOpenaiModel() + " (temperature=" + config.getTemperature() + ", max_steps=" + config.getMaxAgentSteps() + ")",
                            "Token 消耗统计: 输入=" + String.format("%,d", aiClient.getCumulativeInputTokens())
                                    + " │ 输出=" + String.format("%,d", aiClient.getCumulativeOutputTokens())
                                    + " │ 总计=" + String.format("%,d", aiClient.getTotalEstimatedTokens()),
                            "Prompt 缓存   : ⚡命中=" + String.format("%,d", aiClient.getCumulativeCacheHitTokens())
                                    + " tokens (命中率 " + String.format("%.1f%%", aiClient.getCumulativeCacheHitRate()) + ")",
                            "Skills & MCP  : 已加载 Skills=" + skillManager.getAllSkills().size() + " 个 │ MCP Servers=" + mcpManager.getServers().size() + " 个",
                            "内置 JGit 状态:\n" + gitStat
                    );
                    tui.printBox("📊 系统状态与 Token / 缓存命中统计 (TELEMETRY)", lines, tui.getTheme().primary);
                }

                case "/config" -> {
                    if (arg1.equalsIgnoreCase("set") && !arg2.isBlank()) {
                        String[] kv = arg2.split("\\s+", 2);
                        if (kv.length >= 2 && config.setDynamicProperty(kv[0], kv[1])) {
                            aiClient.setEndpoint(config.getOpenaiEndpoint());
                            aiClient.setApiKey(config.getOpenaiKey());
                            aiClient.setModel(config.getOpenaiModel());
                            aiClient.setProtocol(config.getProtocol());
                            fsTools.setWorkspaceRoot(config.getWorkspaceDir());
                            actions.setWorkspaceDir(config.getWorkspaceDir());
                            tui.setTheme(TuiTheme.get(config.getTuiTheme()));
                            tui.printSuccess("配置已更新: " + kv[0] + " = " + kv[1]);
                        } else {
                            tui.printWarning("用法: `/config set <provider|protocol|endpoint|key|model|theme|workspace|yolo|max_steps> <value>`");
                        }
                    } else {
                        tui.clearScreen();
                        List<String> cfgLines = List.of(
                                "ai.provider       = " + config.getProviderId(),
                                "ai.protocol       = " + config.getProtocol(),
                                "openai.endpoint   = " + config.getOpenaiEndpoint(),
                                "openai.model      = " + config.getOpenaiModel(),
                                "tui.theme         = " + config.getTuiTheme(),
                                "agent.yolo        = " + config.isYoloMode(),
                                "workspace.dir     = " + config.getWorkspaceDir(),
                                "",
                                "💡 输入 `/provider` 可启动清屏式向导重新配置供应商与模型，或用 `/config set <key> <val>` 修改。"
                        );
                        tui.printBox("⚙️ 当前配置项 (CONFIGURATION)", cfgLines, tui.getTheme().primary);
                    }
                }

                case "/prompt" -> {
                    tui.clearScreen();
                    String sysPrompt = SystemPromptBuilder.buildSystemPrompt(config, gitOps, githubLogin, skillManager, mcpManager);
                    tui.printBox("📜 当前生效的英文省 Token 版 System Prompt (估算 ~" + (sysPrompt.length() / 4) + " tokens)",
                            List.of(sysPrompt.split("\r?\n")), tui.getTheme().primary);
                }

                case "/memory" -> {
                    Path agentMd = Path.of(config.getWorkspaceDir()).resolve(config.getCustomPromptFile());
                    if (arg1.equalsIgnoreCase("init")) {
                        String template = """
                                # Project Custom Instructions (AGENT.md)
                                - Code style: Clean, modular, defensive error handling.
                                - Git commits: Follow Conventional Commits (feat:, fix:, refactor:, docs:, test:).
                                """;
                        Files.writeString(agentMd, template, StandardCharsets.UTF_8);
                        tui.printSuccess("已创建项目级指令文件: " + agentMd.toAbsolutePath());
                    } else {
                        String existing = config.loadCustomProjectInstructions();
                        if (existing.isBlank()) {
                            tui.printInfo("未检测到 `" + config.getCustomPromptFile() + "`。输入 `/memory init` 可一键生成。");
                        } else {
                            tui.printBox("🧠 项目自定义指令 (" + config.getCustomPromptFile() + ")",
                                    List.of(existing.split("\r?\n")), tui.getTheme().info);
                        }
                    }
                }

                case "/compact" -> {
                    int before = history.size();
                    if (before > 4) {
                        List<AIClient.ChatMessage> kept = new ArrayList<>(history.subList(before - 4, before));
                        history.clear();
                        history.addAll(kept);
                        sessionManager.saveProgress(config, aiClient, turnCount, history);
                        tui.printSuccess("已压缩历史对话，保留最近 4 条核心上下文。");
                    } else {
                        tui.printInfo("当前消息数较少，无需压缩。");
                    }
                }

                case "/clear", "/new" -> {
                    sessionManager.saveProgress(config, aiClient, turnCount, history);
                    history.clear();
                    turnCount = 0;
                    aiClient.resetTokenStats();
                    permissionGate.clearSessionAllowlist();
                    sessionManager.startNewSession();
                    refreshDashboardHeader();
                    tui.printSuccess("已开启全新会话: " + sessionManager.getCurrentSessionId());
                }

                default -> tui.printWarning("未知命令 `" + cmd + "`。直接输入 `/` 可打开交互式命令中心。");
            }
        } catch (Exception e) {
            tui.printError("命令执行异常: " + e.getMessage());
        }
    }

    private void runInteractiveSessionsAndResume(String argSelector) {
        if (!argSelector.isBlank()) {
            tui.clearScreen();
            refreshDashboardHeader();
            restoreSessionBySelector(argSelector);
            return;
        }
        tui.clearScreen();
        TuiTheme th = tui.getTheme();
        List<SessionManager.SessionSummary> list = sessionManager.listSavedSessions();
        if (list.isEmpty()) {
            tui.printInfo("暂无已保存的历史会话。每完成一步对话，进度都会自动保存至 `.agent-sessions/`。");
            return;
        }

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(list.size(), 15); i++) {
            SessionManager.SessionSummary s = list.get(i);
            int totalTok = s.inputTokens + s.outputTokens;
            double cachePct = s.inputTokens > 0 ? (s.cacheHitTokens * 100.0 / s.inputTokens) : 0.0;
            String curMark = s.sessionId.equals(sessionManager.getCurrentSessionId()) ? th.success + " ◀ [当前]" + TuiTheme.RESET : "";
            lines.add(String.format("%s[%2d]%s %s%-20s%s │ %s%-30s%s │ %d轮 │ %,d tok (⚡%.0f%%) │ %s%s",
                    th.primary + TuiTheme.BOLD, i + 1, TuiTheme.RESET,
                    th.secondary, s.sessionId, TuiTheme.RESET,
                    th.text, s.title, TuiTheme.RESET,
                    s.turnCount, totalTok, cachePct, s.updatedAt, curMark));
        }
        lines.add("");
        lines.add(th.muted + "提示: 输入序号 [1-" + Math.min(list.size(), 15) + "] 立即恢复对应会话，或直接回车返回" + TuiTheme.RESET);
        tui.printBox("💾 历史对话进度列表 (SAVED SESSIONS)", lines, th.primary);

        System.out.print(th.primary + "  ❯ 请输入要恢复的会话序号或 ID (回车取消): " + TuiTheme.RESET);
        String sel = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        if (!sel.isEmpty()) {
            tui.clearScreen();
            refreshDashboardHeader();
            restoreSessionBySelector(sel);
        }
    }

    private void runInteractiveSkillsMenu(String subAction, String arg) throws Exception {
        tui.clearScreen();
        TuiTheme th = tui.getTheme();
        skillManager.reloadSkills();

        if (subAction.equalsIgnoreCase("toggle") && !arg.isEmpty()) {
            if (skillManager.toggleSkill(arg)) {
                tui.printSuccess("已切换技能状态: " + arg);
            } else {
                tui.printWarning("未找到技能: " + arg);
            }
            return;
        }

        List<String> lines = new ArrayList<>();
        lines.add(th.muted + "技能目录: " + skillManager.getSkillsDir() + TuiTheme.RESET);
        lines.add("");
        int idx = 1;
        List<SkillManager.Skill> skillList = new ArrayList<>(skillManager.getAllSkills().values());
        for (SkillManager.Skill s : skillList) {
            String badge = s.enabled ? th.success + "[已启用]" : th.muted + "[已禁用]";
            lines.add(String.format("%s[%d]%s %s %s%-20s%s ── %s",
                    th.primary + TuiTheme.BOLD, idx++, TuiTheme.RESET,
                    badge + TuiTheme.RESET,
                    th.accent + TuiTheme.BOLD, s.name, TuiTheme.RESET,
                    th.text + s.description + TuiTheme.RESET));
        }
        lines.add("");
        lines.add(th.muted + "操作: 输入序号查看技能全文 │ 输入 `t <序号>` 启用/禁用 │ 输入 `new` 新建技能 │ 回车返回" + TuiTheme.RESET);
        tui.printBox("🧩 本地 Skills 技能包管理中心 (SKILL MANAGER)", lines, th.primary);

        System.out.print(th.primary + "  ❯ 请选择操作: " + TuiTheme.RESET);
        String in = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        if (in.isEmpty()) return;

        if (in.equalsIgnoreCase("new")) {
            System.out.print(th.primary + "  ❯ 新技能名称 (如 spring-test): " + TuiTheme.RESET);
            String name = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
            System.out.print(th.primary + "  ❯ 简短描述: " + TuiTheme.RESET);
            String desc = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
            System.out.print(th.primary + "  ❯ 技能详细指令内容: " + TuiTheme.RESET);
            String body = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
            if (!name.isEmpty()) {
                skillManager.createSkill(name, desc, body);
                tui.printSuccess("已创建并启用新技能: " + name);
            }
        } else if (in.toLowerCase().startsWith("t ")) {
            int num = Integer.parseInt(in.substring(2).trim());
            if (num >= 1 && num <= skillList.size()) {
                SkillManager.Skill s = skillList.get(num - 1);
                skillManager.toggleSkill(s.name);
                tui.printSuccess("已切换技能 `" + s.name + "` 状态为: " + (s.enabled ? "启用" : "禁用"));
            }
        } else {
            try {
                int num = Integer.parseInt(in);
                if (num >= 1 && num <= skillList.size()) {
                    SkillManager.Skill s = skillList.get(num - 1);
                    tui.printBox("📄 Skill 详情: " + s.name, List.of(s.content.split("\r?\n")), th.info);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void runInteractiveMcpMenu(String subAction, String arg) throws Exception {
        tui.clearScreen();
        TuiTheme th = tui.getTheme();
        mcpManager.loadOrInitConfig();

        List<String> lines = new ArrayList<>();
        lines.add(th.muted + "配置文件: " + mcpManager.getConfigPath() + TuiTheme.RESET);
        lines.add("");
        List<McpClientManager.McpServerConfig> srvList = new ArrayList<>(mcpManager.getServers().values());
        for (int i = 0; i < srvList.size(); i++) {
            McpClientManager.McpServerConfig s = srvList.get(i);
            String badge = s.enabled ? th.success + "[ENABLED]" : th.muted + "[DISABLED]";
            String target = "http".equalsIgnoreCase(s.transport) ? s.url : (s.command + " " + String.join(" ", s.args));
            lines.add(String.format("%s[%d]%s %s %s%-18s%s (%s) ── %s",
                    th.primary + TuiTheme.BOLD, i + 1, TuiTheme.RESET,
                    badge + TuiTheme.RESET,
                    th.accent + TuiTheme.BOLD, s.name, TuiTheme.RESET,
                    s.transport, th.text + target + TuiTheme.RESET));
        }
        lines.add("");
        lines.add(th.muted + "操作: 输入 `t <序号>` 启用/禁用 │ `test <序号>` 测试并列出远端工具 │ `add` 新增 MCP Server │ 回车返回" + TuiTheme.RESET);
        tui.printBox("🔌 MCP (Model Context Protocol) 服务管理中心", lines, th.primary);

        System.out.print(th.primary + "  ❯ 请选择操作: " + TuiTheme.RESET);
        String in = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        if (in.isEmpty()) return;

        if (in.equalsIgnoreCase("add")) {
            System.out.print(th.primary + "  ❯ MCP Server 名称: " + TuiTheme.RESET);
            String name = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
            System.out.print(th.primary + "  ❯ 类型 [1=stdio 本地命令, 2=http 远程端点]: " + TuiTheme.RESET);
            String type = scanner.hasNextLine() ? scanner.nextLine().trim() : "1";
            if ("2".equals(type) || "http".equalsIgnoreCase(type)) {
                System.out.print(th.primary + "  ❯ HTTP JSON-RPC URL: " + TuiTheme.RESET);
                String url = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
                if (!name.isEmpty() && !url.isEmpty()) {
                    mcpManager.addHttpServer(name, url);
                    tui.printSuccess("已添加 HTTP MCP Server: " + name);
                }
            } else {
                System.out.print(th.primary + "  ❯ 启动命令 (如 npx): " + TuiTheme.RESET);
                String cmd = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
                System.out.print(th.primary + "  ❯ 启动参数 (空格分隔，如 -y @modelcontextprotocol/server-filesystem .): " + TuiTheme.RESET);
                String rawArgs = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
                if (!name.isEmpty() && !cmd.isEmpty()) {
                    List<String> argList = rawArgs.isEmpty() ? List.of() : Arrays.asList(rawArgs.split("\\s+"));
                    mcpManager.addStdioServer(name, cmd, argList);
                    tui.printSuccess("已添加 Stdio MCP Server: " + name);
                }
            }
        } else if (in.toLowerCase().startsWith("t ")) {
            int idx = Integer.parseInt(in.substring(2).trim());
            if (idx >= 1 && idx <= srvList.size()) {
                McpClientManager.McpServerConfig s = srvList.get(idx - 1);
                mcpManager.toggleServer(s.name);
                tui.printSuccess("已切换 MCP Server `" + s.name + "` 状态为: " + (s.enabled ? "ENABLED" : "DISABLED"));
            }
        } else if (in.toLowerCase().startsWith("test ")) {
            int idx = Integer.parseInt(in.substring(5).trim());
            if (idx >= 1 && idx <= srvList.size()) {
                McpClientManager.McpServerConfig s = srvList.get(idx - 1);
                String res = mcpManager.listToolsFromServer(s.name);
                tui.printBox("🔍 MCP Tools Discovery: " + s.name, List.of(res.split("\r?\n")), th.info);
            }
        }
    }

    private void refreshDashboardHeader() {
        File ws = new File(config.getWorkspaceDir()).getAbsoluteFile();
        tui.printWelcomeBanner(config, githubLogin, gitOps.getQuickStatusBadge(ws),
                skillManager.getAllSkills().size(), mcpManager.getServers().size(),
                sessionManager.getCurrentSessionId());
    }

    private void executeDirectBuiltinGit(String gitCmd) {
        try {
            File ws = new File(config.getWorkspaceDir()).getAbsoluteFile();
            String output = gitOps.executeGitCommand(ws, gitCmd, githubLogin, githubClient.getTokenForGitOverHttp());
            tui.printBox("🌿 Built-in JGit: git " + gitCmd.replaceFirst("^git\\s+", ""),
                    List.of(output.split("\r?\n")), tui.getTheme().info);
        } catch (Exception e) {
            tui.printError("Built-in Git error: " + e.getMessage());
        }
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
            case "edit_file" -> String.format("path=%s (-%d / +%d chars)",
                    args.path("path").asText(),
                    args.path("old_text").asText("").length(),
                    args.path("new_text").asText("").length());
            case "git_command" -> "git " + args.path("command").asText();
            case "load_skill" -> "skill=" + args.path("name").asText();
            case "mcp_call_tool" -> args.path("server").asText() + "::" + args.path("tool").asText();
            default -> {
                String raw = args.toString();
                yield raw.length() > 95 ? raw.substring(0, 92) + "..." : raw;
            }
        };
    }

    private String truncateForContext(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "\n... [truncated " + (text.length() - maxChars) + " chars]";
    }

    private void trimHistoryIfTooLong() {
        while (history.size() > 36) {
            history.remove(0);
        }
    }
}
