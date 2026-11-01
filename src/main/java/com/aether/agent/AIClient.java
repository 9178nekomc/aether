package com.aether.agent;

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
 * 多协议 AI 客户端（支持 OpenAI Chat、OpenAI Responses、Anthropic Messages、Gemini Native 四大协议）。
 *
 * 核心能力：
 * 1. 精确采集每一步的 Token 消耗量与 Prompt Cache 命中统计：
 *    - OpenAI / OpenRouter: `usage.prompt_tokens_details.cached_tokens`
 *    - DeepSeek: `usage.prompt_cache_hit_tokens` & `usage.prompt_cache_miss_tokens`
 *    - Anthropic: `usage.cache_read_input_tokens` & `usage.cache_creation_input_tokens`
 *    - Gemini: `usageMetadata.cachedContentTokenCount`
 * 2. 在 `anthropic-messages` 协议下自动为系统提示词注入 `cache_control: {"type": "ephemeral"}`，最大化缓存命中率。
 */
public class AIClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static class ChatMessage {
        public String role;
        public String content;

        public ChatMessage() {
        }

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /**
     * 单步推理的完整结果与 Token / 缓存命中统计。
     */
    public static class StepUsage {
        public int inputTokens;
        public int outputTokens;
        public int cacheHitTokens;
        public int cacheWriteTokens;
        public long latencyMs;

        public int totalTokens() {
            return inputTokens + outputTokens;
        }

        public double cacheHitRatePercent() {
            if (inputTokens <= 0) {
                return 0.0;
            }
            return Math.min(100.0, (cacheHitTokens * 100.0) / inputTokens);
        }

        public String formatCompact() {
            return String.format("in: %,d tok (⚡cache: %,d, %.0f%%) │ out: %,d tok │ total: %,d tok │ %dms",
                    inputTokens, cacheHitTokens, cacheHitRatePercent(), outputTokens, totalTokens(), latencyMs);
        }
    }

    public static class ReActStepResult {
        public final JsonNode json;
        public final StepUsage usage;

        public ReActStepResult(JsonNode json, StepUsage usage) {
            this.json = json;
            this.usage = usage;
        }
    }

    private String endpoint;
    private String apiKey;
    private String model;
    private String protocol;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    // 会话级累计 Token 统计
    private int cumulativeInputTokens = 0;
    private int cumulativeOutputTokens = 0;
    private int cumulativeCacheHitTokens = 0;

