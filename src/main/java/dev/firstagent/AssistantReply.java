package dev.firstagent;

import java.util.List;

/**
 * LLM provider 的一次答复。
 *
 * text         — 说给用户的话（无工具调用时就是最终回答；有工具调用时通常为空）
 * toolCalls    — 本轮调用的工具（空 = 结束，不再进入工具批）
 * stopReason   — 模型终止原因，如 end_turn / tool_use / max_tokens
 */
public record AssistantReply(String text, List<ToolCall> toolCalls, String stopReason) {
    public AssistantReply {
        toolCalls = toolCalls == null ? List.of() : toolCalls;
    }

    public boolean hasToolCalls() { return !toolCalls.isEmpty(); }
}