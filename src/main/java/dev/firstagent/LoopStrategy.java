package dev.firstagent;

import java.util.List;

/**
 * 可插拔策略钩子 —— 对齐 pi 的四个 config 钩子（prepareNextTurn / prepareRequest / finishTurn /
 * getSteeringMessages / getFollowUpMessages）。这是"引擎纯循环"与"策略决策"的解耦点。
 *
 * MVP 简化：
 * - prepareNextTurn / prepareRequest 先留口（void no-op），对接后续压缩/换模型/路由模块；
 * - finishTurn 返回 {@link TurnDecision}（end/continue），剪掉 pi 的 retry；
 * - 默认 getSteeringMessages / getFollowUpMessages 为空（不灌注）。
 *
 * 取代原 {@code FinishTurn}（boolean isDone）——决策升级为显式 end/continue，并补齐其余三个钩子。
 */
public interface LoopStrategy {

    /**
     * 对齐 pi prepareNextTurn：新一轮开始前可替换上下文/换模型/做压缩。MVP no-op。
     * 每轮(内层)开始时调用一次。
     */
    default void prepareNextTurn(AssistantReply lastReply) {}

    /**
     * 对齐 pi prepareRequest：发请求前投影/路由。MVP 默认恒等（返回原历史）。
     * 改为返回「实际发给模型的消息」——压缩(折叠)/记忆(注入)在准备时真正生效。
     */
    default List<Message> prepareRequest(List<Message> history) { return history; }

    /**
     * 对齐 pi finishTurn：一轮(内层)结束时裁决 end / continue。
     */
    TurnDecision finishTurn(List<Message> history, AssistantReply lastReply);

    /**
     * 对齐 pi getSteeringMessages：等待期间外部灌注的消息（如用户追加输入）。MVP 默认空。
     */
    default List<Message> getSteeringMessages() { return List.of(); }

    /**
     * 对齐 pi getFollowUpMessages：内层停车后、外层衔接时灌注的消息（驱动外层 while）。
     * MVP 默认空；测试用它在内层停车后再注入一轮。
     */
    default List<Message> getFollowUpMessages() { return List.of(); }

    /** v0 默认：无工具调用即 end，有工具则 continue（工具回合本来就会再走一轮）。 */
    static LoopStrategy endOnNoToolCall() {
        return (h, r) -> r.hasToolCalls() ? TurnDecision.continueTurn() : TurnDecision.end();
    }
}
