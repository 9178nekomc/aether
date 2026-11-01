package com.aether.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 工作区信任授权门控（TrustGate）。
 *
 * 首次在某个目录执行 `aether` 时，会像 Claude Code 一样先展示一张授权面板：
 * 明确告知「本程序将能够读写此目录下的文件、执行内置 Git 命令、调用 GitHub API」，
 * 并让用户选择是否允许访问、以及采用哪种权限模式。选择结果会写入
 * ~/.aether/trusted-dirs.json，下次在同一目录启动不再重复询问。
 *
 * 安全性约定：
 * - 未授权目录（含非交互环境无法回答时）一律拒绝启动，退出码 2；
 * - 非交互场景（CI / 管道）必须显式传 `--trust` 才继续，绝不静默授权；
 * - 目录之外的路径默认禁止写入，需 `--allow-outside` 显式放行。
 */
public class TrustGate {

    /** 授权结果。 */
    public static class Result {
        public final boolean trusted;
        public final TrustStore.PermissionMode mode;
        public final boolean sessionOnly;

        public Result(boolean trusted, TrustStore.PermissionMode mode, boolean sessionOnly) {
            this.trusted = trusted;
            this.mode = mode;
            this.sessionOnly = sessionOnly;
        }
    }

    /**
     * 确保当前工作区已获授权；未授权时弹出交互面板询问。
     *
     * @return 授权结果；若用户拒绝，返回 trusted=false（调用方应退出）
     */
    public static Result ensure(Path workspace, TrustStore store, TuiRenderer tui, Scanner scanner, CliOptions opts) {
        TuiTheme theme = tui.getTheme();
        boolean alreadyTrusted = store.isTrusted(workspace);
        TrustStore.PermissionMode cliMode = opts.mode == null ? null : TrustStore.PermissionMode.fromId(opts.mode);

        // 1) 已授权：直接沿用记录（CLI 显式指定的模式优先级更高）
        if (alreadyTrusted) {
            TrustStore.PermissionMode recorded = store.modeFor(workspace);
            if (cliMode != null && cliMode != recorded) {
                store.trust(workspace, cliMode);
                tui.printInfo("已更新 " + shorten(workspace) + " 的权限模式为 " + cliMode.id + "（" + cliMode.description + "）");
                return new Result(true, cliMode, false);
            }
            return new Result(true, recorded, false);
        }

        // 2) 非交互式显式授权：--trust / -y
        if (opts.autoTrust) {
            TrustStore.PermissionMode mode = cliMode != null ? cliMode : TrustStore.PermissionMode.SAFE;
            store.trust(workspace, mode);
            tui.printSuccess("已通过 --trust 记住工作区授权: " + shorten(workspace) + "（模式: " + mode.id + "）");
            return new Result(true, mode, false);
        }

        // 3) 首次进入该目录：渲染授权面板
        renderConsentPanel(tui, workspace);

        System.out.print(theme.warning + TuiTheme.BOLD + "  ❓ 是否允许访问此工作区? [1/2/3] (默认 1): " + TuiTheme.RESET);
        System.out.flush();
        if (!scanner.hasNextLine()) {
            System.out.println();
            tui.printError("当前输入流已关闭，无法确认工作区授权，已安全退出。");
            tui.printInfo("在脚本 / CI 中请显式传入 `--trust` 以授权当前目录。");
            return new Result(false, TrustStore.PermissionMode.SAFE, false);
        }
        String answer = scanner.nextLine().trim();

        if (answer.equals("3") || answer.equalsIgnoreCase("n") || answer.equalsIgnoreCase("no")
                || answer.equals("否") || answer.equals("拒绝")) {
            tui.printWarning("已拒绝授权，未做任何文件改动。");
            tui.printInfo("换一个可信目录启动，或使用 `" + AppInfo.COMMAND + " /path/to/project` 指定工作区。");
            return new Result(false, TrustStore.PermissionMode.SAFE, false);
        }

        boolean sessionOnly = answer.equals("2") || answer.equalsIgnoreCase("session") || answer.equals("仅本次");

        // 4) 选择权限模式（除非命令行已指定）
        TrustStore.PermissionMode mode;
        if (cliMode != null) {
            mode = cliMode;
            tui.printInfo("权限模式取自命令行参数: " + mode.id + "（" + mode.description + "）");
        } else {
            mode = askPermissionMode(tui, scanner);
        }

        if (sessionOnly) {
            tui.printSuccess("本次会话已授权访问 " + shorten(workspace) + "（不写入信任记录，模式: " + mode.id + "）");
            return new Result(true, mode, true);
        }

        store.trust(workspace, mode);
        tui.printSuccess("已授权并记住此目录: " + shorten(workspace) + " → 权限模式 " + mode.id);
        tui.printInfo("随时可用 `" + AppInfo.COMMAND + " --forget` 撤销授权，或在会话中输入 `/trust` 查看详情。");
        return new Result(true, mode, false);
    }

