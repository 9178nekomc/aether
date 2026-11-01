package com.aether.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 会话进度自动保存与历史对话恢复管理器（SessionManager）。
 *
 * 功能特性：
 * 1. 每完成一步推理或一轮对话，自动将完整对话历史、树型操作日志、累计 Token 消耗与缓存命中数据保存到 `.agent-sessions/<sessionId>.json`。
 * 2. 支持 `/sessions` 查看所有历史会话列表（按更新时间倒序，显示会话标题、轮数、Token 消耗与缓存命中率）。
 * 3. 支持 `/resume [序号或SessionID]` 或启动参数 `--resume` 一键恢复之前的任意对话进度。
 */
public class SessionManager {

    public static class SessionSummary {
        public String sessionId;
        public String title;
        public String createdAt;
        public String updatedAt;
        public String provider;
        public String model;
        public int turnCount;
        public int inputTokens;
        public int outputTokens;
        public int cacheHitTokens;
        public Path filePath;
    }

    public static class RestoredSession {
        public String sessionId;
        public String title;
        public String createdAt;
        public int turnCount;
        public int inputTokens;
        public int outputTokens;
        public int cacheHitTokens;
        public List<AIClient.ChatMessage> messages = new ArrayList<>();
        public List<String> treeLogSummary = new ArrayList<>();
    }

    private final Path sessionsDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private String currentSessionId;
    private String currentTitle = "New Session";
    private String createdAt;
    private final List<String> executionTreeLogs = new ArrayList<>();