    public AIClient(String endpoint, String apiKey, String model, String protocol) {
        setEndpoint(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "deepseek-chat" : model.trim();
        this.protocol = protocol == null || protocol.isBlank() ? "openai-chat" : protocol.trim().toLowerCase();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public AIClient(String endpoint, String apiKey, String model) {
        this(endpoint, apiKey, model, "openai-chat");
    }

    public void setEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            this.endpoint = "https://api.deepseek.com/v1";
        } else {
            this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint.trim();
        }
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setProtocol(String protocol) {
        this.protocol = (protocol == null || protocol.isBlank()) ? "openai-chat" : protocol.trim().toLowerCase();
    }

    public String getModel() { return model; }
    public String getProtocol() { return protocol; }
    public String getEndpoint() { return endpoint; }

    public int getCumulativeInputTokens() { return cumulativeInputTokens; }
    public int getCumulativeOutputTokens() { return cumulativeOutputTokens; }
    public int getCumulativeCacheHitTokens() { return cumulativeCacheHitTokens; }
    public int getTotalEstimatedTokens() { return cumulativeInputTokens + cumulativeOutputTokens; }

    public double getCumulativeCacheHitRate() {
        if (cumulativeInputTokens <= 0) return 0.0;
        return Math.min(100.0, (cumulativeCacheHitTokens * 100.0) / cumulativeInputTokens);
    }

    public void restoreTokenStats(int inTok, int outTok, int cacheTok) {
        this.cumulativeInputTokens = inTok;
        this.cumulativeOutputTokens = outTok;
        this.cumulativeCacheHitTokens = cacheTok;
    }

    public void resetTokenStats() {
        this.cumulativeInputTokens = 0;
        this.cumulativeOutputTokens = 0;
        this.cumulativeCacheHitTokens = 0;
    }

    public String chat(String systemPrompt, String userPrompt) throws IOException {
        return chat(systemPrompt, userPrompt, 0.2);
    }

    public String chat(String systemPrompt, String userPrompt, double temperature) throws IOException {
        StepUsage usage = new StepUsage();
        return executeProtocolCall(systemPrompt, List.of(new ChatMessage("user", userPrompt)), temperature, usage);
    }

    public JsonNode chatJson(String systemPrompt, String userPrompt) throws IOException {
        String enforcedSystem = (systemPrompt == null ? "" : systemPrompt + "\n\n")
                + "Return ONLY a valid JSON object without markdown fences or extra commentary.";
        String raw = chat(enforcedSystem, userPrompt, 0.15);
        String cleaned = JsonUtils.extractJsonObject(raw);
        try {
            return mapper.readTree(cleaned);
        } catch (Exception e) {
            throw new IOException("Invalid JSON returned by model: " + raw, e);
        }
    }

    /**
     * 执行单步 ReAct 推理并返回结构化 JSON 与精确的 Token + Cache 命中统计。
     */
    public ReActStepResult chatReActStepWithUsage(String systemPrompt, List<ChatMessage> history, double temperature) throws IOException {
        StepUsage usage = new StepUsage();
        long t0 = System.currentTimeMillis();
        String raw = executeProtocolCall(systemPrompt, history, temperature, usage);
        usage.latencyMs = System.currentTimeMillis() - t0;

        cumulativeInputTokens += usage.inputTokens;
        cumulativeOutputTokens += usage.outputTokens;
        cumulativeCacheHitTokens += usage.cacheHitTokens;

        String cleaned = JsonUtils.extractJsonObject(raw);
        try {
            JsonNode parsed = mapper.readTree(cleaned);
            if (parsed.isObject()) {
                return new ReActStepResult(parsed, usage);
            }
        } catch (Exception ignored) {
        }

        ObjectNode fallback = mapper.createObjectNode();
        fallback.put("thought", "Synthesizing direct response");
        fallback.put("final_answer", JsonUtils.stripThinkTags(raw));
        return new ReActStepResult(fallback, usage);
    }

    private String executeProtocolCall(String systemPrompt, List<ChatMessage> history, double temperature, StepUsage usage) throws IOException {
        return switch (protocol) {
            case "anthropic-messages" -> callAnthropicMessages(systemPrompt, history, temperature, usage);
            case "openai-responses" -> callOpenAiResponses(systemPrompt, history, temperature, usage);
            case "gemini-native" -> callGeminiNative(systemPrompt, history, temperature, usage);
            default -> callOpenAiChatCompletions(systemPrompt, history, temperature, usage);
        };
    }

    /**
     * 协议 1：OpenAI 兼容 `/chat/completions`（支持 OpenAI, DeepSeek, Qwen, SiliconFlow, Zhipu, Moonshot, OpenRouter, Ollama 等）。
     */
    private String callOpenAiChatCompletions(String systemPrompt, List<ChatMessage> history, double temperature, StepUsage usage) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", temperature);

        ArrayNode messages = root.putArray("messages");
        int estInputChars = 0;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sys = mapper.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            messages.add(sys);
            estInputChars += systemPrompt.length();
        }
        for (ChatMessage msg : history) {
            ObjectNode node = mapper.createObjectNode();
            node.put("role", msg.role);
            node.put("content", msg.content);
            messages.add(node);
            estInputChars += (msg.content != null ? msg.content.length() : 0);
        }

