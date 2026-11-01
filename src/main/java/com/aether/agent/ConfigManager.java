package com.aether.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Scanner;

/**
 * 分步清屏式配置与引导管理器（ConfigManager v3.0）。
 *
 * 亮点设计：
 * 1. 引导向导的【每一步均自动清屏（clearScreen）】并展示顶部进度条，彻底告别终端文字堆叠混乱。
 * 2. 内置模型供应商（Provider）与多协议（Protocol）可视化选择步骤，防止手动拼写端点出错。
 * 3. 默认采用“淡蓝加纯白简约主题”（`sky-dark`），并提供亮色/暗色共 6 套主题随时切换。
 */
public class ConfigManager {

    /** 旧版（v2.x 及更早）使用的项目内配置文件，仅用于一次性迁移。 */
    private static final String LEGACY_CONFIG_FILE = "config.properties";

    /** 实际配置文件路径：默认 ~/.aether/config.properties，可用 AETHER_HOME 重定向。 */
    private Path configPath = AppInfo.configFile();

    private String providerId = "deepseek";
    private String protocol = "openai-chat";
    private String openaiEndpoint = "https://api.deepseek.com/v1";
    private String openaiKey = "";
    private String openaiModel = "deepseek-chat";
    private double temperature = 0.2;
    private int maxAgentSteps = 20;

    private String githubToken = "";
    private String githubUsername = "";
    /** 默认工作区 = 启动时所在目录（对标 Claude Code：cd 进项目再启动）。 */
    private String workspaceDir = AppInfo.currentDir().toString();

    private String tuiTheme = "sky-dark";
    private boolean yoloMode = false;
    private boolean autoApproveRead = true;
    private boolean allowCatastrophicInYolo = false;
    private String customPromptFile = "AGENT.md";
    private String resumeSessionArg = "";

    /** 权限模式（由 TrustGate 授权结果写入，会话内可用 /mode 切换）。 */
    private TrustStore.PermissionMode permissionMode = TrustStore.PermissionMode.SAFE;
    /** 是否允许读写工作区之外的路径（默认禁止，--allow-outside 或 /trust outside on 放行）。 */
    private boolean allowOutsideWorkspace = false;

    public static void clearTerminalScreen() {
        System.out.print("\u001B[2J\u001B[H");
        System.out.flush();
    }

    /**
     * 加载配置（不触发向导）。工作区以启动参数解析结果为准。
     */
    public static ConfigManager load(CliOptions opts, Path workspace) throws IOException {
        ConfigManager cfg = new ConfigManager();
        cfg.configPath = AppInfo.configFile();
        Path path = cfg.configPath;
        cfg.workspaceDir = workspace.toAbsolutePath().normalize().toString();

        migrateLegacyConfig(path);

        if (Files.exists(path)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            }
            cfg.providerId = p.getProperty("ai.provider", "deepseek");
            cfg.protocol = p.getProperty("ai.protocol", "openai-chat");
            cfg.openaiEndpoint = p.getProperty("openai.endpoint", "https://api.deepseek.com/v1");
            cfg.openaiKey = p.getProperty("openai.key", "");
            cfg.openaiModel = p.getProperty("openai.model", "deepseek-chat");
            cfg.temperature = parseDouble(p.getProperty("openai.temperature", "0.2"), 0.2);
            cfg.maxAgentSteps = parseInt(p.getProperty("agent.max_steps", "20"), 20);

            cfg.githubToken = p.getProperty("github.token", "");
            cfg.githubUsername = p.getProperty("github.username", "");
            cfg.workspaceDir = p.getProperty("workspace.dir", cfg.workspaceDir);

            cfg.tuiTheme = p.getProperty("tui.theme", "sky-dark");
            cfg.yoloMode = Boolean.parseBoolean(p.getProperty("agent.yolo", "false"));
            cfg.autoApproveRead = Boolean.parseBoolean(p.getProperty("agent.auto_approve_read", "true"));
            cfg.allowCatastrophicInYolo = Boolean.parseBoolean(p.getProperty("agent.allow_catastrophic_in_yolo", "false"));
            cfg.customPromptFile = p.getProperty("agent.custom_prompt_file", "AGENT.md");
            cfg.permissionMode = TrustStore.PermissionMode.fromId(p.getProperty("permission.mode", "safe"));
            cfg.allowOutsideWorkspace = Boolean.parseBoolean(p.getProperty("workspace.allow_outside", "false"));
        }

