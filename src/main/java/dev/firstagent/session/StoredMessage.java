package dev.firstagent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.firstagent.Message;
import dev.firstagent.ToolCall;

import java.util.List;

/**
 * 落盘用的消息快照 DTO。根 Message 是 final + 私有构造,Jackson 不能直接序列化,
 * 持久化边界用它折一层极薄映射(Message <-> StoredMessage),不侵入共享 demo 类。
 * role 字符串为枚举名:SYSTEM/USER/ASSISTANT/TOOL。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredMessage(String role, String text, List<ToolCall> toolCalls,
                            String toolCallId, boolean isError) {

    public static StoredMessage from(Message m) {
        List<ToolCall> calls = m.toolCalls();
        return new StoredMessage(m.role().name(), m.text(),
                calls.isEmpty() ? null : calls, m.toolCallId(), m.isError());
    }

    public Message toMessage() {
        return switch (Message.Role.valueOf(role)) {
            case SYSTEM -> Message.system(text);
            case USER -> Message.user(text);
            case ASSISTANT -> Message.assistant(text, toolCalls == null ? List.of() : toolCalls);
            case TOOL -> Message.toolResult(toolCallId, text, isError);
        };
    }
}