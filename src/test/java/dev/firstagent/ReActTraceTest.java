package dev.firstagent;

import dev.firstagent.llm.MockLlm;
import dev.firstagent.tools.EchoTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReActTraceTest {

    // 完整闭环：提问 → 工具调用 → 观察 → Finish
    @Test void fullLoopBuildsThoughtActionObservationFinish() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("我需要先查一下。", List.of(new ToolCall("call_1", "echo", "{\"msg\":\"hi\"}")), "tool_use"),
                new AssistantReply("最终答案: 收到了 hi", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop("sys", llm, new ToolRegistry().register(new EchoTool()));
        ReActTrace trace = new ReActTrace();

        String answer = loop.execute("测试一下", trace);
        trace.finish(answer);

        assertEquals("最终答案: 收到了 hi", answer);
        assertEquals(1, trace.steps().size());
        assertEquals("我需要先查一下。", trace.steps().get(0).thought());
        assertEquals("echo[{\"msg\":\"hi\"}]", trace.steps().get(0).action());
        assertTrue(trace.steps().get(0).observation().contains("echo:"));
        assertEquals("最终答案: 收到了 hi", trace.finish());
    }

    // 无工具直接回答：只有一条 Finish，无 step
    @Test void directAnswerHasNoSteps() {
        MockLlm llm = MockLlm.scripted(new AssistantReply("你好", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop("sys", llm, new ToolRegistry());
        ReActTrace trace = new ReActTrace();

        assertEquals("你好", loop.execute("hi", trace));
        trace.finish("你好");
        assertTrue(trace.steps().isEmpty());
        assertEquals("你好", trace.finish());
    }
}