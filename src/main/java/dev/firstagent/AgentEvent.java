package dev.firstagent;

import java.util.List;

/**
 * 引擎 emit 的事件流 —— 引擎纯 emit、不打印；外壳(subscribe 方)据此渲染/落日志。
 * 对齐 pi 的 turn_start / message_start / turn_end / agent_end；tool 级事件是给外壳观测的扩展
 * (Pi 只发 message 级，first-agent 增加 ToolStarted/ToolEnded 便于观察工具执行)。
 *
 * sealed 保证枚举封闭：外壳 switch 覆盖全事件，不留未知分支。
 */
public sealed interface AgentEvent {

    /** 一轮开始。 */
    record TurnStarted() implements AgentEvent {}

    /** 一条消息开始(入历史前)。 */
    record MessageStarted(Message message) implements AgentEvent {}

    /** 一条消息结束(入历史后)。 */
    record MessageEnded(Message message) implements AgentEvent {}

    /** 一次工具调用开始执行。 */
    record ToolStarted(ToolCall call) implements AgentEvent {}

    /** 一次工具调用执行完成(含错误回填的结果)。 */
    record ToolEnded(ToolCall call, Message result) implements AgentEvent {}

    /** 一轮结束(含 finishTurn 裁决后)。 */
    record TurnEnded() implements AgentEvent {}

    /** 整个循环结束，附上完整历史。 */
    record AgentEnded(List<Message> messages) implements AgentEvent {}

    /** Plan-and-Execute：规划完成，附完整计划。 */
    record PlanStarted(String question, List<String> plan) implements AgentEvent {}

    /** Plan-and-Execute：开始执行第 index 步（0-based）。 */
    record PlanStepStarted(int index, String step) implements AgentEvent {}

    /** Plan-and-Execute：第 index 步执行完成，附该步结果。 */
    record PlanStepEnded(int index, String step, String result) implements AgentEvent {}
}