        // 命令行参数优先级最高：工作区、供应商、协议、模型、主题、权限模式
        cfg.workspaceDir = workspace.toAbsolutePath().normalize().toString();
        cfg.applyCliOptions(opts);
        ensureWorkspaceExists(cfg.workspaceDir);
        return cfg;
    }

    /**
     * 缺少 API Key 时运行分步向导；环境变量已提供 Key 则直接采用。
     * 返回 true 表示配置可用。
     */
    public boolean ensureConfigured(Scanner scanner) throws IOException {
        if (openaiKey != null && !openaiKey.isBlank()) {
            return true;
        }
        try {
            ProviderRegistry.ProviderSpec spec = ProviderRegistry.get(providerId);
            String envVal = System.getenv(spec.envKeyName);
            if (envVal != null && !envVal.isBlank()) {
                this.openaiKey = envVal.trim();
                save();
                return true;
            }
        } catch (Exception ignored) {
        }
        runFullInteractiveWizard(scanner);
        save();
        return openaiKey != null && !openaiKey.isBlank();
    }

    /**
     * 把旧版项目内的 config.properties 迁移到用户级目录（只迁移一次，保留原文件不删）。
     * 迁移后统一收紧权限，避免 API Key 被同机其他用户读取。
     */
    private static void migrateLegacyConfig(Path target) {
        try {
            if (Files.exists(target)) {
                return;
            }
            Path legacy = Paths.get(LEGACY_CONFIG_FILE).toAbsolutePath();
            if (!Files.exists(legacy)) {
                return;
            }
            if (legacy.equals(target.toAbsolutePath())) {
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(legacy, target);
            TrustStore.restrictPermissions(target);
            System.out.println("  ℹ 已把项目内配置迁移到用户目录: " + target + "（原文件保留，可手动删除）");
        } catch (Exception ignored) {
        }
    }

    /**
     * 全清屏 7 步引导向导：每一步均先清空屏幕，保持极简清爽体验。
     */
    public void runFullInteractiveWizard(Scanner scanner) throws IOException {
        TuiTheme theme = TuiTheme.get(this.tuiTheme);

        // Step 1/7: 选择模型供应商
        runWizardStep1Provider(scanner, theme, 1, 7);

        // Step 2/7: 选择协议与确认官方端点
        runWizardStep2ProtocolAndEndpoint(scanner, theme, 2, 7);

        // Step 3/7: 配置 API Key
        runWizardStep3ApiKey(scanner, theme, 3, 7);

        // Step 4/7: 选择模型名称
        runWizardStep4Model(scanner, theme, 4, 7);

        // Step 5/7: 配置 GitHub 凭证与工作目录
        clearTerminalScreen();
        printWizardStepHeader(theme, 5, 7, "GitHub 账号与工作区设置 (GitHub & Workspace)");
        System.out.println(theme.muted
                + "  提示（可选）: 填入 GitHub Personal Access Token 仅用于方便进行 GitHub 管理"
                + "（建仓 / Push / PR / Issue / Release）；不需要远端功能可直接回车跳过，本地代码编辑与内置 Git 不受影响。\n"
                + TuiTheme.RESET);
        System.out.print(theme.primary + "  ❯ GitHub Token (ghp_...): " + TuiTheme.RESET);
        String ghTok = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        if (!ghTok.isEmpty()) {
            this.githubToken = ghTok;
        }
        System.out.print(theme.primary + "  ❯ 本地工作区目录 " + theme.muted + "(默认: " + this.workspaceDir + "): " + TuiTheme.RESET);
        String ws = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        if (!ws.isEmpty()) {
            this.workspaceDir = ws;
        }

        // Step 6/7: 选择简约色彩主题（淡蓝白/暖橙/翠绿 × 亮暗色）
        runWizardStep6Theme(scanner, 6, 7);
        theme = TuiTheme.get(this.tuiTheme);

        // Step 7/7: 选择执行安全策略（确认模式 vs YOLO 模式）
        clearTerminalScreen();
        printWizardStepHeader(theme, 7, 7, "安全执行策略 (Execution Permission Policy)");
        System.out.println(theme.text + "  [1] 🛡️  SAFE 确认模式 (默认推荐) —— 写文件、改代码、执行 Git/GitHub 变更前展示彩色 Diff 并询问确认" + TuiTheme.RESET);
        System.out.println(theme.text + "  [2] ⚡ YOLO 全自动模式          —— 全自动连续执行所有工具调用，无需人工逐步确认\n" + TuiTheme.RESET);
        System.out.print(theme.primary + "  ❯ 请选择模式 [1/2, 默认 1]: " + TuiTheme.RESET);
        String modeAns = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        this.yoloMode = modeAns.equals("2") || modeAns.equalsIgnoreCase("yolo") || modeAns.equalsIgnoreCase("y");

        save();
        clearTerminalScreen();
    }

    /**
     * 可随时通过 `/provider` 或 `/model` 单独调用的清屏式模型与供应商配置向导。
     */
    public void runModelAndProviderSetupOnly(Scanner scanner) throws IOException {
        TuiTheme theme = TuiTheme.get(this.tuiTheme);
        runWizardStep1Provider(scanner, theme, 1, 4);
        runWizardStep2ProtocolAndEndpoint(scanner, theme, 2, 4);
        runWizardStep3ApiKey(scanner, theme, 3, 4);
        runWizardStep4Model(scanner, theme, 4, 4);
        save();
        clearTerminalScreen();
    }

    private void runWizardStep1Provider(Scanner scanner, TuiTheme theme, int step, int total) {
        clearTerminalScreen();
        printWizardStepHeader(theme, step, total, "选择 AI 模型供应商 (Select AI Provider)");

        List<ProviderRegistry.ProviderSpec> specs = new ArrayList<>(ProviderRegistry.allProviders().values());
        for (int i = 0; i < specs.size(); i++) {
            ProviderRegistry.ProviderSpec s = specs.get(i);
            String currentMark = s.id.equals(this.providerId) ? theme.success + " ◀ [当前]" + TuiTheme.RESET : "";
            System.out.printf("  %s[%2d]%s %-28s %s(%s | 默认协议: %s)%s%s%n",
                    theme.primary + TuiTheme.BOLD, i + 1, TuiTheme.RESET,
                    theme.text + s.displayName + TuiTheme.RESET,
                    theme.muted, s.defaultEndpoint, s.defaultProtocol, TuiTheme.RESET,
                    currentMark);
        }
        System.out.println();
        System.out.print(theme.primary + "  ❯ 请输入供应商序号或 ID [1-" + specs.size() + ", 默认 1 (DeepSeek)]: " + TuiTheme.RESET);
        String input = scanner.hasNextLine() ? scanner.nextLine().trim() : "";

        ProviderRegistry.ProviderSpec chosen = specs.get(0);
        if (!input.isEmpty()) {
            try {
                int idx = Integer.parseInt(input);
                if (idx >= 1 && idx <= specs.size()) {
                    chosen = specs.get(idx - 1);
                }
            } catch (NumberFormatException e) {
                chosen = ProviderRegistry.get(input);
            }
        }
        this.providerId = chosen.id;
        this.protocol = chosen.defaultProtocol;
        this.openaiEndpoint = chosen.defaultEndpoint;
        this.openaiModel = chosen.defaultModel;
    }

    private void runWizardStep2ProtocolAndEndpoint(Scanner scanner, TuiTheme theme, int step, int total) {
        clearTerminalScreen();
        ProviderRegistry.ProviderSpec spec = ProviderRegistry.get(this.providerId);
        printWizardStepHeader(theme, step, total, "确认通信协议与官方端点 (Protocol & Endpoint)");

        System.out.println(theme.text + "  已选供应商 : " + theme.primary + TuiTheme.BOLD + spec.displayName + TuiTheme.RESET);
        System.out.println(theme.muted + "  说明       : " + spec.notes + "\n" + TuiTheme.RESET);

        List<String> protocols = ProviderRegistry.supportedProtocols();
        for (int i = 0; i < protocols.size(); i++) {
            String p = protocols.get(i);
            String mark = p.equals(this.protocol) ? theme.success + " ◀ [推荐/默认]" + TuiTheme.RESET : "";
            System.out.printf("  %s[%d]%s %s%s%n", theme.primary + TuiTheme.BOLD, i + 1, TuiTheme.RESET, theme.text + p + TuiTheme.RESET, mark);
        }
        System.out.print("\n" + theme.primary + "  ❯ 选择通信协议 [直接回车保持 `" + this.protocol + "`]: " + TuiTheme.RESET);
        String protoIn = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        if (!protoIn.isEmpty()) {
            try {
                int idx = Integer.parseInt(protoIn);
                if (idx >= 1 && idx <= protocols.size()) {
                    this.protocol = protocols.get(idx - 1);
                }
            } catch (NumberFormatException e) {
                this.protocol = protoIn.toLowerCase();
            }
        }

        System.out.print(theme.primary + "  ❯ API 端点 Base URL [直接回车保持 `" + this.openaiEndpoint + "`]: " + TuiTheme.RESET);
        String epIn = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        if (!epIn.isEmpty()) {
            this.openaiEndpoint = epIn;
        }
    }

    private void runWizardStep3ApiKey(Scanner scanner, TuiTheme theme, int step, int total) {
        clearTerminalScreen();
        ProviderRegistry.ProviderSpec spec = ProviderRegistry.get(this.providerId);
        printWizardStepHeader(theme, step, total, "配置 API 密钥 (API Key Authentication)");

        String envVal = System.getenv(spec.envKeyName);
        if (envVal != null && !envVal.isBlank() && this.openaiKey.isBlank()) {
            this.openaiKey = envVal.trim();
        }
        String maskedExisting = this.openaiKey.isBlank()
                ? "未设置"
                : (this.openaiKey.length() > 8 ? this.openaiKey.substring(0, 4) + "****" + this.openaiKey.substring(this.openaiKey.length() - 4) : "****");

        System.out.println(theme.text + "  当前供应商 : " + theme.primary + spec.displayName + TuiTheme.RESET);
        System.out.println(theme.muted + "  环境变量名 : " + spec.envKeyName + " (当前值: " + maskedExisting + ")\n" + TuiTheme.RESET);
        System.out.print(theme.primary + "  ❯ 请输入 API Key [直接回车保持当前值]: " + TuiTheme.RESET);
        String keyIn = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        if (!keyIn.isEmpty()) {
            this.openaiKey = keyIn;
        } else if (this.openaiKey.isBlank() && "ollama".equals(this.providerId)) {
            this.openaiKey = "ollama-local";
        }
    }

    private void runWizardStep4Model(Scanner scanner, TuiTheme theme, int step, int total) {
        clearTerminalScreen();
        ProviderRegistry.ProviderSpec spec = ProviderRegistry.get(this.providerId);
        printWizardStepHeader(theme, step, total, "输入模型名称 (Model Name)");

        // 推荐模型只作为参考提示，不做编号选择：模型名以用户输入为准，
        // 避免「输入 1 到底是指第 1 个模型还是名为 1 的模型」这类歧义。
        System.out.println(theme.muted + "  " + spec.displayName + " 常见模型（仅供参考，可直接输入任意模型名）：" + TuiTheme.RESET);
        for (String m : spec.recommendedModels) {
            String mark = m.equals(this.openaiModel) ? theme.success + " ◀ [当前]" + TuiTheme.RESET : "";
            System.out.println("    " + theme.text + "· " + m + TuiTheme.RESET + mark);
        }
        System.out.println();
        System.out.print(theme.primary + "  ❯ 请输入模型名称 [回车保持 `" + this.openaiModel + "`]: " + TuiTheme.RESET);
        String mIn = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        if (!mIn.isEmpty()) {
            this.openaiModel = mIn;
        }
    }

    public void runWizardStep6Theme(Scanner scanner, int step, int total) {
        clearTerminalScreen();
        TuiTheme curTheme = TuiTheme.get(this.tuiTheme);
        printWizardStepHeader(curTheme, step, total, "选择简约色彩主题 (3 大色系 × 亮色/暗色)");

        List<TuiTheme> themes = new ArrayList<>(TuiTheme.allThemes().values());
        for (int i = 0; i < themes.size(); i++) {
            TuiTheme t = themes.get(i);
            String active = t.name.equals(this.tuiTheme) ? t.success + " ◀ [当前]" + TuiTheme.RESET : "";
            System.out.printf("  %s[%d]%s %s● %-14s%s %s%s%s%n",
                    curTheme.primary + TuiTheme.BOLD, i + 1, TuiTheme.RESET,
                    t.primary + TuiTheme.BOLD, t.name, TuiTheme.RESET,
                    t.text, t.displayName, active);
        }
        System.out.println();
        System.out.print(curTheme.primary + "  ❯ 请选择主题序号或名称 [默认 1 (sky-dark 淡蓝纯白简约)]: " + TuiTheme.RESET);
        String thIn = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        if (!thIn.isEmpty()) {
            try {
                int idx = Integer.parseInt(thIn);
                if (idx >= 1 && idx <= themes.size()) {
                    this.tuiTheme = themes.get(idx - 1).name;
                }
            } catch (NumberFormatException e) {
                this.tuiTheme = TuiTheme.get(thIn).name;
            }
        }
    }

    private void printWizardStepHeader(TuiTheme theme, int step, int total, String title) {
        StringBuilder bar = new StringBuilder();
        for (int i = 1; i <= total; i++) {
            if (i < step) bar.append(theme.success).append("━━━");
            else if (i == step) bar.append(theme.primary).append(TuiTheme.BOLD).append("━●━");
            else bar.append(theme.muted).append("───");
        }
        bar.append(TuiTheme.RESET);

        System.out.println();
        System.out.println(theme.primary + "╭──────────────────────────────────────────────────────────────────────────────╮" + TuiTheme.RESET);
        System.out.printf("%s│  %sStep %d/%d%s  %s  %s%n",
                theme.primary,
                theme.accent + TuiTheme.BOLD, step, total, TuiTheme.RESET,
                bar,
                theme.text + TuiTheme.BOLD + title + TuiTheme.RESET);
        System.out.println(theme.primary + "╰──────────────────────────────────────────────────────────────────────────────╯" + TuiTheme.RESET);
        System.out.println();
    }

    /**
     * 应用命令行参数覆盖（v3.0 参数 + 单命令启动器参数）。
     * 优先级：命令行 > 配置文件 > 内置默认值。
     */
    public void applyCliOptions(CliOptions opts) {
        if (opts == null) {
            return;
        }
        if (opts.yolo != null) {
            this.yoloMode = opts.yolo;
        }
        if (opts.strict != null && opts.strict) {
            this.yoloMode = false;
            this.permissionMode = TrustStore.PermissionMode.STRICT;
        }
        if (opts.theme != null && !opts.theme.isBlank()) {
            this.tuiTheme = TuiTheme.get(opts.theme).name;
        }
        if (opts.model != null && !opts.model.isBlank()) {
            this.openaiModel = opts.model;
        }
        if (opts.provider != null && !opts.provider.isBlank()) {
            ProviderRegistry.ProviderSpec spec = ProviderRegistry.get(opts.provider);
            this.providerId = spec.id;
            this.protocol = spec.defaultProtocol;
            this.openaiEndpoint = spec.defaultEndpoint;
            this.openaiModel = spec.defaultModel;
        }
        if (opts.protocol != null && !opts.protocol.isBlank()) {
            this.protocol = opts.protocol.trim().toLowerCase();
        }
        if (opts.workspaceArg != null && !opts.workspaceArg.isBlank()) {
            this.workspaceDir = opts.resolveWorkspace().toString();
        }
        if (opts.resume != null && !opts.resume.isBlank()) {
            this.resumeSessionArg = opts.resume;
        }
        if (opts.mode != null) {
            applyPermissionMode(TrustStore.PermissionMode.fromId(opts.mode));
        }
        if (opts.allowOutside) {
            this.allowOutsideWorkspace = true;
        }
    }

    private static void ensureWorkspaceExists(String dir) {
        try {
            Files.createDirectories(Paths.get(dir));
        } catch (IOException ignored) {
        }
    }

    public void save() throws IOException {
        Properties p = new Properties();
        p.setProperty("ai.provider", nullToEmpty(providerId));
        p.setProperty("ai.protocol", nullToEmpty(protocol));
        p.setProperty("openai.endpoint", nullToEmpty(openaiEndpoint));
        p.setProperty("openai.key", nullToEmpty(openaiKey));
        p.setProperty("openai.model", nullToEmpty(openaiModel));
        p.setProperty("openai.temperature", String.valueOf(temperature));
        p.setProperty("agent.max_steps", String.valueOf(maxAgentSteps));
        p.setProperty("github.token", nullToEmpty(githubToken));
        p.setProperty("github.username", nullToEmpty(githubUsername));
        p.setProperty("workspace.dir", nullToEmpty(workspaceDir));
        p.setProperty("tui.theme", nullToEmpty(tuiTheme));
        p.setProperty("agent.yolo", String.valueOf(yoloMode));
        p.setProperty("agent.auto_approve_read", String.valueOf(autoApproveRead));
        p.setProperty("agent.allow_catastrophic_in_yolo", String.valueOf(allowCatastrophicInYolo));
        p.setProperty("agent.custom_prompt_file", nullToEmpty(customPromptFile));
        p.setProperty("permission.mode", permissionMode == null ? "safe" : permissionMode.id);
        p.setProperty("workspace.allow_outside", String.valueOf(allowOutsideWorkspace));

        Files.createDirectories(configPath.getParent());
        try (OutputStream out = Files.newOutputStream(configPath)) {
            p.store(out, AppInfo.APP_NAME + " v" + AppInfo.version() + " config - " + configPath);
        }
        TrustStore.restrictPermissions(configPath);
    }

    public String loadCustomProjectInstructions() {
        try {
            Path inWorkspace = Paths.get(workspaceDir).resolve(customPromptFile);
            if (Files.exists(inWorkspace)) {
                return Files.readString(inWorkspace, StandardCharsets.UTF_8);
            }
            Path inRoot = Paths.get(customPromptFile);
            if (Files.exists(inRoot)) {
                return Files.readString(inRoot, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public boolean setDynamicProperty(String key, String value) throws IOException {
        switch (key.toLowerCase()) {
            case "ai.provider", "provider" -> {
                ProviderRegistry.ProviderSpec s = ProviderRegistry.get(value);
                this.providerId = s.id;
                this.protocol = s.defaultProtocol;
                this.openaiEndpoint = s.defaultEndpoint;
                this.openaiModel = s.defaultModel;
            }
            case "ai.protocol", "protocol" -> this.protocol = value.trim().toLowerCase();
            case "openai.endpoint", "endpoint" -> this.openaiEndpoint = value.trim();
            case "openai.key", "key" -> this.openaiKey = value.trim();
            case "openai.model", "model" -> this.openaiModel = value.trim();
            case "openai.temperature", "temperature" -> this.temperature = parseDouble(value, 0.2);
            case "agent.max_steps", "max_steps" -> this.maxAgentSteps = parseInt(value, 20);
            case "github.token", "token" -> this.githubToken = value.trim();
            case "github.username", "username" -> this.githubUsername = value.trim();
            case "workspace.dir", "workspace" -> {
                this.workspaceDir = value.trim();
                ensureWorkspaceExists(this.workspaceDir);
            }
            case "tui.theme", "theme" -> this.tuiTheme = TuiTheme.get(value).name;
            case "agent.yolo", "yolo" -> this.yoloMode = Boolean.parseBoolean(value);
            case "agent.auto_approve_read", "auto_approve_read" -> this.autoApproveRead = Boolean.parseBoolean(value);
            case "agent.custom_prompt_file", "prompt_file" -> this.customPromptFile = value.trim();
            case "permission.mode", "mode" -> applyPermissionMode(TrustStore.PermissionMode.fromId(value));
            case "workspace.allow_outside", "allow_outside" -> this.allowOutsideWorkspace = Boolean.parseBoolean(value);
            default -> {
                return false;
            }
        }
        save();
        return true;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }
    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    public String getProviderId() { return providerId; }
    public String getProtocol() { return protocol; }
    public String getOpenaiEndpoint() { return openaiEndpoint; }
    public String getOpenaiKey() { return openaiKey; }
    public String getOpenaiModel() { return openaiModel; }
    public void setOpenaiModel(String model) { this.openaiModel = model; }
    public double getTemperature() { return temperature; }
    public int getMaxAgentSteps() { return maxAgentSteps; }
    public String getGithubToken() { return githubToken; }
    public String getGithubUsername() { return githubUsername; }
    public void setGithubUsername(String githubUsername) { this.githubUsername = githubUsername; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; ensureWorkspaceExists(workspaceDir); }
    public String getTuiTheme() { return tuiTheme; }
    public void setTuiTheme(String tuiTheme) { this.tuiTheme = tuiTheme; }
    public boolean isYoloMode() { return yoloMode; }
    public void setYoloMode(boolean yoloMode) { this.yoloMode = yoloMode; }
    public boolean isAutoApproveRead() { return autoApproveRead; }
    public boolean isAllowCatastrophicInYolo() { return allowCatastrophicInYolo; }
    public String getCustomPromptFile() { return customPromptFile; }
    public String getResumeSessionArg() { return resumeSessionArg; }

    public Path getConfigPath() { return configPath; }

    public TrustStore.PermissionMode getPermissionMode() {
        return permissionMode == null ? TrustStore.PermissionMode.SAFE : permissionMode;
    }

    /**
     * 切换权限模式：YOLO 会同时打开 yoloMode，其余模式关闭。
     */
    public void applyPermissionMode(TrustStore.PermissionMode mode) {
        this.permissionMode = (mode == null ? TrustStore.PermissionMode.SAFE : mode);
        this.yoloMode = this.permissionMode == TrustStore.PermissionMode.YOLO;
    }

    public boolean isAllowOutsideWorkspace() { return allowOutsideWorkspace; }

    public void setAllowOutsideWorkspace(boolean allow) { this.allowOutsideWorkspace = allow; }
}
