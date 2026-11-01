package com.ai.github.agent;

import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.FileTreeIterator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 零外部依赖的内置纯 Java Git 引擎（基于 Eclipse JGit）。
 *
 * 核心特性：
 * 1. 用户机器【完全不需要安装 Git 客户端】（无需 /usr/bin/git 或 git.exe），所有 Git 能力自带于 JAR 包内。
 * 2. 提供完整的命令解析器 executeGitCommand(...)：
 *    无论是 Agent 调用 git_command 工具，还是用户在 TUI 中输入 `/git status`、`git diff`、`git log --oneline`、
 *    `git checkout -b feat/x`、`git add .`、`git commit -m "..."`、`git branch -a`、`git stash`、`git tag`、
 *    `git remote -v`、`git reset`、`git push`、`git pull`、`git clone`、`git show`，
 *    全部由本类在 JVM 内部通过 JGit 原生执行并返回标准格式输出！
 */
public class GitOperations {

    public Git initRepo(File dir) throws GitAPIException {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return Git.init().setDirectory(dir).call();
    }

    public Git openOrInitRepo(File dir) throws IOException, GitAPIException {
        File gitDir = new File(dir, ".git");
        if (gitDir.exists()) {
            return Git.open(dir);
        }
        return initRepo(dir);
    }

    public boolean isGitRepository(File dir) {
        if (dir == null || !dir.exists()) {
            return false;
        }
        File current = dir.getAbsoluteFile();
        while (current != null) {
            if (new File(current, ".git").exists()) {
                return true;
            }
            current = current.getParentFile();
        }
        return false;
    }

    public File findRepoRoot(File startDir) {
        File current = startDir.getAbsoluteFile();
        while (current != null) {
            if (new File(current, ".git").exists()) {
                return current;
            }
            current = current.getParentFile();
        }
        return startDir.getAbsoluteFile();
    }

    /**
     * 获取用于在 TUI 状态栏实时展示的简短分支与变更摘要（例如：`main ✓` 或 `feat/ui *3 +1`）。
     */
    public String getQuickStatusBadge(File dir) {
        File root = findRepoRoot(dir);
        if (!new File(root, ".git").exists()) {
            return "no-repo (内置JGit就绪)";
        }
        try (Git git = Git.open(root)) {
            String branch = git.getRepository().getBranch();
            if (branch == null) {
                branch = "HEAD";
            }
            Status status = git.status().call();
            int modified = status.getModified().size() + status.getChanged().size() + status.getRemoved().size() + status.getMissing().size();
            int untracked = status.getUntracked().size() + status.getAdded().size();
            if (modified == 0 && untracked == 0) {
                return branch + " ✔";
            }
            return String.format("%s *%d +%d", branch, modified, untracked);
        } catch (Exception e) {
            return "git-ready";
        }
    }

    public void ensureMainBranch(Git git) throws GitAPIException, IOException {
        String current = git.getRepository().getBranch();
        if (current != null && !current.equals("main") && git.getRepository().resolve("HEAD") != null) {
            git.branchRename().setNewName("main").call();
        }
    }

    public void addAll(Git git) throws GitAPIException {
        git.add().addFilepattern(".").call();
        // 同步处理已删除的文件（等价于 git add -A）
        git.add().setUpdate(true).addFilepattern(".").call();
    }

    public RevCommit commit(Git git, String message, String authorName, String authorEmail) throws GitAPIException {
        String safeName = (authorName == null || authorName.isBlank()) ? "github-agent" : authorName;
        String safeEmail = (authorEmail == null || authorEmail.isBlank())
                ? safeName + "@users.noreply.github.com"
                : authorEmail;
        return git.commit()
                .setMessage(message)
                .setAuthor(safeName, safeEmail)
                .setCommitter(safeName, safeEmail)
                .call();
    }

    public void addRemote(Git git, String name, String url) throws GitAPIException, URISyntaxException, IOException {
        StoredConfig config = git.getRepository().getConfig();
        config.setString("remote", name, "url", url);
        config.setString("remote", name, "fetch", "+refs/heads/*:refs/remotes/" + name + "/*");
        config.save();
    }