        String url = endpoint.endsWith("/chat/completions") ? endpoint : endpoint + "/chat/completions";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(root), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("API Error (HTTP " + response.code() + " via " + protocol + "): " + raw);
            }
            JsonNode json = mapper.readTree(raw);
            JsonNode choices = json.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new IOException("No choices field in response: " + raw);
            }
            String content = choices.get(0).path("message").path("content").asText("");
            parseOpenAiCompatibleUsage(json.get("usage"), estInputChars, content.length(), usage);
            return content;
        }
    }

    /**
     * 协议 2：OpenAI `/responses` API。
     */
    private String callOpenAiResponses(String systemPrompt, List<ChatMessage> history, double temperature, StepUsage usage) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("temperature", temperature);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            root.put("instructions", systemPrompt);
        }
        ArrayNode inputArr = root.putArray("input");
        int estInputChars = systemPrompt != null ? systemPrompt.length() : 0;
        for (ChatMessage msg : history) {
            ObjectNode item = mapper.createObjectNode();
            item.put("role", msg.role);
            item.put("content", msg.content);
            inputArr.add(item);
            estInputChars += (msg.content != null ? msg.content.length() : 0);
        }

        String url = endpoint.endsWith("/responses") ? endpoint : endpoint + "/responses";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(root), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Responses API Error (HTTP " + response.code() + "): " + raw);
            }
            JsonNode json = mapper.readTree(raw);
            String outText = json.path("output_text").asText("");
            if (outText.isBlank() && json.has("output")) {
                for (JsonNode item : json.path("output")) {
                    for (JsonNode c : item.path("content")) {
                        if (c.has("text")) {
                            outText += c.path("text").asText("");
                        }
                    }
                }
            }
            parseOpenAiCompatibleUsage(json.get("usage"), estInputChars, outText.length(), usage);
            return outText;
        }
    }

    /**
     * 协议 3：Anthropic 原生 `/v1/messages` 协议（含 ephemeral Prompt Caching 支持）。
     */
    private String callAnthropicMessages(String systemPrompt, List<ChatMessage> history, double temperature, StepUsage usage) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 8192);
        root.put("temperature", temperature);

        int estInputChars = 0;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ArrayNode sysBlocks = root.putArray("system");
            ObjectNode sysBlock = mapper.createObjectNode();
            sysBlock.put("type", "text");
            sysBlock.put("text", systemPrompt);
            ObjectNode cacheCtrl = sysBlock.putObject("cache_control");
            cacheCtrl.put("type", "ephemeral");
            sysBlocks.add(sysBlock);
            estInputChars += systemPrompt.length();
        }

        ArrayNode messages = root.putArray("messages");
        for (ChatMessage msg : history) {
            ObjectNode m = mapper.createObjectNode();
            m.put("role", "assistant".equals(msg.role) ? "assistant" : "user");
            m.put("content", msg.content);
            messages.add(m);
            estInputChars += (msg.content != null ? msg.content.length() : 0);
        }

        String url = endpoint.endsWith("/messages") ? endpoint : endpoint + "/messages";
        Request request = new Request.Builder()
                .url(url)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(root), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Anthropic API Error (HTTP " + response.code() + "): " + raw);
            }
            JsonNode json = mapper.readTree(raw);
            StringBuilder textOut = new StringBuilder();
            JsonNode contentArr = json.path("content");
            if (contentArr.isArray()) {
                for (JsonNode block : contentArr) {
                    if ("text".equals(block.path("type").asText())) {
                        textOut.append(block.path("text").asText(""));
                    }
                }
            }
            JsonNode u = json.get("usage");
            if (u != null) {
                int uncachedIn = u.path("input_tokens").asInt(0);
                int cacheRead = u.path("cache_read_input_tokens").asInt(0);
                int cacheCreate = u.path("cache_creation_input_tokens").asInt(0);
                usage.inputTokens = uncachedIn + cacheRead + cacheCreate;
                usage.outputTokens = u.path("output_tokens").asInt(0);
                usage.cacheHitTokens = cacheRead;
                usage.cacheWriteTokens = cacheCreate;
            } else {
                usage.inputTokens = Math.max(1, estInputChars / 4);
                usage.outputTokens = Math.max(1, textOut.length() / 4);
            }
            return textOut.toString();
        }
    }

    /**
     * 协议 4：Google Gemini 原生 `/models/{model}:generateContent` 协议。
     */
    private String callGeminiNative(String systemPrompt, List<ChatMessage> history, double temperature, StepUsage usage) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        int estInputChars = 0;
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sysInst = root.putObject("systemInstruction");
            ArrayNode parts = sysInst.putArray("parts");
            parts.addObject().put("text", systemPrompt);
            estInputChars += systemPrompt.length();
        }

        ArrayNode contents = root.putArray("contents");
        for (ChatMessage msg : history) {
            ObjectNode c = mapper.createObjectNode();
            c.put("role", "assistant".equals(msg.role) ? "model" : "user");
            ArrayNode parts = c.putArray("parts");
            parts.addObject().put("text", msg.content);
            contents.add(c);
            estInputChars += (msg.content != null ? msg.content.length() : 0);
        }

        ObjectNode genCfg = root.putObject("generationConfig");
        genCfg.put("temperature", temperature);

        String base = endpoint.replace("/openai", "");
        String url = base + "/models/" + model + ":generateContent?key=" + apiKey;
        Request request = new Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(mapper.writeValueAsString(root), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("Gemini Native API Error (HTTP " + response.code() + "): " + raw);
            }
            JsonNode json = mapper.readTree(raw);
            String text = json.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
            JsonNode meta = json.get("usageMetadata");
            if (meta != null) {
                usage.inputTokens = meta.path("promptTokenCount").asInt(Math.max(1, estInputChars / 4));
                usage.outputTokens = meta.path("candidatesTokenCount").asInt(Math.max(1, text.length() / 4));
                usage.cacheHitTokens = meta.path("cachedContentTokenCount").asInt(0);
            } else {
                usage.inputTokens = Math.max(1, estInputChars / 4);
                usage.outputTokens = Math.max(1, text.length() / 4);
            }
            return text;
        }
    }

    private void parseOpenAiCompatibleUsage(JsonNode u, int estInChars, int estOutChars, StepUsage usage) {
        if (u == null || u.isNull()) {
            usage.inputTokens = Math.max(1, estInChars / 4);
            usage.outputTokens = Math.max(1, estOutChars / 4);
            return;
        }
        usage.inputTokens = u.path("prompt_tokens").asInt(u.path("input_tokens").asInt(Math.max(1, estInChars / 4)));
        usage.outputTokens = u.path("completion_tokens").asInt(u.path("output_tokens").asInt(Math.max(1, estOutChars / 4)));

        // 兼容 OpenAI / OpenRouter `prompt_tokens_details.cached_tokens` 与 DeepSeek `prompt_cache_hit_tokens`
        int cached = u.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        if (cached == 0) {
            cached = u.path("input_tokens_details").path("cached_tokens").asInt(0);
        }
        if (cached == 0) {
            cached = u.path("prompt_cache_hit_tokens").asInt(0);
        }
        if (cached == 0) {
            cached = u.path("cache_read_input_tokens").asInt(0);
        }
        usage.cacheHitTokens = cached;
    }
}
