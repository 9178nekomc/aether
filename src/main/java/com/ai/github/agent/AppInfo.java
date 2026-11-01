package com.ai.github.agent;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 应用级常量与路径解析（AppInfo）。
 *
 * GitHub AI Agent Pro v2.1 起改为「全局命令 + 用户级配置」形态，对标 Claude Code CLI：
 * - 安装后任意目录下直接输入 `github-agent`（或短别名 `gha`）即可启动；
 * - 配置与信任记录不再散落在当前目录，而是统一存放于用户主目录：
 *
 *   ~/.github-agent/
 *   ├── config.properties     # API 端点 / 密钥 / Token / 主题 / 权限等
 *   ├── trusted-dirs.json     # 已授权访问的工作区目录与权限模式（等价于 Claude Code 的 trust 记录）
 *   └── lib/github-agent.jar  # （由安装脚本放置）fat jar 本体
 *
 * 如需把整套配置改到其他位置（便携模式 / 测试隔离），设置环境变量 GITHUB_AGENT_HOME 即可。
 */
public final class AppInfo {

    public static final String APP_NAME = "GitHub AI Agent Pro";
    public static final String CODENAME = "Sentinel";
    public static final String COMMAND = "github-agent";
    public static final String SHORT_COMMAND = "gha";

    /** 兜底版本号；打包后优先从 jar 清单的 Implementation-Version 读取，避免与 pom 不一致。 */
    private static final String FALLBACK_VERSION = "2.1.2";

    private AppInfo() {
    }

    public static String version() {
        try {
            Package pkg = AppInfo.class.getPackage();
            if (pkg != null) {
                String v = pkg.getImplementationVersion();
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return FALLBACK_VERSION;
    }

    public static String fullTitle() {
        return APP_NAME + " v" + version() + " (" + CODENAME + ")";
    }

    /**
     * 用户级数据目录：$GITHUB_AGENT_HOME 优先，否则 ~/.github-agent。
     */
    public static Path homeDir() {
        String env = System.getenv("GITHUB_AGENT_HOME");
        if (env != null && !env.isBlank()) {
            return Paths.get(env.trim()).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home", "."), ".github-agent").toAbsolutePath().normalize();
    }

    public static Path configFile() {
        return homeDir().resolve("config.properties");
    }

    public static Path trustFile() {
        return homeDir().resolve("trusted-dirs.json");
    }

    public static Path libJar() {
        return homeDir().resolve("lib").resolve("github-agent.jar");
    }

    /**
     * 当前进程的工作目录（启动时所在目录）。
     */
    public static Path currentDir() {
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    /**
     * 是否运行在真实交互终端中。
     */
    public static boolean isInteractive() {
        try {
            return System.console() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
