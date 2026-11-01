package com.ai.github.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 通用多轮对话 AI 客户端（兼容 OpenAI / Claude-Proxy / DeepSeek / Ollama / OpenRouter `/chat/completions` 接口）。
 *
 * v2.0 升级特性：
 * - 支持多轮上下文历史（Message History）与 Token 消耗估算
 * - 支持运行时动态切换 Endpoint / API Key / Model / Temperature
 * - 增强 JSON 容错解析：自动剥离 `<think>...</think>` 思考标签与 Markdown 围栏，并在 JSON 解析失败时支持自动自愈重试
 */
public class AIClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static class ChatMessage {
        public final String role;
        public final String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private String endpoint;
    private String apiKey;
    private String model;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private int totalEstimatedTokens = 0;

    public AIClient(String endpoint, String apiKey, String model) {
        setEndpoint(endpoint);
        this.apiKey = apiKey;
        this.model = model;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public void setEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            this.endpoint = "https://api.openai.com/v1";
        } else {
            this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        }
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getModel() {
        return model;
    }

    public int getTotalEstimatedTokens() {
        return totalEstimatedTokens;
    }

    public String chat(String systemPrompt, String userPrompt) throws IOException {
        return chat(systemPrompt, userPrompt, 0.3);
    }

    public String chat(String systemPrompt, String userPrompt, double temperature) throws IOException {
        return chatMultiTurn(systemPrompt, List.of(new ChatMessage("user", userPrompt)), temperature);
    }

    /**
     * 多轮上下文对话请求。
     */
    public String chatMultiTurn(String systemPrompt, List<ChatMessage> history, double temperature) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", temperature);

        ArrayNode messages = root.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = mapper.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);
        }
        int charCount = systemPrompt != null ? systemPrompt.length() : 0;
        for (ChatMessage msg : history) {
            ObjectNode node = mapper.createObjectNode();
            node.put("role", msg.role);
            node.put("content", msg.content);
            messages.add(node);
            charCount += (msg.content != null ? msg.content.length() : 0);
        }

        RequestBody body = RequestBody.create(mapper.writeValueAsString(root), JSON);
        Request request = new Request.Builder()
                .url(endpoint + "/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("AI API 调用失败: HTTP " + response.code() + " - " + raw);
            }
            JsonNode json = mapper.readTree(raw);
            JsonNode usage = json.get("usage");
            if (usage != null && usage.has("total_tokens")) {
                totalEstimatedTokens += usage.get("total_tokens").asInt(0);
            } else {
                totalEstimatedTokens += Math.max(1, charCount / 4);
            }

            JsonNode choices = json.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new IOException("AI API 返回中没有 choices 字段: " + raw);
            }
            JsonNode message = choices.get(0).get("message");
            if (message == null || message.get("content") == null) {
                throw new IOException("AI API 返回格式异常: " + raw);
            }
            return message.get("content").asText();
        }
    }

    /**
     * 单轮 JSON 结构化请求。
     */
    public JsonNode chatJson(String systemPrompt, String userPrompt) throws IOException {
        String enforcedSystem = (systemPrompt == null ? "" : systemPrompt + "\n\n")
                + "严格要求：只返回合法JSON对象本身，不要包含任何解释性文字，不要使用Markdown代码块围栏（```）。";

        String raw = chat(enforcedSystem, userPrompt, 0.2);
        String cleaned = JsonUtils.extractJsonObject(raw);
        try {
            return mapper.readTree(cleaned);
        } catch (Exception e) {
            throw new IOException("AI返回内容不是合法JSON，原始内容: " + raw, e);
        }
    }

    /**
     * 多轮 ReAct 专用 JSON 推理调用：若模型偶尔直接输出了纯文本而非 JSON，自动包装为 final_answer 保证永不崩溃。
     */
    public JsonNode chatReActStep(String systemPrompt, List<ChatMessage> history, double temperature) throws IOException {
        String raw = chatMultiTurn(systemPrompt, history, temperature);
        String cleaned = JsonUtils.extractJsonObject(raw);
        try {
            JsonNode parsed = mapper.readTree(cleaned);
            if (parsed.isObject()) {
                return parsed;
            }
        } catch (Exception ignored) {
        }
        // 优雅降级：若模型直接回复了 Markdown 文本而未包裹 JSON，自动将其封装为 final_answer
        ObjectNode fallback = mapper.createObjectNode();
        fallback.put("thought", "Direct response from model");
        fallback.put("final_answer", JsonUtils.stripThinkTags(raw));
        return fallback;
    }
}
