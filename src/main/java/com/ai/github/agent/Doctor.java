package com.ai.github.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 环境自检报告（Doctor）。
 *
 * 由 `github-agent --doctor`（非交互）与会话内 `/doctor` 共用，检查：
 * Java 运行时、内置 JGit、工作区可写性、配置文件与信任记录、AI 端点、GitHub 认证状态。
 */
public final class Doctor {

    private Doctor() {
    }

    public static List<String> collect(ConfigManager config, String githubLogin) {
        List<String> checks = new ArrayList<>();
        Path ws = Path.of(config.getWorkspaceDir()).toAbsolutePath().normalize();

        checks.add("✔ 程序版本        : " + AppInfo.fullTitle());
        checks.add("✔ Java 运行时     : Java " + System.getProperty("java.version")
                + " (" + System.getProperty("os.name") + " " + System.getProperty("os.arch") + ")");
        checks.add("✔ 内置 Git 引擎   : Eclipse JGit（纯 Java，无需安装 git 可执行文件）");
        checks.add("✔ 工作区目录      : " + ws);
        checks.add("✔ 工作区可写      : " + (Files.isWritable(ws) ? "是" : "否（只读！写入将失败）"));
        checks.add("✔ 越界写入策略    : " + (config.isAllowOutsideWorkspace()
                ? "允许（--allow-outside）"
                : "已限制（仅工作区内可写）"));
        checks.add("✔ 权限模式        : " + config.getPermissionMode().id
                + "（" + config.getPermissionMode().description + "）");
        checks.add("✔ 配置文件        : " + config.getConfigPath()
                + (Files.exists(config.getConfigPath()) ? "" : " [尚未创建]"));
        checks.add("✔ 信任记录        : " + AppInfo.trustFile()
                + (Files.exists(AppInfo.trustFile()) ? "" : " [当前目录尚未授权]"));
        checks.add("✔ AI API 端点     : " + config.getOpenaiEndpoint()
                + " [model: " + config.getOpenaiModel() + "]"
                + (config.getOpenaiKey() == null || config.getOpenaiKey().isBlank() ? " ⚠ 未配置 API Key" : ""));
        checks.add("✔ GitHub 认证     : " + (githubLogin != null && !githubLogin.isBlank()
                ? "已认证 @" + githubLogin
                : "未连接（离线 / 仅本地代码与内置 Git 模式）"));
        checks.add("✔ 交互式终端      : " + (AppInfo.isInteractive() ? "是" : "否（管道或重定向输入）"));
        return checks;
    }

    public static void print(TuiRenderer tui, ConfigManager config, String githubLogin) {
        tui.printBox("🩺 系统环境自检诊断 (SYSTEM DOCTOR)", collect(config, githubLogin), tui.getTheme().success);
    }
}