    public String push(Git git, String username, String token) throws GitAPIException, IOException {
        String branch = git.getRepository().getBranch();
        if (branch == null || branch.isBlank()) {
            branch = "main";
        }
        var pushCmd = git.push()
                .setRemote("origin")
                .add(branch);
        if (token != null && !token.isBlank()) {
            String user = (username == null || username.isBlank()) ? "x-access-token" : username;
            pushCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, token));
        }
        Iterable<PushResult> results = pushCmd.call();
        StringBuilder sb = new StringBuilder();
        for (PushResult r : results) {
            for (RemoteRefUpdate update : r.getRemoteUpdates()) {
                sb.append("Push ").append(update.getRemoteName()).append(" -> ").append(update.getStatus()).append("\n");
            }
        }
        return sb.length() == 0 ? "Push completed successfully." : sb.toString().trim();
    }

    public Git cloneRepo(String url, File dir, String username, String token) throws GitAPIException {
        var cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir);
        if (token != null && !token.isBlank()) {
            String user = (username == null || username.isBlank()) ? "x-access-token" : username;
            cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, token));
        }
        return cmd.call();
    }

    /**
     * 核心方法：在 JVM 内直接解析并执行自然 Git 命令行指令（100% 纯 Java JGit 实现，无需外部 git 程序）。
     *
     * 支持的子命令包括：
     * - init
     * - status [-s|--short]
     * - add <pathspec|.|--all|-A>
     * - commit -m "<msg>" [-a]
     * - diff [--cached|--staged] [path]
     * - log [-n <count>|--oneline]
     * - show [rev]
     * - branch [-a|-d <name>|-M <name>|<new-branch>]
     * - checkout [-b] <branch|path>
     * - switch [-c] <branch>
     * - reset [--soft|--mixed|--hard] [rev]
     * - stash [push|pop|list|apply|drop]
     * - tag [-d] [tagname]
     * - remote [-v|add <name> <url>|set-url <name> <url>|remove <name>]
     * - pull / fetch / push / clone
     * - rm <file>
     */
    public String executeGitCommand(File workingDir, String rawCommand, String githubUser, String githubToken) throws Exception {
        List<String> tokens = tokenizeCommand(rawCommand);
        if (tokens.isEmpty()) {
            return "请提供 git 子命令，例如: status, diff, log, add, commit, branch, checkout, push, pull, clone";
        }
        if (tokens.get(0).equalsIgnoreCase("git")) {
            tokens.remove(0);
        }
        if (tokens.isEmpty()) {
            return getBuiltinGitHelp();
        }

        String sub = tokens.get(0).toLowerCase();
        List<String> args = tokens.subList(1, tokens.size());

        if (sub.equals("version") || sub.equals("--version")) {
            return "git version 2.44.0 (Built-in Pure-Java Eclipse JGit Engine 6.8.0 - Zero External Git Dependency)";
        }
        if (sub.equals("help") || sub.equals("--help")) {
            return getBuiltinGitHelp();
        }

        if (sub.equals("init")) {
            File targetDir = workingDir;
            if (!args.isEmpty() && !args.get(0).startsWith("-")) {
                targetDir = new File(workingDir, args.get(0));
            }
            try (Git git = initRepo(targetDir)) {
                return "Initialized empty Git repository (via built-in JGit) in " + new File(targetDir, ".git").getAbsolutePath();
            }
        }

        if (sub.equals("clone")) {
            if (args.isEmpty()) {
                return "错误: git clone 需要提供仓库 URL，例如: git clone https://github.com/owner/repo.git [目录]";
            }
            String url = args.get(0);
            String folderName = extractRepoNameFromUrl(url);
            if (args.size() >= 2 && !args.get(1).startsWith("-")) {
                folderName = args.get(1);
            }
            File dest = new File(workingDir, folderName);
            try (Git cloned = cloneRepo(url, dest, githubUser, githubToken)) {
                return "Cloned repository '" + url + "' into '" + dest.getAbsolutePath() + "' via built-in JGit.";
            }
        }

        File repoRoot = findRepoRoot(workingDir);
        if (!new File(repoRoot, ".git").exists()) {
            return "当前目录不是 Git 仓库 (" + workingDir.getAbsolutePath() + ")。可先运行 `git init` 初始化仓库。";
        }

        try (Git git = Git.open(repoRoot)) {
            return switch (sub) {
                case "status" -> runStatus(git, args);
                case "add" -> runAdd(git, args);
                case "commit" -> runCommit(git, args, githubUser);
                case "diff" -> runDiff(git, args);
                case "log" -> runLog(git, args);
                case "show" -> runShow(git, args);
                case "branch" -> runBranch(git, args);
                case "checkout" -> runCheckout(git, args);
                case "switch" -> runSwitch(git, args);
                case "reset" -> runReset(git, args);
                case "stash" -> runStash(git, args);
                case "tag" -> runTag(git, args);
                case "remote" -> runRemote(git, args);
                case "push" -> push(git, githubUser, githubToken);
                case "pull" -> runPull(git, githubUser, githubToken);
                case "fetch" -> runFetch(git, githubUser, githubToken);
                case "rm" -> runRm(git, args);
                default -> "内置 JGit 引擎暂未单独映射子命令 `" + sub + "`。支持的内置命令: init, clone, status, add, commit, diff, log, show, branch, checkout, switch, reset, stash, tag, remote, push, pull, fetch, rm。";
            };
        }
    }

    private String runStatus(Git git, List<String> args) throws GitAPIException, IOException {
        Status s = git.status().call();
        String branch = git.getRepository().getBranch();
        boolean shortFmt = args.contains("-s") || args.contains("--short");

        StringBuilder sb = new StringBuilder();
        if (shortFmt) {
            sb.append("## ").append(branch).append("\n");
            appendSet(sb, "A  ", s.getAdded());
            appendSet(sb, "M  ", s.getChanged());
            appendSet(sb, " M ", s.getModified());
            appendSet(sb, "D  ", s.getRemoved());
            appendSet(sb, " D ", s.getMissing());
            appendSet(sb, "?? ", s.getUntracked());
            return sb.toString().trim();
        }

        sb.append("On branch ").append(branch).append("\n");
        if (s.isClean()) {
            sb.append("nothing to commit, working tree clean");
            return sb.toString();
        }

        if (!s.getAdded().isEmpty() || !s.getChanged().isEmpty() || !s.getRemoved().isEmpty()) {
            sb.append("\nChanges to be committed:\n");
            appendSet(sb, "  new file:   ", s.getAdded());
            appendSet(sb, "  modified:   ", s.getChanged());
            appendSet(sb, "  deleted:    ", s.getRemoved());
        }
        if (!s.getModified().isEmpty() || !s.getMissing().isEmpty()) {
            sb.append("\nChanges not staged for commit:\n");
            appendSet(sb, "  modified:   ", s.getModified());
            appendSet(sb, "  deleted:    ", s.getMissing());
        }
        if (!s.getUntracked().isEmpty()) {
            sb.append("\nUntracked files:\n");
            appendSet(sb, "  ", s.getUntracked());
        }
        return sb.toString().trim();
    }

    private void appendSet(StringBuilder sb, String prefix, Set<String> items) {
        for (String item : items) {
            sb.append(prefix).append(item).append("\n");
        }
    }

    private String runAdd(Git git, List<String> args) throws GitAPIException {
        if (args.isEmpty() || args.contains(".") || args.contains("-A") || args.contains("--all")) {
            addAll(git);
            return "Staged all changes (git add -A) via built-in JGit.";
        }
        var addCmd = git.add();
        for (String pattern : args) {
            if (!pattern.startsWith("-")) {
                addCmd.addFilepattern(pattern);
            }
        }
        addCmd.call();
        return "Staged: " + String.join(", ", args);
    }

    private String runCommit(Git git, List<String> args, String githubUser) throws GitAPIException {
        boolean all = args.contains("-a") || args.contains("-am") || args.contains("--all");
        String message = null;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if ((arg.equals("-m") || arg.equals("-am") || arg.equals("--message")) && i + 1 < args.size()) {
                message = args.get(i + 1);
                break;
            }
        }
        if (message == null || message.isBlank()) {
            return "错误: git commit 需要通过 `-m \"提交说明\"` 指定提交信息。";
        }
        if (all) {
            git.add().setUpdate(true).addFilepattern(".").call();
        }
        RevCommit rev = commit(git, message, githubUser, null);
        String shortId = rev.getId().abbreviate(7).name();
        return String.format("[%s] %s (Author: %s)", shortId, rev.getShortMessage(), rev.getAuthorIdent().getName());
    }

    private String runDiff(Git git, List<String> args) throws GitAPIException, IOException {
        boolean cached = args.contains("--cached") || args.contains("--staged");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter fmt = new DiffFormatter(out)) {
            fmt.setRepository(git.getRepository());
            List<DiffEntry> entries = git.diff().setCached(cached).call();
            if (entries.isEmpty()) {
                return "(无差异 / No diff output)";
            }
            for (DiffEntry entry : entries) {
                fmt.format(entry);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private String runLog(Git git, List<String> args) throws GitAPIException, IOException {
        if (git.getRepository().resolve("HEAD") == null) {
            return "当前仓库尚无任何 Commit 记录。";
        }
        int maxCount = 10;
        boolean oneline = args.contains("--oneline");
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if ((a.equals("-n") || a.equals("--max-count")) && i + 1 < args.size()) {
                try {
                    maxCount = Integer.parseInt(args.get(i + 1));
                } catch (NumberFormatException ignored) {
                }
            } else if (a.matches("-\\d+")) {
                maxCount = Integer.parseInt(a.substring(1));
            }
        }

        Iterable<RevCommit> commits = git.log().setMaxCount(maxCount).call();
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (RevCommit c : commits) {
            String shortHash = c.getId().abbreviate(7).name();
            if (oneline) {
                sb.append(shortHash).append(" ").append(c.getShortMessage()).append("\n");
            } else {
                PersonIdent author = c.getAuthorIdent();
                sb.append("commit ").append(c.getName()).append("\n");
                sb.append("Author: ").append(author.getName()).append(" <").append(author.getEmailAddress()).append(">\n");
                sb.append("Date:   ").append(sdf.format(new Date(c.getCommitTime() * 1000L))).append("\n\n");
                sb.append("    ").append(c.getFullMessage().trim().replace("\n", "\n    ")).append("\n\n");
            }
        }
        return sb.toString().trim();
    }

    private String runShow(Git git, List<String> args) throws IOException, GitAPIException {
        Repository repo = git.getRepository();
        String revStr = args.isEmpty() ? "HEAD" : args.get(0);
        ObjectId commitId = repo.resolve(revStr);
        if (commitId == null) {
            return "未找到提交引用: " + revStr;
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(commitId);
            StringBuilder sb = new StringBuilder();
            sb.append("commit ").append(commit.getName()).append("\n");
            sb.append("Author: ").append(commit.getAuthorIdent().getName())
                    .append(" <").append(commit.getAuthorIdent().getEmailAddress()).append(">\n");
            sb.append("\n    ").append(commit.getFullMessage().trim()).append("\n\n");

            ByteArrayOutputStream diffOut = new ByteArrayOutputStream();
            try (DiffFormatter fmt = new DiffFormatter(diffOut)) {
                fmt.setRepository(repo);
                if (commit.getParentCount() > 0) {
                    RevCommit parent = walk.parseCommit(commit.getParent(0).getId());
                    fmt.format(parent.getTree(), commit.getTree());
                } else {
                    try (ObjectReader reader = repo.newObjectReader()) {
                        CanonicalTreeParser newTree = new CanonicalTreeParser();
                        newTree.reset(reader, commit.getTree());
                        fmt.format(new org.eclipse.jgit.treewalk.EmptyTreeIterator(), newTree);
                    }
                }
            }
            sb.append(diffOut.toString(StandardCharsets.UTF_8));
            return sb.toString();
        }
    }

    private String runBranch(Git git, List<String> args) throws GitAPIException, IOException {
        if (args.isEmpty() || args.contains("-a") || args.contains("--all") || args.contains("-l")) {
            String current = git.getRepository().getBranch();
            List<Ref> refs = git.branchList().call();
            StringBuilder sb = new StringBuilder();
            for (Ref ref : refs) {
                String shortName = Repository.shortenRefName(ref.getName());
                if (shortName.equals(current)) {
                    sb.append("* ").append(shortName).append("\n");
                } else {
                    sb.append("  ").append(shortName).append("\n");
                }
            }
            return sb.length() == 0 ? "* " + current + " (尚无提交)" : sb.toString().trim();
        }
        if ((args.contains("-d") || args.contains("-D")) && args.size() >= 2) {
            String target = args.get(args.size() - 1);
            git.branchDelete().setBranchNames(target).setForce(args.contains("-D")).call();
            return "Deleted branch " + target;
        }
        if ((args.contains("-m") || args.contains("-M")) && args.size() >= 2) {
            String newName = args.get(args.size() - 1);
            git.branchRename().setNewName(newName).call();
            return "Renamed current branch to " + newName;
        }
        String newBranch = args.get(0);
        git.branchCreate().setName(newBranch).call();
        return "Created branch '" + newBranch + "'";
    }

    private String runCheckout(Git git, List<String> args) throws GitAPIException {
        if (args.isEmpty()) {
            return "错误: git checkout 需要指定分支名或文件路径。";
        }
        boolean create = args.contains("-b") || args.contains("-B");
        String target = args.get(args.size() - 1);
        git.checkout()
                .setCreateBranch(create)
                .setName(target)
                .call();
        return (create ? "Switched to a new branch '" : "Switched to branch '") + target + "'";
    }

    private String runSwitch(Git git, List<String> args) throws GitAPIException {
        if (args.isEmpty()) {
            return "错误: git switch 需要指定分支名。";
        }
        boolean create = args.contains("-c") || args.contains("--create");
        String target = args.get(args.size() - 1);
        git.checkout()
                .setCreateBranch(create)
                .setName(target)
                .call();
        return (create ? "Switched to a new branch '" : "Switched to branch '") + target + "'";
    }

    private String runReset(Git git, List<String> args) throws GitAPIException {
        ResetCommand.ResetType mode = ResetCommand.ResetType.MIXED;
        String ref = "HEAD";
        for (String a : args) {
            if (a.equals("--soft")) {
                mode = ResetCommand.ResetType.SOFT;
            } else if (a.equals("--hard")) {
                mode = ResetCommand.ResetType.HARD;
            } else if (a.equals("--mixed")) {
                mode = ResetCommand.ResetType.MIXED;
            } else if (!a.startsWith("-")) {
                ref = a;
            }
        }
        Ref result = git.reset().setMode(mode).setRef(ref).call();
        return "HEAD is now at " + (result != null && result.getObjectId() != null ? result.getObjectId().abbreviate(7).name() : ref)
                + " (" + mode.name() + ")";
    }

    private String runStash(Git git, List<String> args) throws GitAPIException {
        String action = args.isEmpty() ? "push" : args.get(0).toLowerCase();
        return switch (action) {
            case "push", "save" -> {
                RevCommit stash = git.stashCreate().call();
                yield stash == null ? "No local changes to save" : "Saved working directory and index state: " + stash.getId().abbreviate(7).name();
            }
            case "pop", "apply" -> {
                ObjectId applied = git.stashApply().call();
                if (action.equals("pop")) {
                    git.stashDrop().call();
                }
                yield "Applied stash: " + applied.abbreviate(7).name();
            }
            case "list" -> {
                var list = git.stashList().call();
                StringBuilder sb = new StringBuilder();
                int idx = 0;
                for (RevCommit c : list) {
                    sb.append("stash@{").append(idx++).append("}: ").append(c.getShortMessage()).append("\n");
                }
                yield sb.length() == 0 ? "(stash 列表为空)" : sb.toString().trim();
            }
            case "drop" -> {
                git.stashDrop().call();
                yield "Dropped stash@{0}";
            }
            default -> "支持的 git stash 子命令: push, pop, apply, list, drop";
        };
    }

    private String runTag(Git git, List<String> args) throws GitAPIException {
        if (args.isEmpty() || args.contains("-l")) {
            List<Ref> tags = git.tagList().call();
            if (tags.isEmpty()) {
                return "(暂无 Tag)";
            }
            StringBuilder sb = new StringBuilder();
            for (Ref t : tags) {
                sb.append(Repository.shortenRefName(t.getName())).append("\n");
            }
            return sb.toString().trim();
        }
        if (args.contains("-d") && args.size() >= 2) {
            String tagName = args.get(args.size() - 1);
            git.tagDelete().setTags(tagName).call();
            return "Deleted tag '" + tagName + "'";
        }
        String tagName = args.get(0);
        git.tag().setName(tagName).call();
        return "Created tag '" + tagName + "'";
    }

    private String runRemote(Git git, List<String> args) throws GitAPIException, URISyntaxException, IOException {
        if (args.isEmpty() || args.contains("-v")) {
            List<RemoteConfig> remotes = git.remoteList().call();
            if (remotes.isEmpty()) {
                return "(未配置任何远程仓库 remote)";
            }
            StringBuilder sb = new StringBuilder();
            for (RemoteConfig rc : remotes) {
                for (URIish uri : rc.getURIs()) {
                    sb.append(rc.getName()).append("\t").append(uri.toString()).append(" (fetch/push)\n");
                }
            }
            return sb.toString().trim();
        }
        String action = args.get(0);
        if ((action.equals("add") || action.equals("set-url")) && args.size() >= 3) {
            addRemote(git, args.get(1), args.get(2));
            return "Configured remote '" + args.get(1) + "' -> " + args.get(2);
        }
        if ((action.equals("remove") || action.equals("rm")) && args.size() >= 2) {
            git.remoteRemove().setRemoteName(args.get(1)).call();
            return "Removed remote '" + args.get(1) + "'";
        }
        return "用法: git remote -v | git remote add <name> <url> | git remote set-url <name> <url>";
    }

    private String runPull(Git git, String username, String token) throws GitAPIException {
        var pullCmd = git.pull();
        if (token != null && !token.isBlank()) {
            String user = (username == null || username.isBlank()) ? "x-access-token" : username;
            pullCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, token));
        }
        var res = pullCmd.call();
        return "Pull result: " + (res.isSuccessful() ? "SUCCESS" : "FAILED") + " (" + res.getMergeResult() + ")";
    }

    private String runFetch(Git git, String username, String token) throws GitAPIException {
        var fetchCmd = git.fetch();
        if (token != null && !token.isBlank()) {
            String user = (username == null || username.isBlank()) ? "x-access-token" : username;
            fetchCmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(user, token));
        }
        var res = fetchCmd.call();
        return "Fetch completed from remote. Tracking updates: " + res.getTrackingRefUpdates().size();
    }

    private String runRm(Git git, List<String> args) throws GitAPIException {
        if (args.isEmpty()) {
            return "错误: git rm 需要指定文件名。";
        }
        var rm = git.rm();
        for (String a : args) {
            if (!a.startsWith("-")) {
                rm.addFilepattern(a);
            }
        }
        rm.call();
        return "Removed from index/working tree: " + String.join(", ", args);
    }

    private String extractRepoNameFromUrl(String url) {
        String clean = url.trim();
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.endsWith(".git")) {
            clean = clean.substring(0, clean.length() - 4);
        }
        int slash = clean.lastIndexOf('/');
        return slash >= 0 ? clean.substring(slash + 1) : "cloned-repo";
    }

    public static List<String> tokenizeCommand(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return list;
        }
        Matcher m = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)").matcher(raw.trim());
        while (m.find()) {
            if (m.group(1) != null) {
                list.add(m.group(1));
            } else if (m.group(2) != null) {
                list.add(m.group(2));
            } else {
                list.add(m.group(3));
            }
        }
        return list;
    }

    public String getBuiltinGitHelp() {
        return """
                内置纯 Java Git 引擎 (Eclipse JGit 驱动，零外部 Git 依赖) 支持以下命令：
                  git init [dir]                     初始化本地 Git 仓库
                  git clone <url> [dir]              克隆远程仓库（自动附带 GitHub Token 鉴权）
                  git status [-s|--short]            查看工作区与暂存区文件状态
                  git diff [--staged]                查看工作区或暂存区的 Unified Diff
                  git add <file|.|--all>             暂存文件修改与删除
                  git commit -m "<message>" [-a]     创建本地 Commit 提交
                  git log [-n <N>] [--oneline]       查看提交历史记录
                  git show [commit]                  查看指定提交的详情与 Diff
                  git branch [-a|-d <name>|<new>]    查看、创建、删除或重命名分支
                  git checkout [-b] <branch>         切换或创建并切换分支
                  git switch [-c] <branch>           切换或新建分支
                  git reset [--soft|--mixed|--hard]  重置 HEAD 状态
                  git stash [push|pop|list|drop]     暂存或恢复未提交修改
                  git tag [-d] [tagname]             管理版本标签
                  git remote [-v|add <name> <url>]   查看或配置远程仓库地址
                  git push / git pull / git fetch    与远程 GitHub 仓库同步
                """;
    }
}
