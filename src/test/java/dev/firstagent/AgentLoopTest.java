package dev.firstagent;

import dev.firstagent.llm.MockLlm;
import dev.firstagent.tools.EchoTool;
import dev.firstagent.tools.FailTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {

    private static final String SYSTEM = "你是一个演示助手。";

    // AC-3 无工具调用 ⇒ 直接返回文本，只一次模型调用
    @Test void noToolCallReturnsTextWithSingleCall() {
        MockLlm llm = MockLlm.scripted(new AssistantReply("你好", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry());
        assertEquals("你好", loop.execute("hi"));
        assertEquals(1, llm.calls());
    }

    // AC-4 模型调用不存在工具 ⇒ 回填 tool_use_error 而非崩溃；模型重试后成功
    @Test void unknownToolFeedsBackErrorThenRetrySucceeds() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("call_1", "ghost", "{}")), "tool_use"),
                new AssistantReply("重试成功", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry().register(new EchoTool()));

        assertEquals("重试成功", loop.execute("调一个不存在的工具"));
        assertEquals(2, llm.calls());

        Message last = llm.lastHistory().get(llm.lastHistory().size() - 1);  // 喂给模型的重试前历史，末条=tool_result
        assertTrue(last.isError());
        assertTrue(last.text().contains("unknown tool: ghost"));          // 回填了缺工具错误（AC-4）
    }

    // AC-4 工具 execute 抛异常 ⇒ 回填 error；模型重试成功后返回正确文本
    @Test void throwingToolFeedsBackErrorThenRetrySucceeds() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("call_1", "fail", "{}")), "tool_use"),
                new AssistantReply("最终答案", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry().register(new FailTool()));

        assertEquals("最终答案", loop.execute("触发失败"));
        assertEquals(2, llm.calls());
    }

    // AC-5 非法/截断 JSON 参数 ⇒ 回填 tool_use_error 不崩，循环继续
    @Test void illegalJsonParamsFeedsBackErrorWithoutCrash() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("call_1", "echo", "not-json")), "tool_use"),
                new AssistantReply("纠正后成功", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry().register(new EchoTool()));

        assertEquals("纠正后成功", loop.execute("给坏参数"));
        assertEquals(2, llm.calls());

        Message last = llm.lastHistory().get(llm.lastHistory().size() - 1);
        assertTrue(last.isError());
        assertTrue(last.text().contains("参数不完整"));                    // 回填了 salvage 提示文案（AC-5）
    }

    // AC-6 连续只发工具不收敛 ⇒ 抛 MaxTurnsReached 不死循环（cap 用 3，机制等同默认 10）
    @Test void nonConvergingToolCallsThrowMaxTurnsReached() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("c1", "echo", "{}")), "tool_use"),
                new AssistantReply("", List.of(new ToolCall("c2", "echo", "{}")), "tool_use"),
                new AssistantReply("", List.of(new ToolCall("c3", "echo", "{}")), "tool_use"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm,
                new ToolRegistry().register(new EchoTool()), 3, FinishTurn.endOnNoToolCall());

        assertThrows(MaxTurnsReached.class, () -> loop.execute("一直发工具"));
    }

    // AC-10 finishTurn：返回 continue 让 loop 继续一轮；返回 end 则本轮结束
    @Test void finishTurnContinueRunsExtraRoundThenEnd() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("第一轮", List.of(), "end_turn"),
                new AssistantReply("第二轮", List.of(), "end_turn"));
        FinishTurn continueThenEnd = new FinishTurn() {
            private boolean first = true;
            @Override public boolean isDone(List<Message> h, AssistantReply r) {
                if (first) { first = false; return false; }  // continue 一轮
                return true;                                  // 再一轮 end
            }
        };
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry(), 10, continueThenEnd);

        assertEquals("第二轮", loop.execute("多走一轮"));
        assertEquals(2, llm.calls());
    }

    // AC-7 历史 append-only & 派生：重试请求看到的历史顺序为 system→user→assistant(tool_call)→tool_result
    @Test void historyIsAppendOnlyAndDerived() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("call_1", "echo", "{\"x\":1}")), "tool_use"),
                new AssistantReply("收尾", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry().register(new EchoTool()));
        loop.execute("验证历史");

        List<Message> h = llm.lastHistory();                  // 第二次(重试)调用看到的历史
        assertEquals(4, h.size());
        assertEquals(Message.Role.SYSTEM, h.get(0).role());
        assertEquals(Message.Role.USER, h.get(1).role());
        assertEquals(Message.Role.ASSISTANT, h.get(2).role());
        assertFalse(h.get(2).toolCalls().isEmpty());
        assertEquals(Message.Role.TOOL, h.get(3).role());
        assertEquals("call_1", h.get(3).toolCallId());
    }
}