package com.ai.github.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Scanner;

/**
 * 配置管理器（ConfigManager v2.1）。
 *
 * v2.1 起配置改为「用户级全局配置」，存放在 ~/.github-agent/config.properties，
 * 这样在任意目录输入 `github-agent` 都能读到同一份配置（对标 Claude Code 的 ~/.claude）。
 *
 * 支持项：
 * - AI 端点、API Key、模型名称、采样温度（temperature）、单轮最大推理步数（max_steps）
 * - GitHub Token 与用户名（支持离线/仅本地 Git 与代码模式）
 * - TUI 主题配色（claude / cyberpunk / codex / dracula / nord）
 * - 权限策略（agent.yolo、agent.auto_approve_read）与工作区越界写入开关（workspace.allow_outside）
 * - 项目级自定义提示词文件（默认读取工作区下的 AGENT.md，对标 Claude Code 的 CLAUDE.md）
 * - 环境变量预填充（OPENAI_API_KEY / OPENAI_BASE_URL / OPENAI_MODEL / GITHUB_TOKEN）
 * - 命令行参数覆盖（--yolo / --mode / --theme / --model / --workspace）
 */
public class ConfigManager {

    private String openaiEndpoint = "https://api.openai.com/v1";
    private String openaiKey = "";
    private String openaiModel = "gpt-4o";
    private double temperature = 0.2;
    private int maxAgentSteps = 20;

    private String githubToken = "";
    private String githubUsername = "";

    /** 运行时生效的工作区（绝对路径，不落盘）。 */
    private String workspaceDir;
    /** 持久化的工作区覆盖项：为空表示「始终使用启动时的当前目录」。 */
    private String workspaceOverride = "";

    private String tuiTheme = "claude";
    private boolean yoloMode = false;
    private boolean autoApproveRead = true;
    private boolean allowCatastrophicInYolo = false;
    private boolean allowOutsideWorkspace = false;
    private String customPromptFile = "AGENT.md";

    private Path configPath = AppInfo.configFile();

    private ConfigManager() {
        this.workspaceDir = AppInfo.currentDir().toString();
    }

    /**
     * 读取配置（不进行任何交互式提问）。
     *
     * @param opts 命令行参数（用于覆盖配置项）；可为 null
     */
    public static ConfigManager load(CliOptions opts) {
        ConfigManager cfg = new ConfigManager();
        cfg.configPath = AppInfo.configFile();
        cfg.workspaceDir = opts != null ? opts.resolveWorkspace().toString() : AppInfo.currentDir().toString();

        Properties p = new Properties();
        Path legacy = Paths.get("config.properties").toAbsolutePath();
        boolean loadedFromLegacy = false;

        if (Files.exists(cfg.configPath)) {
            cfg.readInto(p, cfg.configPath);
        } else if (Files.exists(legacy)) {
            // 兼容 v2.0 及更早版本：把项目目录下的 config.properties 迁移到 ~/.github-agent/
            cfg.readInto(p, legacy);
            loadedFromLegacy = !p.isEmpty();
        }

        String envOpenaiKey = env("OPENAI_API_KEY");
        String envOpenaiBase = env("OPENAI_BASE_URL");
        String envOpenaiModel = env("OPENAI_MODEL");
        String envGithubToken = env("GITHUB_TOKEN");

        cfg.openaiEndpoint = p.getProperty("openai.endpoint", envOpenaiBase != null ? envOpenaiBase : "https://api.openai.com/v1");
        cfg.openaiKey = p.getProperty("openai.key", envOpenaiKey != null ? envOpenaiKey : "");
        cfg.openaiModel = p.getProperty("openai.model", envOpenaiModel != null ? envOpenaiModel : "gpt-4o");
        cfg.temperature = parseDouble(p.getProperty("openai.temperature", "0.2"), 0.2);
        cfg.maxAgentSteps = parseInt(p.getProperty("agent.max_steps", "20"), 20);

        cfg.githubToken = p.getProperty("github.token", envGithubToken != null ? envGithubToken : "");
        cfg.githubUsername = p.getProperty("github.username", "");

        // 新键名 workspace.override；旧键 workspace.dir 仅在显式指定非默认值时才作为覆盖项
        String override = p.getProperty("workspace.override", "");
        if (override.isBlank()) {
            String legacyWs = p.getProperty("workspace.dir", "").trim();
            if (!legacyWs.isEmpty() && !legacyWs.equals(".") && !legacyWs.equals("workspace")) {
                override = legacyWs;
            }
        }
        cfg.workspaceOverride = override;

        cfg.tuiTheme = p.getProperty("tui.theme", "claude");
        cfg.yoloMode = Boolean.parseBoolean(p.getProperty("agent.yolo", "false"));
        cfg.autoApproveRead = Boolean.parseBoolean(p.getProperty("agent.auto_approve_read", "true"));
        cfg.allowCatastrophicInYolo = Boolean.parseBoolean(p.getProperty("agent.allow_catastrophic_in_yolo", "false"));
        cfg.allowOutsideWorkspace = Boolean.parseBoolean(p.getProperty("workspace.allow_outside", "false"));
        cfg.customPromptFile = p.getProperty("agent.custom_prompt_file", "AGENT.md");

        cfg.applyCli(opts);

        if (loadedFromLegacy) {
            try {
                cfg.save();
            } catch (IOException ignored) {
            }
        }
        return cfg;
    }

