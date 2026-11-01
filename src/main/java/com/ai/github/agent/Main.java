package com.ai.github.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * GitHub AI Agent Pro v2.1 主程序入口（全局命令形态）。
 *
 * v2.1 新增「安装即用」的命令行启动器，使用体验对齐 Claude Code CLI：
 *
 *   $ github-agent                 # 在当前目录启动（自动询问是否允许访问该工作区）
 *   $ github-agent ~/code/proj     # 在指定目录启动
 *   $ github-agent -p "写个脚本"    # 一次性任务模式
 *   $ github-agent --version / --help / --doctor / --config / --forget
 *
 * 启动流程：解析参数 → 加载全局配置（~/.github-agent）→ 工作区信任授权（首次）→
 * 首次运行向导（缺 API Key 时）→ 校验 GitHub Token → 进入 TUI 交互循环。
 *
 * 其他核心能力保持不变：
 * - 零外部 Git 依赖（内置 Eclipse JGit 纯 Java 引擎）
 * - 富色彩 TUI（5 套 TrueColor 主题、彩色 Unified Diff 预览、状态栏、动画加载器）
 * - 多步 ReAct 自主智能体（读文件、精准编辑、内置 Git、GitHub 全量 API）
 * - 三级风险审批门控（SAFE / STRICT / YOLO）
 */
public class Main {

