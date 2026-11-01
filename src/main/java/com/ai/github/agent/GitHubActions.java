package com.ai.github.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jgit.api.Git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * GitHub 高阶业务动作与格式化输出编排器（GitHubActions v2.0）。
 *
 * 组合底层的 GitHubApiClient / GitOperations (内置 JGit) / ProjectGenerator，
 * 提供：
 * - AI 一键生成完整项目 + 本地内置 JGit 初始化提交 + 远端创建 GitHub 仓库并推送
 * - 搜索仓库、查看热门项目、Star/Unstar、Follow/Unfollow、查看用户画像
 * - 仓库列表、Issue 列表/创建/评论、PR 列表/创建、Release 发布、代码搜索
 */
public class GitHubActions {

    private final GitHubApiClient github;
    private final GitOperations gitOps;
    private final ProjectGenerator projectGenerator;
    private String githubUsername;
    private Path workspaceDir;

    public GitHubActions(GitHubApiClient github, GitOperations gitOps, ProjectGenerator projectGenerator,
                         String githubUsername, String workspaceDir) {
        this.github = github;
        this.gitOps = gitOps;
        this.projectGenerator = projectGenerator;
        this.githubUsername = githubUsername;
        this.workspaceDir = Paths.get(workspaceDir);
    }

    public void setGithubUsername(String githubUsername) {
        this.githubUsername = githubUsername;
    }

    public void setWorkspaceDir(String workspaceDir) {
        this.workspaceDir = Paths.get(workspaceDir);
    }

    public String createProjectAndPublish(String taskDescription) throws Exception {
        ProjectGenerator.GeneratedProject project = projectGenerator.generate(taskDescription, workspaceDir);

        File dir = project.directory.toFile();
        try (Git git = gitOps.initRepo(dir)) {
            gitOps.addAll(git);
            gitOps.commit(git, "feat: initial AI generated project - " + project.description,
                    githubUsername, githubUsername + "@users.noreply.github.com");
            gitOps.ensureMainBranch(git);

            if (!github.hasToken()) {
                return "已在本地工作区生成项目并完成内置 Git 仓库初始化与首次 Commit: "
                        + dir.getAbsolutePath() + " (未配置 GitHub Token，跳过远端发布)";
            }

            JsonNode repoResult = github.createRepository(project.repoName, project.description, false);
            String cloneUrl = repoResult.path("clone_url").asText();
            String htmlUrl = repoResult.path("html_url").asText();

            gitOps.addRemote(git, "origin", cloneUrl);
            gitOps.push(git, githubUsername, github.getTokenForGitOverHttp());
            return "项目已生成并通过内置 JGit 推送至 GitHub！\n本地路径: " + dir.getAbsolutePath() + "\n仓库地址: " + htmlUrl;
        }
    }

    public String starProjectByQuery(String query) throws IOException {
        JsonNode result = github.searchRepositories(query, "stars", "desc", 5);
        JsonNode items = result.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return "未找到匹配 \"" + query + "\" 的仓库。";
        }

        JsonNode top = items.get(0);
        String fullName = top.path("full_name").asText();
        String[] parts = fullName.split("/", 2);
        github.starRepository(parts[0], parts[1]);