    public SessionManager(String dirPath) {
        this.sessionsDir = Paths.get(dirPath == null || dirPath.isBlank() ? ".agent-sessions" : dirPath)
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException ignored) {
        }
        startNewSession();
    }

    public final void startNewSession() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        this.currentSessionId = "sess_" + ts;
        this.createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        this.currentTitle = "未命名会话 (" + ts + ")";
        this.executionTreeLogs.clear();
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public String getCurrentTitle() {
        return currentTitle;
    }

    public void recordTreeStep(String stepSummary) {
        executionTreeLogs.add(stepSummary);
    }

    public List<String> getExecutionTreeLogs() {
        return executionTreeLogs;
    }

    /**
     * 每一步/每一轮完成后自动保存当前会话进度。
     */
    public void saveProgress(ConfigManager config, AIClient aiClient, int turnCount, List<AIClient.ChatMessage> history) {
        try {
            Files.createDirectories(sessionsDir);
            // 自动提取第一条用户消息作为会话标题
            for (AIClient.ChatMessage m : history) {
                if ("user".equals(m.role) && m.content != null && !m.content.startsWith("TOOL_EXECUTION_RESULTS:")) {
                    String clean = m.content.replaceAll("\\s+", " ").trim();
                    this.currentTitle = clean.length() > 42 ? clean.substring(0, 42) + "..." : clean;
                    break;
                }
            }

            ObjectNode root = mapper.createObjectNode();
            root.put("sessionId", currentSessionId);
            root.put("title", currentTitle);
            root.put("createdAt", createdAt);
            root.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            root.put("provider", config.getProviderId());
            root.put("protocol", config.getProtocol());
            root.put("model", config.getOpenaiModel());
            root.put("turnCount", turnCount);
            root.put("inputTokens", aiClient.getCumulativeInputTokens());
            root.put("outputTokens", aiClient.getCumulativeOutputTokens());
            root.put("cacheHitTokens", aiClient.getCumulativeCacheHitTokens());

            ArrayNode msgArr = root.putArray("messages");
            for (AIClient.ChatMessage m : history) {
                ObjectNode item = mapper.createObjectNode();
                item.put("role", m.role);
                item.put("content", m.content);
                msgArr.add(item);
            }

            ArrayNode treeArr = root.putArray("treeLogs");
            for (String log : executionTreeLogs) {
                treeArr.add(log);
            }

            String jsonText = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Files.writeString(sessionsDir.resolve(currentSessionId + ".json"), jsonText, StandardCharsets.UTF_8);
            Files.writeString(sessionsDir.resolve("latest.json"), jsonText, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    /**
     * 列出所有已保存的历史会话（按最后更新时间倒序排列）。
     */
    public List<SessionSummary> listSavedSessions() {
        List<SessionSummary> list = new ArrayList<>();
        if (!Files.exists(sessionsDir)) {
            return list;
        }
        try (Stream<Path> stream = Files.list(sessionsDir)) {
            List<Path> files = stream
                    .filter(p -> p.getFileName().toString().startsWith("sess_") && p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
            for (Path f : files) {
                try {
                    JsonNode node = mapper.readTree(Files.readString(f, StandardCharsets.UTF_8));
                    SessionSummary s = new SessionSummary();
                    s.sessionId = node.path("sessionId").asText("");
                    s.title = node.path("title").asText("未命名会话");
                    s.createdAt = node.path("createdAt").asText("");
                    s.updatedAt = node.path("updatedAt").asText("");
                    s.provider = node.path("provider").asText("");
                    s.model = node.path("model").asText("");
                    s.turnCount = node.path("turnCount").asInt(0);
                    s.inputTokens = node.path("inputTokens").asInt(0);
                    s.outputTokens = node.path("outputTokens").asInt(0);
                    s.cacheHitTokens = node.path("cacheHitTokens").asInt(0);
                    s.filePath = f;
                    list.add(s);
                } catch (Exception ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        return list;
    }

    /**
     * 按索引号（1-based）、Session ID 或 `"latest"` 恢复历史会话。
     */
    public RestoredSession loadSession(String selector) throws IOException {
        Path targetFile = null;
        if (selector == null || selector.isBlank() || selector.equalsIgnoreCase("latest")) {
            Path latest = sessionsDir.resolve("latest.json");
            if (Files.exists(latest)) {
                targetFile = latest;
            }
        } else {
            List<SessionSummary> all = listSavedSessions();
            try {
                int idx = Integer.parseInt(selector.trim());
                if (idx >= 1 && idx <= all.size()) {
                    targetFile = all.get(idx - 1).filePath;
                }
            } catch (NumberFormatException ignored) {
            }
            if (targetFile == null) {
                for (SessionSummary s : all) {
                    if (s.sessionId.equalsIgnoreCase(selector.trim()) || s.sessionId.contains(selector.trim())) {
                        targetFile = s.filePath;
                        break;
                    }
                }
            }
        }

        if (targetFile == null || !Files.exists(targetFile)) {
            throw new IOException("未找到匹配的历史会话: " + selector);
        }

        JsonNode root = mapper.readTree(Files.readString(targetFile, StandardCharsets.UTF_8));
        RestoredSession rs = new RestoredSession();
        rs.sessionId = root.path("sessionId").asText("sess_restored");
        rs.title = root.path("title").asText("Restored Session");
        rs.createdAt = root.path("createdAt").asText("");
        rs.turnCount = root.path("turnCount").asInt(0);
        rs.inputTokens = root.path("inputTokens").asInt(0);
        rs.outputTokens = root.path("outputTokens").asInt(0);
        rs.cacheHitTokens = root.path("cacheHitTokens").asInt(0);

        JsonNode msgArr = root.get("messages");
        if (msgArr != null && msgArr.isArray()) {
            for (JsonNode m : msgArr) {
                rs.messages.add(new AIClient.ChatMessage(
                        m.path("role").asText("user"),
                        m.path("content").asText("")
                ));
            }
        }

        JsonNode treeArr = root.get("treeLogs");
        if (treeArr != null && treeArr.isArray()) {
            for (JsonNode t : treeArr) {
                rs.treeLogSummary.add(t.asText());
            }
        }

        // 将当前活跃会话切换为已恢复的会话
        this.currentSessionId = rs.sessionId;
        this.currentTitle = rs.title;
        this.createdAt = rs.createdAt;
        this.executionTreeLogs.clear();
        this.executionTreeLogs.addAll(rs.treeLogSummary);

        return rs;
    }
}
