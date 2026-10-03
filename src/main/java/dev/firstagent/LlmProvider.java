package dev.firstagent;

import java.util.List;

/**
 * LLM 接入的薄 seam —— 把"Agent Loop"和"具体模型/厂商"解耦。 这就是那批生产项目唯一引入的东西。
 * 由 AO：失当回复 {@link AssistantReply} 是 provider 层把厂商原始产物归一化后的结果。
 */
public interface LlmProvider {
    /**
     * 给定完整消息历史，返回模型答复。 history 即 "model-visible means logged" 的派生输入。
     * 实现必须是纯函数式的：只读 history，不修改它。
     */
    AssistantReply chat(List<Message> history);
}