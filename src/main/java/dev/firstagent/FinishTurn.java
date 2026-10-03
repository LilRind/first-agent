package dev.firstagent;

import java.util.List;

/**
 * pi finishTurn 的可编程退出钩子（v0 留口，不实现任务完成检测）。
 * 无工具调用时由 AgentLoop 调用；true = 本轮结束并返回文本，false = 继续一轮。
 */
@FunctionalInterface
public interface FinishTurn {
    boolean isDone(List<Message> history, AssistantReply lastReply);

    /** v0 默认：无工具调用即结束（不额外检测任务是否完成）。 */
    static FinishTurn endOnNoToolCall() { return (h, r) -> true; }
}