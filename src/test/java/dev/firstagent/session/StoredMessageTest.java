package dev.firstagent.session;

import dev.firstagent.Message;
import dev.firstagent.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StoredMessageTest {

    @Test void roundTripsSystemRole() {
        StoredMessage s = StoredMessage.from(Message.system("你是助手"));
        assertEquals("SYSTEM", s.role());
        assertEquals("你是助手", s.text());
        Message back = s.toMessage();
        assertEquals(Message.Role.SYSTEM, back.role());
        assertEquals("你是助手", back.text());
    }

    @Test void roundTripsUserRole() {
        Message back = StoredMessage.from(Message.user("帮我查天气")).toMessage();
        assertEquals(Message.Role.USER, back.role());
        assertEquals("帮我查天气", back.text());
        assertTrue(back.toolCalls().isEmpty());
        assertFalse(back.isError());
    }

    @Test void roundTripsAssistantWithToolCalls() {
        Message m = Message.assistant("我要查", List.of(new ToolCall("c1", "WeatherTool", "{\"city\":\"hz\"}")));
        Message back = StoredMessage.from(m).toMessage();
        assertEquals(Message.Role.ASSISTANT, back.role());
        assertEquals("我要查", back.text());
        assertEquals(1, back.toolCalls().size());
        assertEquals("c1", back.toolCalls().get(0).id());
        assertEquals("WeatherTool", back.toolCalls().get(0).name());
    }

    @Test void roundTripsToolResult() {
        Message back = StoredMessage.from(Message.toolResult("c1", "晴 25°C", false)).toMessage();
        assertEquals(Message.Role.TOOL, back.role());
        assertEquals("c1", back.toolCallId());
        assertEquals("晴 25°C", back.text());
        assertFalse(back.isError());
    }

    @Test void errorToolResultRoundTrips() {
        assertTrue(StoredMessage.from(Message.toolResult("c1", "错误", true)).toMessage().isError());
    }
}