    /**
     * 展示工作区访问授权面板（仿 Claude Code 的 trust dialog）。
     */
    private static void renderConsentPanel(TuiRenderer tui, Path workspace) {
        TuiTheme theme = tui.getTheme();
        List<String> lines = new ArrayList<>();
        lines.add(theme.accent + "📁 工作区目录 : " + TuiTheme.RESET + TuiTheme.BOLD + workspace + TuiTheme.RESET);
        lines.add(theme.accent + "🧩 程序版本   : " + TuiTheme.RESET + theme.text + AppInfo.fullTitle() + TuiTheme.RESET);
        lines.add("");
        lines.add(theme.secondary + "允许访问后，本智能体在此目录内可以：" + TuiTheme.RESET);
        lines.add(theme.text + "  • 读取、创建、修改、删除该目录下的文件（写入前受审批门控保护）" + TuiTheme.RESET);
        lines.add(theme.text + "  • 执行内置纯 Java Git 命令（status / diff / commit / push 等）" + TuiTheme.RESET);
        lines.add(theme.text + "  • 使用你配置的 Token 调用 GitHub REST API（建仓 / 提交 / Issue / PR）" + TuiTheme.RESET);
        lines.add("");
        lines.add(theme.muted + "该目录之外的路径默认只读，写入会被拒绝（可用 --allow-outside 显式放行）。" + TuiTheme.RESET);
        lines.add(theme.muted + "授权记录保存在 " + AppInfo.trustFile() + "，可随时用 --forget 撤销。" + TuiTheme.RESET);
        lines.add("");
        lines.add(theme.success + "[1]" + TuiTheme.RESET + theme.text + " 是，信任此目录并记住（推荐）" + TuiTheme.RESET);
        lines.add(theme.info + "[2]" + TuiTheme.RESET + theme.text + " 是，但仅本次会话有效" + TuiTheme.RESET);
        lines.add(theme.danger + "[3]" + TuiTheme.RESET + theme.text + " 否，退出" + TuiTheme.RESET);
        tui.printBox("🔐 工作区访问授权 (WORKSPACE TRUST)", lines, theme.warning);
    }

    /**
     * 询问权限模式。
     */
    private static TrustStore.PermissionMode askPermissionMode(TuiRenderer tui, Scanner scanner) {
        TuiTheme theme = tui.getTheme();
        List<String> lines = new ArrayList<>();
        lines.add(theme.success + "[1]" + TuiTheme.RESET + TuiTheme.BOLD + " SAFE  " + TuiTheme.RESET
                + theme.text + TrustStore.PermissionMode.SAFE.description + TuiTheme.RESET);
        lines.add(theme.info + "[2]" + TuiTheme.RESET + TuiTheme.BOLD + " STRICT" + TuiTheme.RESET
                + theme.text + " " + TrustStore.PermissionMode.STRICT.description + TuiTheme.RESET);
        lines.add(theme.danger + "[3]" + TuiTheme.RESET + TuiTheme.BOLD + " YOLO  " + TuiTheme.RESET
                + theme.text + TrustStore.PermissionMode.YOLO.description + TuiTheme.RESET);
        lines.add("");
        lines.add(theme.muted + "会话中可随时用 /mode <safe|strict|yolo> 切换。" + TuiTheme.RESET);
        tui.printBox("🛡️ 权限模式 (PERMISSION MODE)", lines, theme.primary);

        System.out.print(theme.primary + TuiTheme.BOLD + "  ❓ 请选择权限模式 [1/2/3] (默认 1): " + TuiTheme.RESET);
        System.out.flush();
        if (!scanner.hasNextLine()) {
            return TrustStore.PermissionMode.SAFE;
        }
        String ans = scanner.nextLine().trim();
        TrustStore.PermissionMode mode = switch (ans) {
            case "2", "strict" -> TrustStore.PermissionMode.STRICT;
            case "3", "yolo" -> TrustStore.PermissionMode.YOLO;
            default -> TrustStore.PermissionMode.SAFE;
        };
        if (mode == TrustStore.PermissionMode.YOLO) {
            tui.printWarning("⚡ 已选择 YOLO：Agent 将不再逐步询问，文件写入、Git 提交与 GitHub 操作会直接执行。请确保当前目录可信。");
        }
        return mode;
    }

    /**
     * 把长路径缩短为「父目录/名」，避免在窄终端里撑破面板。
     */
    private static String shorten(Path p) {
        String s = p.toString();
        return s.length() <= 72 ? s : "…" + s.substring(s.length() - 71);
    }
}
