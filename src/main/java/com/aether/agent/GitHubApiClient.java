package com.aether.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 基于 GitHub 官方 REST API（https://api.github.com）的全功能客户端封装。
 *
 * v2.0 升级覆盖：
 * - 账号与社交：获取当前登录用户、查看任意用户资料、关注/取消关注、Star/Unstar 仓库
 * - 仓库管理：创建仓库（支持公开/私有）、删除仓库、Fork 仓库、列出用户仓库、搜索仓库、读取 README 与任意文件
 * - 协作与研发流：查询/创建 Issue、发表 Issue/PR 评论、查询/创建 Pull Request (PR)、创建版本 Release、搜索代码 (Code Search)
 */
public class GitHubApiClient {

    private static final String API_BASE = "https://api.github.com";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String token;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public GitHubApiClient(String token) {
        this.token = token == null ? "" : token.trim();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    private Request.Builder baseRequest(String path) {
        Request.Builder builder = new Request.Builder()
                .url(API_BASE + path)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28");
        if (hasToken()) {
            builder.addHeader("Authorization", "Bearer " + token);
        }
        return builder;
    }

    public JsonNode getAuthenticatedUser() throws IOException {
        Request request = baseRequest("/user").get().build();
        return execute(request);
    }

    public JsonNode getUserProfile(String username) throws IOException {
        Request request = baseRequest("/users/" + username).get().build();
        return execute(request);
    }

    public JsonNode listUserRepositories(String username, int perPage) throws IOException {
        String path = (username == null || username.isBlank())
                ? "/user/repos?sort=updated&per_page=" + perPage
                : "/users/" + username + "/repos?sort=updated&per_page=" + perPage;
        Request request = baseRequest(path).get().build();
        return execute(request);
    }

    public JsonNode createRepository(String name, String description, boolean isPrivate) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", name);
        body.put("description", description == null ? "" : description);
        body.put("private", isPrivate);
        body.put("auto_init", false);

        Request request = baseRequest("/user/repos")
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .build();
        return execute(request);
    }

    public JsonNode forkRepository(String owner, String repo) throws IOException {
        Request request = baseRequest("/repos/" + owner + "/" + repo + "/forks")
                .post(RequestBody.create("{}", JSON))
                .build();
        return execute(request);
    }

    public boolean deleteRepository(String owner, String repo) throws IOException {
        Request request = baseRequest("/repos/" + owner + "/" + repo).delete().build();
        try (Response response = http.newCall(request).execute()) {
            return response.code() == 204;
        }
    }

    public JsonNode getRepository(String owner, String repo) throws IOException {
        Request request = baseRequest("/repos/" + owner + "/" + repo).get().build();
        return execute(request);
    }

    public String getRemoteFileContent(String owner, String repo, String path, String ref) throws IOException {
        String cleanPath = path.startsWith("/") ? path.substring(1) : path;
        String urlPath = "/repos/" + owner + "/" + repo + "/contents/" + cleanPath
                + (ref != null && !ref.isBlank() ? "?ref=" + ref : "");
        Request request = baseRequest(urlPath).get().build();
        JsonNode node = execute(request);
        String contentB64 = node.path("content").asText("").replace("\n", "").replace("\r", "");
        if (contentB64.isBlank()) {
            return node.toPrettyString();
        }
        return new String(Base64.getDecoder().decode(contentB64), StandardCharsets.UTF_8);
    }

    public JsonNode createOrUpdateFile(String owner, String repo, String path, String message,
                                       String content, String branch, String existingSha) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("message", message);
        body.put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        if (branch != null && !branch.isBlank()) {
            body.put("branch", branch);
        }
        if (existingSha != null && !existingSha.isBlank()) {
            body.put("sha", existingSha);
        }

        Request request = baseRequest("/repos/" + owner + "/" + repo + "/contents/" + path)
                .put(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .build();
        return execute(request);
    }

    public boolean starRepository(String owner, String repo) throws IOException {
        Request request = baseRequest("/user/starred/" + owner + "/" + repo)
                .put(RequestBody.create(new byte[0], null))
                .build();
        try (Response response = http.newCall(request).execute()) {
            return response.code() == 204;
        }
    }

    public boolean unstarRepository(String owner, String repo) throws IOException {
        Request request = baseRequest("/user/starred/" + owner + "/" + repo).delete().build();
        try (Response response = http.newCall(request).execute()) {
            return response.code() == 204;
        }
    }

    public boolean isStarring(String owner, String repo) throws IOException {
        Request request = baseRequest("/user/starred/" + owner + "/" + repo).get().build();
        try (Response response = http.newCall(request).execute()) {
            return response.code() == 204;
        }
    }

    public boolean followUser(String username) throws IOException {
        Request request = baseRequest("/user/following/" + username)
                .put(RequestBody.create(new byte[0], null))
                .build();
        try (Response response = http.newCall(request).execute()) {
            return response.code() == 204;
        }
    }

