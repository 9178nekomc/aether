package com.aether.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 应用级常量与路径解析（AppInfo）。
 *
 * 采用「全局命令 + 用户级配置」形态，对标 Claude Code CLI：
 * - 安装后任意目录下直接输入 `aether`（或短别名 `ae`）即可启动；
 * - 配置与信任记录不再散落在当前目录，而是统一存放于用户主目录：
 *
 *   ~/.aether/
 *   ├── config.properties   # 供应商 / 协议 / 密钥 / Token / 主题 / 权限等
 *   ├── trusted-dirs.json   # 已授权访问的工作区目录与权限模式
 *   └── lib/aether.jar      # （由安装脚本放置）fat jar 本体
 *
 * 如需把整套配置改到其他位置（便携模式 / 测试隔离），设置环境变量 AETHER_HOME 即可。
 */
public final class AppInfo {

    public static final String APP_NAME = "Aether";
    public static final String CODENAME = "Lumen";
    public static final String COMMAND = "aether";
    public static final String SHORT_COMMAND = "ae";
    public static final String JAR_NAME = "aether.jar";
    public static final String HOME_DIR_NAME = ".aether";
    public static final String HOME_ENV = "AETHER_HOME";

    /** 更名前（v3.0.x 及以前）的产品名与目录，仅用于一次性迁移与提示。 */
    private static final String LEGACY_HOME_DIR_NAME = ".github-agent";
    private static final String LEGACY_JAR_NAME = "github-agent.jar";
    private static final String LEGACY_COMMAND = "github-agent";

    /** 兜底版本号；打包后优先从 jar 清单的 Implementation-Version 读取，避免与 pom 不一致。 */
    private static final String FALLBACK_VERSION = "3.1.0";

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
     * 用户级数据目录：$AETHER_HOME 优先，否则 ~/.aether。
     */
    public static Path homeDir() {
        String env = System.getenv(HOME_ENV);
        if (env != null && !env.isBlank()) {
            return Paths.get(env.trim()).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home", "."), HOME_DIR_NAME).toAbsolutePath().normalize();
    }

    public static Path configFile() {
        return homeDir().resolve("config.properties");
    }

    public static Path trustFile() {
        return homeDir().resolve("trusted-dirs.json");
    }

    public static Path libJar() {
        return homeDir().resolve("lib").resolve(JAR_NAME);
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

    /**
     * 从旧版（Aether，目录 ~/.aether）一次性迁移配置与信任记录。
     *
     * 规则：仅当新目录不存在、旧目录存在且不是同一个路径时才复制；
     * 旧目录保留不删（用户可自行清理），复制完成后打印一行提示。
     */
    public static void migrateLegacyHome() {
        try {
            Path target = homeDir();
            if (Files.exists(target)) {
                return;
            }
            Path legacy = Paths.get(System.getProperty("user.home", "."), LEGACY_HOME_DIR_NAME)
                    .toAbsolutePath().normalize();
            if (!Files.exists(legacy) || legacy.equals(target)) {
                return;
            }
            Files.createDirectories(target);
            try (Stream<Path> walk = Files.walk(legacy)) {
                walk.forEach(src -> {
                    try {
                        Path dst = target.resolve(legacy.relativize(src).toString());
                        if (Files.isDirectory(src)) {
                            Files.createDirectories(dst);
                        } else {
                            Files.copy(src, dst);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
            // 旧 jar 文件名与新 jar 不同，迁移后删掉，避免占空间
            Files.deleteIfExists(target.resolve("lib").resolve(LEGACY_JAR_NAME));
            System.out.println("  ℹ 已从旧版目录迁移配置: " + legacy + " → " + target + "（旧目录可手动删除）");
        } catch (Exception ignored) {
        }
    }

    /**
     * 若检测到旧版仍在 PATH 中，返回提示文案；否则返回 null。
     */
    public static String legacyInstallHint() {
        try {
            Path legacyBin = Paths.get(System.getProperty("user.home", "."), LEGACY_HOME_DIR_NAME, "bin")
                    .toAbsolutePath().normalize();
            if (!Files.exists(legacyBin)) {
                return null;
            }
            return "检测到旧版仍在 " + legacyBin + "（命令 " + LEGACY_COMMAND
                    + "），可执行 `" + LEGACY_COMMAND + " --forget` 后手动删除该目录，避免两个命令并存。";
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 清空目录（仅用于安装脚本之外的内部清理场景）。 */
    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