    private void readInto(Properties p, Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (Exception ignored) {
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? null : v;
    }

    /**
     * 命令行参数覆盖（优先级最高）。
     */
    public void applyCli(CliOptions opts) {
        if (opts == null) {
            return;
        }
        if (opts.theme != null && !opts.theme.isBlank()) {
            this.tuiTheme = opts.theme.trim().toLowerCase();
        }
        if (opts.model != null && !opts.model.isBlank()) {
            this.openaiModel = opts.model.trim();
        }
        if (opts.mode != null) {
            applyPermissionMode(TrustStore.PermissionMode.fromId(opts.mode));
        }
        if (opts.yolo != null) {
            this.yoloMode = opts.yolo;
        }
        if (opts.strict != null) {
            this.autoApproveRead = !opts.strict;
            this.yoloMode = false;
        }
        if (opts.allowOutside) {
            this.allowOutsideWorkspace = true;
        }
        this.workspaceDir = opts.resolveWorkspace().toString();
    }

    /**
     * 首次运行引导：仅当缺少 API Key 时才会提问（其余项都有合理默认值，可随时用 /config 修改）。
     */
    public void ensureConfigured(Scanner scanner) throws IOException {
        if (openaiKey != null && !openaiKey.isBlank()) {
            return;
        }
        TuiTheme theme = TuiTheme.get(tuiTheme);
        System.out.println();
        System.out.println(theme.primary + TuiTheme.BOLD + "╭── 🛠️ 首次运行向导 (FIRST-RUN SETUP) ────────────────────────────╮" + TuiTheme.RESET);
        System.out.println(theme.muted + "│ 只需填 3 项即可开始；配置保存到 " + AppInfo.configFile() + TuiTheme.RESET);
        System.out.println(theme.muted + "│ 之后可随时用 /config set <key> <value> 修改，或直接编辑该文件。  │" + TuiTheme.RESET);
        System.out.println(theme.primary + "╰────────────────────────────────────────────────────────────────╯" + TuiTheme.RESET);

        System.out.print(theme.accent + "1. AI API 端点 " + theme.muted + "(默认 https://api.openai.com/v1): " + TuiTheme.RESET);
        String endpoint = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        if (!endpoint.isEmpty()) {
            this.openaiEndpoint = endpoint;
        }

        System.out.print(theme.accent + "2. AI API Key " + theme.muted + "(sk-... 或兼容 OpenAI 协议的任意密钥): " + TuiTheme.RESET);
        this.openaiKey = scanner.hasNextLine() ? scanner.nextLine().trim() : "";

        System.out.print(theme.accent + "3. 模型名称 " + theme.muted + "(默认 " + openaiModel + "，支持 gpt-4o / claude-3-7-sonnet / deepseek-chat 等): " + TuiTheme.RESET);
        String model = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        if (!model.isEmpty()) {
            this.openaiModel = model;
        }

        System.out.println(theme.info + "\n💡 GitHub Token 可选（用于建仓 / Push / PR / Release；留空则进入纯本地模式）" + TuiTheme.RESET);
        System.out.print(theme.accent + "4. GitHub Token " + theme.muted + "(ghp_... 或 gho_...，可回车跳过): " + TuiTheme.RESET);
        this.githubToken = scanner.hasNextLine() ? scanner.nextLine().trim() : "";

        save();
        System.out.println(theme.success + "✔ 配置已保存: " + AppInfo.configFile() + TuiTheme.RESET);
        System.out.println(theme.muted + "  （文件权限已尽量收紧为仅本人可读写）" + TuiTheme.RESET);
    }

    public void save() throws IOException {
        Properties p = new Properties();
        p.setProperty("openai.endpoint", nullToEmpty(openaiEndpoint));
        p.setProperty("openai.key", nullToEmpty(openaiKey));
        p.setProperty("openai.model", nullToEmpty(openaiModel));
        p.setProperty("openai.temperature", String.valueOf(temperature));
        p.setProperty("agent.max_steps", String.valueOf(maxAgentSteps));
        p.setProperty("github.token", nullToEmpty(githubToken));
        p.setProperty("github.username", nullToEmpty(githubUsername));
        p.setProperty("workspace.override", nullToEmpty(workspaceOverride));
        p.setProperty("workspace.allow_outside", String.valueOf(allowOutsideWorkspace));
        p.setProperty("tui.theme", nullToEmpty(tuiTheme));
        p.setProperty("agent.yolo", String.valueOf(yoloMode));
        p.setProperty("agent.auto_approve_read", String.valueOf(autoApproveRead));
        p.setProperty("agent.allow_catastrophic_in_yolo", String.valueOf(allowCatastrophicInYolo));
        p.setProperty("agent.custom_prompt_file", nullToEmpty(customPromptFile));

        Files.createDirectories(configPath.getParent());
        try (OutputStream out = Files.newOutputStream(configPath)) {
            p.store(out, AppInfo.APP_NAME + " v" + AppInfo.version() + " config - " + configPath);
        }
        TrustStore.restrictPermissions(configPath);
    }

    /**
     * 读取工作区或项目根目录下的自定义提示词文件（如 AGENT.md），实现类似 CLAUDE.md 的项目记忆定制。
     */
    public String loadCustomProjectInstructions() {
        try {
            Path inWorkspace = Paths.get(workspaceDir).resolve(customPromptFile);
            if (Files.exists(inWorkspace)) {
                return Files.readString(inWorkspace, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * 应用权限模式（同时维护 yoloMode / autoApproveRead 两个底层开关）。
     */
    public void applyPermissionMode(TrustStore.PermissionMode mode) {
        if (mode == null) {
            return;
        }
        switch (mode) {
            case SAFE -> {
                this.yoloMode = false;
                this.autoApproveRead = true;
            }
            case STRICT -> {
                this.yoloMode = false;
                this.autoApproveRead = false;
            }
            case YOLO -> this.yoloMode = true;
        }
    }

    /**
     * 当前生效的权限模式。
     */
    public TrustStore.PermissionMode getPermissionMode() {
        if (yoloMode) {
            return TrustStore.PermissionMode.YOLO;
        }
        return autoApproveRead ? TrustStore.PermissionMode.SAFE : TrustStore.PermissionMode.STRICT;
    }

    public boolean setDynamicProperty(String key, String value) throws IOException {
        switch (key.toLowerCase()) {
            case "openai.endpoint", "endpoint" -> this.openaiEndpoint = value;
            case "openai.key", "key" -> this.openaiKey = value;
            case "openai.model", "model" -> this.openaiModel = value;
            case "openai.temperature", "temperature" -> this.temperature = parseDouble(value, 0.2);
            case "agent.max_steps", "max_steps" -> this.maxAgentSteps = parseInt(value, 20);
            case "github.token", "token" -> this.githubToken = value;
            case "github.username", "username" -> this.githubUsername = value;
            case "workspace", "workspace.override" -> {
                this.workspaceOverride = value == null ? "" : value.trim();
                this.workspaceDir = Paths.get(workspaceOverride.isEmpty() ? AppInfo.currentDir().toString() : workspaceOverride)
                        .toAbsolutePath().normalize().toString();
                Files.createDirectories(Paths.get(this.workspaceDir));
            }
            case "workspace.allow_outside", "allow_outside" -> this.allowOutsideWorkspace = Boolean.parseBoolean(value);
            case "tui.theme", "theme" -> this.tuiTheme = value.toLowerCase();
            case "agent.yolo", "yolo" -> this.yoloMode = Boolean.parseBoolean(value);
            case "agent.auto_approve_read", "auto_approve_read" -> this.autoApproveRead = Boolean.parseBoolean(value);
            case "agent.custom_prompt_file", "prompt_file" -> this.customPromptFile = value;
            default -> {
                return false;
            }
        }
        save();
        return true;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public Path getConfigPath() { return configPath; }
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
    public void setWorkspaceDir(String workspaceDir) {
        this.workspaceDir = workspaceDir;
        try {
            Files.createDirectories(Paths.get(workspaceDir));
        } catch (IOException ignored) {
        }
    }
    public String getWorkspaceOverride() { return workspaceOverride; }
    public String getTuiTheme() { return tuiTheme; }
    public void setTuiTheme(String tuiTheme) { this.tuiTheme = tuiTheme; }
    public boolean isYoloMode() { return yoloMode; }
    public void setYoloMode(boolean yoloMode) { this.yoloMode = yoloMode; }
    public boolean isAutoApproveRead() { return autoApproveRead; }
    public boolean isAllowCatastrophicInYolo() { return allowCatastrophicInYolo; }
    public boolean isAllowOutsideWorkspace() { return allowOutsideWorkspace; }
    public void setAllowOutsideWorkspace(boolean v) { this.allowOutsideWorkspace = v; }
    public String getCustomPromptFile() { return customPromptFile; }
}
