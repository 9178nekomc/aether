package com.ai.github.agent;

/**
 * JSON 与模型输出文本清洗工具（v2.0 增强版）。
 *
 * 支持：
 * 1. 自动剥离 DeepSeek-R1 / QwQ 等推理模型的 `<think>...</think>` 思维链标签
 * 2. 自动剥离 Markdown 代码块围栏（```json ... ```）
 * 3. 从夹杂前后缀说明的文本中精准提取最外层 `{ ... }` JSON 对象
 */
public final class JsonUtils {

    private JsonUtils() {
    }

    public static String stripThinkTags(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }

    public static String stripCodeFences(String text) {
        if (text == null) {
            return "";
        }
        String t = stripThinkTags(text).trim();

        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline != -1) {
                t = t.substring(firstNewline + 1);
            } else {
                t = t.substring(3);
            }
            int lastFence = t.lastIndexOf("```");
            if (lastFence != -1) {
                t = t.substring(0, lastFence);
            }
        }

        return t.trim();
    }

    /**
     * 从模型输出中提取首个完整的 `{ ... }` JSON 对象文本。
     */
    public static String extractJsonObject(String raw) {
        String cleaned = stripCodeFences(raw);
        int firstBrace = cleaned.indexOf('{');
        int lastBrace = cleaned.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            return cleaned.substring(firstBrace, lastBrace + 1).trim();
        }
        return cleaned;
    }
}
