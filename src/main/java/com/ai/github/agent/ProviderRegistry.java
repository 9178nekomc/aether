package com.ai.github.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知名 AI 模型供应商与多协议注册表（ProviderRegistry）。
 *
 * 所有端点均经核实为最新官方稳定地址，支持 4 大主流协议：
 * 1. openai-chat       : OpenAI 兼容 `/chat/completions` 协议（行业通用标准）
 * 2. openai-responses  : OpenAI 新版 `/responses` 协议
 * 3. anthropic-messages: Anthropic 原生 `/messages` 协议（自动注入 ephemeral cache_control 断点）
 * 4. gemini-native     : Google Gemini 原生 `/models/{model}:generateContent` 协议
 */
public class ProviderRegistry {

    public static class ProviderSpec {
        public final String id;
        public final String displayName;
        public final String defaultEndpoint;
        public final String defaultProtocol;
        public final String defaultModel;
        public final List<String> recommendedModels;
        public final String envKeyName;
        public final String notes;

        public ProviderSpec(String id, String displayName, String defaultEndpoint, String defaultProtocol,
                            String defaultModel, List<String> recommendedModels, String envKeyName, String notes) {
            this.id = id;
            this.displayName = displayName;
            this.defaultEndpoint = defaultEndpoint;
            this.defaultProtocol = defaultProtocol;
            this.defaultModel = defaultModel;
            this.recommendedModels = recommendedModels;
            this.envKeyName = envKeyName;
            this.notes = notes;
        }
    }

    private static final Map<String, ProviderSpec> PROVIDERS = new LinkedHashMap<>();

    static {
        register(new ProviderSpec(
                "deepseek", "DeepSeek (深度求索官方)",
                "https://api.deepseek.com/v1", "openai-chat",
                "deepseek-chat",
                List.of("deepseek-chat", "deepseek-reasoner"),
                "DEEPSEEK_API_KEY",
                "原生支持自动前缀硬盘缓存 (prompt_cache_hit_tokens)"
        ));

        register(new ProviderSpec(
                "openai", "OpenAI (官方 API)",
                "https://api.openai.com/v1", "openai-chat",
                "gpt-4o",
                List.of("gpt-4o", "gpt-4.1", "gpt-4.1-mini", "o3-mini"),
                "OPENAI_API_KEY",
                "支持 openai-chat (/chat/completions) 与 openai-responses (/responses)，自动统计 cached_tokens"
        ));

        register(new ProviderSpec(
                "anthropic", "Anthropic (官方 Messages API)",
                "https://api.anthropic.com/v1", "anthropic-messages",
                "claude-sonnet-4-20250514",
                List.of("claude-sonnet-4-20250514", "claude-3-7-sonnet-latest", "claude-3-5-haiku-latest"),
                "ANTHROPIC_API_KEY",
                "使用原生 /v1/messages 协议，自动附带 cache_control 断点以命中 Prompt Cache"
        ));

        register(new ProviderSpec(
                "gemini", "Google Gemini (官方 API)",
                "https://generativelanguage.googleapis.com/v1beta/openai", "openai-chat",
                "gemini-2.5-pro",
                List.of("gemini-2.5-pro", "gemini-2.5-flash"),
                "GEMINI_API_KEY",
                "支持 OpenAI 兼容端点 (/v1beta/openai) 或切换为 gemini-native 原生协议"
        ));

        register(new ProviderSpec(
                "qwen", "阿里云百炼 DashScope (通义千问)",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "openai-chat",
                "qwen-plus",
                List.of("qwen-max", "qwen-plus", "qwen3-coder-plus", "qwen-turbo"),
                "DASHSCOPE_API_KEY",
                "阿里云官方 OpenAI 兼容端点"
        ));

        register(new ProviderSpec(
                "siliconflow", "SiliconFlow (硅基流动)",
                "https://api.siliconflow.cn/v1", "openai-chat",
                "deepseek-ai/DeepSeek-V3",
                List.of("deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1", "Qwen/Qwen2.5-Coder-32B-Instruct"),
                "SILICONFLOW_API_KEY",
                "聚合多种开源与商业模型的国内高速节点"
        ));

        register(new ProviderSpec(
                "zhipu", "智谱清言 BigModel (GLM)",
                "https://open.bigmodel.cn/api/paas/v4", "openai-chat",
                "glm-4-plus",
                List.of("glm-4-plus", "glm-4-air", "glm-4-flash", "glm-zero-preview"),
                "ZHIPU_API_KEY",
                "智谱官方 v4 OpenAI 兼容接口"
        ));

        register(new ProviderSpec(
                "moonshot", "Moonshot AI (月之暗面 Kimi)",
                "https://api.moonshot.cn/v1", "openai-chat",
                "kimi-k2-0711-preview",
                List.of("kimi-k2-0711-preview", "moonshot-v1-128k", "moonshot-v1-32k"),
                "MOONSHOT_API_KEY",
                "Kimi 官方 OpenAI 兼容接口"
        ));

        register(new ProviderSpec(
                "volcengine", "火山引擎方舟 (字节豆包 Ark)",
                "https://ark.cn-beijing.volces.com/api/v3", "openai-chat",
                "doubao-1-5-pro-32k-250115",
                List.of("doubao-1-5-pro-32k-250115", "deepseek-v3-250324"),
                "ARK_API_KEY",
                "火山方舟 v3 OpenAI 兼容端点"
        ));

        register(new ProviderSpec(
                "openrouter", "OpenRouter (全球模型聚合网关)",
                "https://openrouter.ai/api/v1", "openai-chat",
                "openai/gpt-4o",
                List.of("openai/gpt-4o", "anthropic/claude-3.7-sonnet", "google/gemini-2.5-pro", "deepseek/deepseek-chat"),
                "OPENROUTER_API_KEY",
                "单一 Key 调用全球主流模型，支持缓存穿透统计"
        ));

        register(new ProviderSpec(
                "xai", "xAI (Grok 官方 API)",
                "https://api.x.ai/v1", "openai-chat",
                "grok-3",
                List.of("grok-3", "grok-3-mini"),
                "XAI_API_KEY",
                "xAI 官方兼容端点"
        ));

        register(new ProviderSpec(
                "ollama", "Ollama (本地离线大模型)",
                "http://127.0.0.1:11434/v1", "openai-chat",
                "qwen2.5-coder:14b",
                List.of("qwen2.5-coder:14b", "deepseek-r1:14b", "llama3.3:latest"),
                "OLLAMA_API_KEY",
                "本地无需公网 API Key（随便填 local 即可）"
        ));

        register(new ProviderSpec(
                "custom", "Custom (自定义端点与任意网关)",
                "https://api.openai.com/v1", "openai-chat",
                "gpt-4o",
                List.of("gpt-4o"),
                "OPENAI_API_KEY",
                "自由指定任意 Base URL 与通信协议"
        ));
    }

    private static void register(ProviderSpec spec) {
        PROVIDERS.put(spec.id, spec);
    }

    public static Map<String, ProviderSpec> allProviders() {
        return PROVIDERS;
    }

    public static ProviderSpec get(String id) {
        if (id == null || id.isBlank()) {
            return PROVIDERS.get("deepseek");
        }
        return PROVIDERS.getOrDefault(id.trim().toLowerCase(), PROVIDERS.get("custom"));
    }

    public static List<String> supportedProtocols() {
        return List.of(
                "openai-chat",
                "openai-responses",
                "anthropic-messages",
                "gemini-native"
        );
    }
}
