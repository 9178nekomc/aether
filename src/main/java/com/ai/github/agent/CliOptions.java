package com.ai.github.agent;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行参数解析器（CliOptions v3.0）。
 *
 * 安装后即可像 `claude` 一样使用单命令启动：
 *
 *   github-agent                      # 在当前目录启动（等价于 claude）
 *   github-agent ~/code/my-project    # 在指定目录启动（等价于 claude <dir>）
 *   github-agent -p "重构这个模块"     # 一次性任务模式：执行完直接退出（等价于 claude -p）
 *   github-agent --version / --help / --doctor / --config
 *   github-agent --trust              # 跳过信任询问，直接记住当前目录（CI / 脚本用）
 *   github-agent --mode yolo          # 权限模式：safe | strict | yolo
 *   github-agent --allow-outside      # 允许读写工作区之外的路径（默认阻止）
 *   github-agent --forget             # 撤销当前目录的信任记录
 */
public class CliOptions {

    /** 位置参数解析出的目标目录（可为空 → 使用当前目录）。 */
    public String workspaceArg;
    /** -p / --print：一次性执行的任务描述，执行完即退出。 */
    public String prompt;
    public String theme;
    public String model;
    /** AI 供应商 ID（v3.0 新增，对应 ProviderRegistry）。 */
    public String provider;
    /** 通信协议（v3.0 新增：openai-chat / openai-responses / anthropic-messages / gemini-native）。 */
    public String protocol;
    /** 恢复历史会话（v3.0 新增，--resume [sessionId]）。 */
    public String resume;
    /** 权限模式：safe / strict / yolo（null = 未指定，沿用已保存设置）。 */
    public String mode;
    /** 三个互斥的简易开关解析结果。 */
    public Boolean yolo;
    public Boolean strict;

    public boolean help;
    public boolean version;
    public boolean doctor;
    public boolean printConfig;
    /** 跳过信任询问并直接记录（--trust / -y / --yes）。 */
    public boolean autoTrust;
    /** 撤销当前目录信任记录（--forget）。 */
    public boolean forget;
    public boolean allowOutside;
    public boolean noColor;
    public boolean verbose;
    /** 打印被识别但未使用的参数，便于排错。 */
    public final List<String> warnings = new ArrayList<>();

