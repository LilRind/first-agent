package dev.firstagent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.firstagent.AssistantReply;
import dev.firstagent.LlmProvider;
import dev.firstagent.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划器 —— 一次性生成行动计划（JSON 数组），lenient 解析 + 重试。
 *
 * v1：静态计划，不做动态重规划（改善项）。
 * 解析：截取首个 '[' 到 末个 ']' 区间解析为字符串数组（兼容模型在文本里夹 JSON 的情况），
 *      失败重试最多 maxRetries 次，仍失败返回空列表（调用方据此终止）。
 */
public final class Planner {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String PROMPT = """
            你是一个顶级的AI规划专家。请将用户问题分解成一个由多个简单步骤组成的行动计划。
            每个步骤是一个独立的、可执行的子任务，按逻辑顺序排列。
            输出必须是一个 JSON 数组，例如：["步骤1", "步骤2", "步骤3"]
            只输出 JSON 数组本身，不要任何解释。

            问题: %s""";

    private final LlmProvider llm;
    private final int maxRetries;

    public Planner(LlmProvider llm) { this(llm, 2); }
    public Planner(LlmProvider llm, int maxRetries) { this.llm = llm; this.maxRetries = maxRetries; }

    public List<String> plan(String question) {
        for (int i = 0; i <= maxRetries; i++) {
            AssistantReply r = llm.chat(List.of(Message.user(PROMPT.formatted(question))));
            String text = r.text();
            if (text == null) continue;
            List<String> plan = parseList(text);
            if (!plan.isEmpty()) return plan;
        }
        return List.of();
    }

    /** lenient：截取首个 '[' 到 末个 ']' 区间解析为 JSON 字符串数组。 */
    static List<String> parseList(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        try {
            var arr = M.readTree(text.substring(start, end + 1));
            if (!arr.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (var node : arr) {
                if (node.isTextual() && !node.asText().isBlank()) out.add(node.asText().trim());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}