        String url = top.path("html_url").asText();
        return "⭐ 已成功 Star 项目: " + fullName + " (" + url + ")";
    }

    public String starDirect(String owner, String repo) throws IOException {
        github.starRepository(owner, repo);
        return "⭐ 已成功 Star 仓库: " + owner + "/" + repo + " (https://github.com/" + owner + "/" + repo + ")";
    }

    public String unstarDirect(String owner, String repo) throws IOException {
        github.unstarRepository(owner, repo);
        return "已取消 Star 仓库: " + owner + "/" + repo;
    }

    public String followUserByName(String username) throws IOException {
        github.followUser(username);
        return "👤 已成功关注用户: @" + username + " (https://github.com/" + username + ")";
    }

    public String unfollowUserByName(String username) throws IOException {
        github.unfollowUser(username);
        return "已取消关注用户: @" + username;
    }

    public String browseAndDiscover(String language) throws IOException {
        String query = "stars:>500" + (language == null || language.isBlank() ? "" : " language:" + language);
        return searchRepositoriesFormatted(query, 10);
    }

    public String searchRepositoriesFormatted(String query, int limit) throws IOException {
        JsonNode result = github.searchRepositories(query, "stars", "desc", limit);
        JsonNode items = result.path("items");
        if (!items.isArray() || items.isEmpty()) {
            return "未搜索到匹配 `" + query + "` 的 GitHub 仓库。";
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode item : items) {
            lines.add(String.format("- %s (⭐ %d | 🍴 %d | %s) - %s [%s]",
                    item.path("full_name").asText(),
                    item.path("stargazers_count").asInt(),
                    item.path("forks_count").asInt(),
                    item.path("language").asText("N/A"),
                    item.path("description").asText("无描述"),
                    item.path("html_url").asText()));
        }
        return "找到 " + lines.size() + " 个仓库:\n" + String.join("\n", lines);
    }

    public String listUserReposFormatted(String username, int limit) throws IOException {
        JsonNode arr = github.listUserRepositories(username, limit);
        if (!arr.isArray() || arr.isEmpty()) {
            return "未找到仓库记录。";
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode repo : arr) {
            lines.add(String.format("- %s [%s] (⭐ %d | %s) - %s (%s)",
                    repo.path("full_name").asText(),
                    repo.path("private").asBoolean() ? "Private" : "Public",
                    repo.path("stargazers_count").asInt(),
                    repo.path("language").asText("N/A"),
                    repo.path("description").asText(""),
                    repo.path("html_url").asText()));
        }
        return "仓库列表 (" + lines.size() + "):\n" + String.join("\n", lines);
    }

    public String listIssuesFormatted(String owner, String repo, String state, int limit) throws IOException {
        JsonNode arr = github.listIssues(owner, repo, state, limit);
        if (!arr.isArray() || arr.isEmpty()) {
            return owner + "/" + repo + " 当前没有状态为 " + state + " 的 Issue。";
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode item : arr) {
            if (item.has("pull_request")) {
                continue; // 过滤掉 PR
            }
            lines.add(String.format("#%d [%s] %s (by @%s) - %s",
                    item.path("number").asInt(),
                    item.path("state").asText(),
                    item.path("title").asText(),
                    item.path("user").path("login").asText(),
                    item.path("html_url").asText()));
        }
        return lines.isEmpty() ? "未找到 Issue。" : String.join("\n", lines);
    }

    public String listPullRequestsFormatted(String owner, String repo, String state, int limit) throws IOException {
        JsonNode arr = github.listPullRequests(owner, repo, state, limit);
        if (!arr.isArray() || arr.isEmpty()) {
            return owner + "/" + repo + " 当前没有状态为 " + state + " 的 Pull Request。";
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode pr : arr) {
            lines.add(String.format("PR #%d [%s] %s (%s -> %s, by @%s) - %s",
                    pr.path("number").asInt(),
                    pr.path("state").asText(),
                    pr.path("title").asText(),
                    pr.path("head").path("label").asText(),
                    pr.path("base").path("label").asText(),
                    pr.path("user").path("login").asText(),
                    pr.path("html_url").asText()));
        }
        return String.join("\n", lines);
    }

    public String formatUserProfile(String username) throws IOException {
        JsonNode u = github.getUserProfile(username);
        return String.format("""
                GitHub 用户资料: @%s (%s)
                - 简介 (Bio): %s
                - 公司/地区: %s / %s
                - 公开仓库: %d | Followers: %d | Following: %d
                - 主页: %s
                """,
                u.path("login").asText(),
                u.path("name").asText(""),
                u.path("bio").asText("无"),
                u.path("company").asText("无"),
                u.path("location").asText("无"),
                u.path("public_repos").asInt(),
                u.path("followers").asInt(),
                u.path("following").asInt(),
                u.path("html_url").asText());
    }
}