    public static void main(String[] args) {
        CliOptions opts = CliOptions.parse(args);
        prepareStdout(opts);

        if (opts.help) {
            System.out.println(CliOptions.helpText());
            return;
        }
        if (opts.version) {
            System.out.println(AppInfo.fullTitle() + "  (" + AppInfo.homeDir() + ")");
            return;
        }
        for (String w : opts.warnings) {
            System.err.println("⚠ 参数提示: " + w + "（用 --help 查看完整用法）");
        }

        Path workspace = opts.resolveWorkspace();
        if (!Files.isDirectory(workspace)) {
            System.err.println("✖ 工作区目录不存在: " + workspace);
            System.err.println("  请先创建该目录，或用 `" + AppInfo.COMMAND + " <已存在的目录>` 指定工作区。");
            System.exit(2);
            return;
        }

        ConfigManager config = ConfigManager.load(opts);
        config.setWorkspaceDir(workspace.toString());
        TuiTheme theme = TuiTheme.get(config.getTuiTheme());
        TuiRenderer tui = new TuiRenderer(theme);

        if (opts.printConfig) {
            printConfigPanel(tui, config, workspace);
            return;
        }
        if (opts.forget) {
            TrustStore store = new TrustStore();
            boolean removed = store.revoke(workspace);
            if (removed) {
                tui.printSuccess("已撤销该目录的信任记录: " + workspace);
                tui.printInfo("下次在此目录启动时会重新询问是否允许访问。");
            } else {
                tui.printInfo("该目录本来就没有信任记录: " + workspace);
            }
            return;
        }

        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            GitHubApiClient githubClient = new GitHubApiClient(config.getGithubToken());

            // ---- 非交互式自检：只跑诊断，不进入授权与交互循环 ----
            if (opts.doctor) {
                String login = detectLogin(config, githubClient, tui, true);
                Doctor.print(tui, config, login);
                return;
            }

            // ---- 1. 工作区信任授权（首次进入某个目录时询问） ----
            TrustStore trustStore = new TrustStore();
            TrustGate.Result consent = TrustGate.ensure(workspace, trustStore, tui, scanner, opts);
            if (!consent.trusted) {
                System.exit(2);
                return;
            }
            config.applyPermissionMode(consent.mode);
            if (opts.allowOutside) {
                config.setAllowOutsideWorkspace(true);
            }

            // ---- 2. 首次运行向导（仅在缺少 API Key 时触发） ----
            try {
                config.ensureConfigured(scanner);
            } catch (IOException e) {
                tui.printError("配置保存失败: " + e.getMessage());
            }
            if (config.getOpenaiKey() == null || config.getOpenaiKey().isBlank()) {
                tui.printError("未配置 AI API Key，无法启动。");
                tui.printInfo("请重新运行 `" + AppInfo.COMMAND + "` 完成首次向导，或设置环境变量 OPENAI_API_KEY，");
                tui.printInfo("也可直接编辑配置文件: " + config.getConfigPath());
                System.exit(3);
                return;
            }

            // ---- 3. 组装各组件 ----
            AIClient aiClient = new AIClient(config.getOpenaiEndpoint(), config.getOpenaiKey(), config.getOpenaiModel());
            GitOperations gitOps = new GitOperations();

            String login = detectLogin(config, githubClient, tui, false);

            File workspaceFile = new File(config.getWorkspaceDir()).getAbsoluteFile();
            FileSystemTools fsTools = new FileSystemTools(config.getWorkspaceDir());
            fsTools.setEnforceWorkspace(true);
            fsTools.setAllowOutside(config.isAllowOutsideWorkspace());

            ProjectGenerator projectGenerator = new ProjectGenerator(aiClient);
            GitHubActions actions = new GitHubActions(githubClient, gitOps, projectGenerator, login, config.getWorkspaceDir());
            PermissionGate permissionGate = new PermissionGate(config, tui, scanner);

            AgentLoop agentLoop = new AgentLoop(
                    config, aiClient, githubClient, gitOps, actions, fsTools, tui, permissionGate, login
            );

            // ---- 4. 一次性任务模式（-p / --print）：执行完直接退出 ----
            if (opts.prompt != null && !opts.prompt.isBlank()) {
                if (config.getPermissionMode() != TrustStore.PermissionMode.YOLO) {
                    tui.printInfo("一次性任务模式：写入类操作仍会逐次询问确认（如需全自动请加 --mode yolo）。");
                }
                agentLoop.handle(opts.prompt);
                return;
            }

            // ---- 5. 交互式 TUI 主循环 ----
            tui.printWelcomeBanner(config, login, gitOps.getQuickStatusBadge(workspaceFile));

            while (true) {
                File currentWs = new File(config.getWorkspaceDir()).getAbsoluteFile();
                String gitBadge = gitOps.getQuickStatusBadge(currentWs);
                tui.printStatusBar(config, gitBadge, agentLoop.getTurnCount(), aiClient.getTotalEstimatedTokens());
                tui.printPrompt(config.isYoloMode());

                if (!scanner.hasNextLine()) {
                    break;
                }
                String input = scanner.nextLine().trim();
                if (input.isEmpty()) {
                    continue;
                }
                if (input.equals("退出") || input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")
                        || input.equalsIgnoreCase("/exit") || input.equalsIgnoreCase("/quit")) {
                    tui.printInfo("👋 感谢使用 " + AppInfo.fullTitle() + "，再见！");
                    break;
                }
                agentLoop.handle(input);
                System.out.println();
            }
        } catch (Exception e) {
            System.err.println("❌ 程序异常终止: " + e.getMessage());
            if (isDebug()) {
                e.printStackTrace();
            } else {
                System.err.println("   （设置环境变量 GITHUB_AGENT_DEBUG=1 可查看完整堆栈）");
            }
            System.exit(1);
        }
    }

    /**
     * 校验 GitHub Token 并自动同步用户名（失败时提示并降级为纯本地模式）。
     */
    private static String detectLogin(ConfigManager config, GitHubApiClient githubClient, TuiRenderer tui, boolean quiet) {
        String login = config.getGithubUsername();
        if (!githubClient.hasToken()) {
            if (!quiet) {
                tui.printInfo("未配置 GitHub Token，已进入【本地代码编辑 + 内置 JGit】模式（可随时 `/config set token <ghp_xxx>`）。");
            }
            return login;
        }
        TuiRenderer.Spinner spinner = tui.startSpinner("正在通过 GitHub REST API 校验 Token 身份...");
        try {
            JsonNode me = githubClient.getAuthenticatedUser();
            spinner.stop();
            String detected = me.path("login").asText("");
            if (!detected.isBlank()) {
                login = detected;
                config.setGithubUsername(login);
                config.save();
            }
        } catch (Exception e) {
            spinner.stop();
            tui.printWarning("GitHub Token 校验未通过 (" + e.getMessage() + ")，已自动进入【本地代码编辑 + 内置 Git】模式。");
        }
        return login;
    }

    /**
     * 打印当前生效配置（--config）。
     */
    private static void printConfigPanel(TuiRenderer tui, ConfigManager config, Path workspace) {
        TrustStore store = new TrustStore();
        TrustStore.Entry entry = store.entryFor(workspace);
        List<String> lines = new ArrayList<>();
        lines.add("程序版本          = " + AppInfo.fullTitle());
        lines.add("配置文件          = " + config.getConfigPath());
        lines.add("工作区(本次)      = " + workspace);
        lines.add("工作区覆盖项      = " + (config.getWorkspaceOverride().isBlank() ? "(空 → 始终使用启动目录)" : config.getWorkspaceOverride()));
        lines.add("信任记录          = " + (entry == null ? "未授权" : "已授权 " + entry.trustedAt + " (mode=" + entry.mode + ")"));
        lines.add("");
        lines.add("openai.endpoint   = " + config.getOpenaiEndpoint());
        lines.add("openai.model      = " + config.getOpenaiModel());
        lines.add("openai.key        = " + mask(config.getOpenaiKey()));
        lines.add("openai.temperature= " + config.getTemperature());
        lines.add("agent.max_steps   = " + config.getMaxAgentSteps());
        lines.add("github.token      = " + mask(config.getGithubToken()));
        lines.add("github.username   = " + config.getGithubUsername());
        lines.add("权限模式          = " + config.getPermissionMode().id + "（" + config.getPermissionMode().description + "）");
        lines.add("越界写入          = " + (config.isAllowOutsideWorkspace() ? "允许" : "已限制"));
        lines.add("tui.theme         = " + config.getTuiTheme());
        tui.printBox("⚙️ 当前生效配置 (EFFECTIVE CONFIG)", lines, tui.getTheme().secondary);
    }

    private static String mask(String secret) {
        if (secret == null || secret.isBlank()) {
            return "(未设置)";
        }
        if (secret.length() <= 8) {
            return "****";
        }
        return secret.substring(0, 4) + "****" + secret.substring(secret.length() - 4);
    }

    private static boolean isDebug() {
        String v = System.getenv("GITHUB_AGENT_DEBUG");
        return v != null && !v.isBlank() && !v.equals("0");
    }

    /**
     * 统一 UTF-8 输出；在 --no-color 或 NO_COLOR 环境下剥离全部 ANSI 转义序列。
     */
    private static void prepareStdout(CliOptions opts) {
        boolean noColor = opts.noColor || (System.getenv("NO_COLOR") != null && !System.getenv("NO_COLOR").isBlank());
        try {
            if (noColor) {
                OutputStream raw = new FileOutputStream(FileDescriptor.out);
                OutputStream stripper = new FilterOutputStream(raw) {
                    @Override
                    public void write(int b) throws IOException {
                        write(new byte[]{(byte) b}, 0, 1);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        String s = new String(b, off, len, StandardCharsets.UTF_8)
                                .replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
                        out.write(s.getBytes(StandardCharsets.UTF_8));
                    }
                };
                System.setOut(new PrintStream(stripper, true, StandardCharsets.UTF_8));
            } else {
                System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }
}
