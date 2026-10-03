package dev.firstagent.llm;

import dev.firstagent.AssistantReply;
import dev.firstagent.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockLlmTest {

    @Test void returnsScriptedRepliesInOrder() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("第一", List.of(), "end_turn"),
                new AssistantReply("第二", List.of(), "end_turn"));
        assertEquals("第一", llm.chat(List.of(Message.user("x"))).text());
        assertEquals("第二", llm.chat(List.of(Message.user("x"))).text());
        assertEquals(2, llm.calls());
    }

    @Test void exhaustedScriptThrows() {
        MockLlm llm = MockLlm.scripted(new AssistantReply("只此一次", List.of(), "end_turn"));
        llm.chat(List.of(Message.user("x")));
        assertThrows(IllegalStateException.class, () -> llm.chat(List.of(Message.user("x"))));
    }
}