    public static CliOptions parse(String[] args) {
        CliOptions o = new CliOptions();
        if (args == null) {
            return o;
        }
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null || a.isBlank()) {
                continue;
            }
            String lower = a.toLowerCase();
            switch (lower) {
                case "-h", "--help", "help", "-?" -> o.help = true;
                case "-v", "-V", "--version", "version" -> o.version = true;
                case "--doctor" -> o.doctor = true;
                case "--config", "--print-config" -> o.printConfig = true;
                case "--trust", "--yes", "-y", "--auto-trust" -> o.autoTrust = true;
                case "--forget", "--untrust", "--revoke" -> o.forget = true;
                case "--allow-outside", "--unsafe-paths" -> o.allowOutside = true;
                case "--no-color", "--no-colour" -> o.noColor = true;
                case "--verbose", "-d", "--debug" -> o.verbose = true;
                case "--yolo" -> {
                    o.yolo = true;
                    o.mode = "yolo";
                }
                case "--safe" -> {
                    o.yolo = false;
                    o.mode = "safe";
                }
                case "--strict" -> {
                    o.strict = true;
                    o.yolo = false;
                    o.mode = "strict";
                }
                case "--theme" -> {
                    if (i + 1 < args.length) {
                        o.theme = args[++i];
                    } else {
                        o.warnings.add("--theme 缺少取值");
                    }
                }
                case "--model" -> {
                    if (i + 1 < args.length) {
                        o.model = args[++i];
                    } else {
                        o.warnings.add("--model 缺少取值");
                    }
                }
                case "--provider" -> {
                    if (i + 1 < args.length) {
                        o.provider = args[++i];
                    } else {
                        o.warnings.add("--provider 缺少取值");
                    }
                }
                case "--protocol" -> {
                    if (i + 1 < args.length) {
                        o.protocol = args[++i];
                    } else {
                        o.warnings.add("--protocol 缺少取值");
                    }
                }
                case "--resume" -> {
                    if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        o.resume = args[++i];
                    } else {
                        o.resume = "latest";
                    }
                }
                case "--workspace", "--dir", "-C" -> {
                    if (i + 1 < args.length) {
                        o.workspaceArg = args[++i];
                    } else {
                        o.warnings.add("--workspace 缺少取值");
                    }
                }
                case "--mode", "--permission-mode" -> {
                    if (i + 1 < args.length) {
                        o.mode = normalizeMode(args[++i]);
                        if (o.mode == null) {
                            o.warnings.add("未知权限模式，可选: safe / strict / yolo");
                        }
                    } else {
                        o.warnings.add("--mode 缺少取值");
                    }
                }
                case "-p", "--print", "--prompt" -> {
                    if (i + 1 < args.length) {
                        o.prompt = args[++i];
                    } else {
                        o.warnings.add("-p 缺少任务描述");
                    }
                }
                default -> {
                    if (a.startsWith("-")) {
                        o.warnings.add("未识别的参数: " + a);
                    } else if (o.workspaceArg == null) {
                        o.workspaceArg = a;
                    } else if (o.prompt == null) {
                        // 支持 github-agent <dir> "任务描述" 的写法
                        o.prompt = a;
                    }
                }
            }
        }
        return o;
    }

    private static String normalizeMode(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "safe", "ask", "default" -> "safe";
            case "strict", "readonly", "read-only", "paranoid" -> "strict";
            case "yolo", "auto", "accept", "full" -> "yolo";
            default -> null;
        };
    }

    /**
     * 解析出本次会话的工作区绝对路径：位置参数 > --workspace > 当前目录。
     */
    public Path resolveWorkspace() {
        String target = workspaceArg;
        if (target == null || target.isBlank()) {
            return AppInfo.currentDir();
        }
        String expanded = expandHome(target.trim());
        Path p = Paths.get(expanded);
        if (!p.isAbsolute()) {
            p = AppInfo.currentDir().resolve(p);
        }
        return p.toAbsolutePath().normalize();
    }

    private static String expandHome(String path) {
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    public static String helpText() {
        String v = AppInfo.version();
        return """
                %s v%s (%s) — 让 AI 直接读写你的项目并操作 GitHub

                用法:
                  %s [目录] [选项]

                常用示例:
                  %s                                在当前目录启动交互式智能体
                  %s ~/code/my-project              在指定项目目录启动
                  %s -p "把这个模块重构为策略模式"   一次性任务模式，执行完退出
                  %s --mode yolo                    以全自动免确认模式启动
                  %s --doctor                       非交互式环境自检

                选项:
                  -p, --print <任务>        一次性执行任务后退出（CI/脚本友好）
                      --mode <模式>         权限模式: safe(推荐) | strict | yolo
                      --workspace, -C <目录> 指定工作区（等同于位置参数）
                      --theme <名称>        TUI 主题: sky-dark(默认) | sky-light | amber-dark | amber-light | emerald-dark | emerald-light
                      --model <名称>        覆盖本次会话使用的模型
                      --provider <ID>       AI 供应商: deepseek | openai | anthropic | gemini | dashscope | siliconflow | zhipu | moonshot | ark | openrouter | xai | ollama | custom
                      --protocol <名称>     通信协议: openai-chat | openai-responses | anthropic-messages | gemini-native
                      --resume [会话ID]     恢复历史会话（默认 latest）
                      --trust, -y           跳过信任询问，直接记住当前目录
                      --forget              撤销当前目录的信任记录并退出
                      --allow-outside       允许读写工作区之外的路径（默认阻止）
                      --doctor              仅运行环境自检
                      --config              打印当前生效配置
                      --no-color            关闭所有 ANSI 颜色输出
                  -h, --help                显示本帮助
                  -v, --version             显示版本号

                配置与信任记录:
                  配置目录: %s
                  信任记录: %s
                  可用环境变量 GITHUB_AGENT_HOME 重定向整个配置目录（便携模式）。
                """.formatted(
                AppInfo.APP_NAME, v, AppInfo.CODENAME,
                AppInfo.COMMAND,
                AppInfo.COMMAND, AppInfo.COMMAND, AppInfo.COMMAND, AppInfo.COMMAND, AppInfo.COMMAND,
                AppInfo.homeDir(), AppInfo.trustFile());
    }
}