    public boolean unfollowUser(String username) throws IOException {
        Request request = baseRequest("/user/following/" + username).delete().build();
        try (Response response = http.newCall(request).execute()) {
            return response.code() == 204;
        }
    }

    public boolean isFollowing(String username) throws IOException {
        Request request = baseRequest("/user/following/" + username).get().build();
        try (Response response = http.newCall(request).execute()) {
            return response.code() == 204;
        }
    }

    public JsonNode searchRepositories(String query, String sort, String order, int perPage) throws IOException {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(API_BASE + "/search/repositories").newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("per_page", String.valueOf(Math.max(1, Math.min(perPage, 30))));
        if (sort != null && !sort.isBlank()) {
            urlBuilder.addQueryParameter("sort", sort);
        }
        if (order != null && !order.isBlank()) {
            urlBuilder.addQueryParameter("order", order);
        }

        Request.Builder reqBuilder = new Request.Builder()
                .url(urlBuilder.build())
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28");
        if (hasToken()) {
            reqBuilder.addHeader("Authorization", "Bearer " + token);
        }
        return execute(reqBuilder.get().build());
    }

    public JsonNode searchCode(String query, int perPage) throws IOException {
        HttpUrl url = HttpUrl.parse(API_BASE + "/search/code").newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("per_page", String.valueOf(Math.max(1, Math.min(perPage, 20))))
                .build();
        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28");
        if (hasToken()) {
            reqBuilder.addHeader("Authorization", "Bearer " + token);
        }
        return execute(reqBuilder.get().build());
    }

    public JsonNode listIssues(String owner, String repo, String state, int perPage) throws IOException {
        String st = (state == null || state.isBlank()) ? "open" : state;
        Request request = baseRequest("/repos/" + owner + "/" + repo + "/issues?state=" + st + "&per_page=" + perPage).get().build();
        return execute(request);
    }

    public JsonNode createIssue(String owner, String repo, String title, String body, List<String> labels) throws IOException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("title", title);
        payload.put("body", body == null ? "" : body);
        if (labels != null && !labels.isEmpty()) {
            ArrayNode arr = payload.putArray("labels");
            labels.forEach(arr::add);
        }
        Request request = baseRequest("/repos/" + owner + "/" + repo + "/issues")
                .post(RequestBody.create(mapper.writeValueAsString(payload), JSON))
                .build();
        return execute(request);
    }

    public JsonNode createIssueComment(String owner, String repo, int issueNumber, String body) throws IOException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("body", body);
        Request request = baseRequest("/repos/" + owner + "/" + repo + "/issues/" + issueNumber + "/comments")
                .post(RequestBody.create(mapper.writeValueAsString(payload), JSON))
                .build();
        return execute(request);
    }

    public JsonNode listPullRequests(String owner, String repo, String state, int perPage) throws IOException {
        String st = (state == null || state.isBlank()) ? "open" : state;
        Request request = baseRequest("/repos/" + owner + "/" + repo + "/pulls?state=" + st + "&per_page=" + perPage).get().build();
        return execute(request);
    }

    public JsonNode createPullRequest(String owner, String repo, String title, String body, String head, String base) throws IOException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("title", title);
        payload.put("body", body == null ? "" : body);
        payload.put("head", head);
        payload.put("base", (base == null || base.isBlank()) ? "main" : base);
        Request request = baseRequest("/repos/" + owner + "/" + repo + "/pulls")
                .post(RequestBody.create(mapper.writeValueAsString(payload), JSON))
                .build();
        return execute(request);
    }

    public JsonNode createRelease(String owner, String repo, String tagName, String name, String body, boolean prerelease) throws IOException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("tag_name", tagName);
        payload.put("name", (name == null || name.isBlank()) ? tagName : name);
        payload.put("body", body == null ? "" : body);
        payload.put("prerelease", prerelease);
        Request request = baseRequest("/repos/" + owner + "/" + repo + "/releases")
                .post(RequestBody.create(mapper.writeValueAsString(payload), JSON))
                .build();
        return execute(request);
    }

    public String getReadmeText(String owner, String repo) throws IOException {
        Request request = baseRequest("/repos/" + owner + "/" + repo + "/readme").get().build();
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                return "未找到 README (HTTP " + response.code() + ")";
            }
            JsonNode json = mapper.readTree(raw);
            String contentB64 = json.path("content").asText("");
            String cleaned = contentB64.replace("\n", "").replace("\r", "");
            if (cleaned.isBlank()) {
                return "";
            }
            byte[] decoded = Base64.getDecoder().decode(cleaned);
            return new String(decoded, StandardCharsets.UTF_8);
        }
    }

    public String getTokenForGitOverHttp() {
        return token;
    }

    private JsonNode execute(Request request) throws IOException {
        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("GitHub API 调用失败: HTTP " + response.code() + " - " + raw);
            }
            return raw.isBlank() ? mapper.createObjectNode() : mapper.readTree(raw);
        }
    }
}
