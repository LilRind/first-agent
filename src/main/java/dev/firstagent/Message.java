package dev.firstagent;

import java.util.List;

/**
 * 一条会话消息。一切(用户话/模型答复/工具结果)都归一成 Message。
 *
 * - SYSTEM / USER  : text 承载内容
 * - ASSISTANT      : text(说给用户的话) + toolCalls(本轮要调的工具，可为空)
 * - TOOL           : toolCallId(回填到哪个调用) + text(结果) + isError(执行是否失败)
 */
public final class Message {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    private final Role role;
    private final String text;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final boolean isError;

    private Message(Role role, String text, List<ToolCall> toolCalls, String toolCallId, boolean isError) {
        this.role = role;
        this.text = text;
        this.toolCalls = toolCalls;
        this.toolCallId = toolCallId;
        this.isError = isError;
    }

    public static Message system(String text) { return new Message(Role.SYSTEM, text, null, null, false); }
    public static Message user(String text)    { return new Message(Role.USER, text, null, null, false); }

    /** 模型答复：文本 + 本轮工具调用(可能空)。 */
    public static Message assistant(String text, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, text, toolCalls, null, false);
    }

    /** 工具结果回填：绑定到对应的 tool_use_id。 */
    public static Message toolResult(String toolCallId, String text, boolean isError) {
        return new Message(Role.TOOL, text, null, toolCallId, isError);
    }

    public Role role() { return role; }
    public String text() { return text; }
    public List<ToolCall> toolCalls() { return toolCalls == null ? List.of() : toolCalls; }
    public String toolCallId() { return toolCallId; }
    public boolean isError() { return isError; }
}