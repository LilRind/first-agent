package dev.firstagent.memory;

import dev.firstagent.*;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.session.Session;
import dev.firstagent.session.SessionStore;
import dev.firstagent.session.SummarizingCompactor;
import dev.firstagent.telemetry.AgentEventTelemetryBridge;
import dev.firstagent.telemetry.TelemetryRecorder;
import dev.firstagent.tools.EchoTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成小测试框架：fixture 驱动。预置一段旧会话 → 跑 MemoryRecallStrategy 驱动的 AgentLoop →
 * 断言 (a) 跨会话记忆注入历史 (b) telemetry span 时序 (c) 超阈值压缩折叠。
 */
class MemoryRecallIntegrationTest {

    private static final String CWD = "cwdX";
    private static final String SYSTEM = "你是一个演示助手。" + "a".repeat(120); // 133 字符 → ~33 tokens，配合小预算强制压缩

    @TempDir Path tmp;

    @Test void recallsPriorSession_recordsTelemetry_andCompacts() {
        SessionStore store = new SessionStore(tmp);
        // 预置「更早一次会话」作为长期记忆来源
        Session prev = store.create(CWD, "prior");
        store.append(prev, Message.user("用户上次要北京天气"));
        store.append(prev, Message.assistant("用户决定去长城", List.of()));

        TelemetryRecorder telemetry = new TelemetryRecorder();
        SummarizingCompactor compactor = new SummarizingCompactor(20); // 小预算强制压缩
        MemoryRecallStrategy strategy = new MemoryRecallStrategy(store, compactor, CWD, telemetry);
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("c1", "echo", "{\"a\":1}")), "tool_use"),
                new AssistantReply("", List.of(new ToolCall("c2", "echo", "{\"b\":2}")), "tool_use"),
                new AssistantReply("最后答案", List.of(), "end_turn"));
        ToolRegistry tools = new ToolRegistry().register(new EchoTool());
        AgentLoop loop = new AgentLoop(SYSTEM, llm, tools, 10, strategy);

        String answer = loop.execute("你好", new AgentEventTelemetryBridge(telemetry));

        assertEquals("最后答案", answer);

        // (a) 跨会话记忆召回并注入历史（最终请求里仍带长期记忆）
        List<Message> history = llm.lastHistory();
        assertTrue(history.stream().anyMatch(m ->
                m.role() == Message.Role.SYSTEM && m.text().contains("长期记忆")),
                "应注入上上次会话的折叠摘要作为长期记忆");
        assertTrue(history.stream().anyMatch(m -> m.text().contains("北京天气")),
                "召回内容应含旧会话关键词");

        // (c) 超阈值压缩折叠发生：历史里出现 [压缩摘要] system
        assertTrue(history.stream().anyMatch(m ->
                m.role() == Message.Role.SYSTEM && m.text().startsWith("[压缩摘要]")),
                "应发生折叠压缩");

        // (b) telemetry span 时序：memory 命中 + agent 收尾都在
        List<String> names = telemetry.spanNames();
        assertTrue(names.contains("memory.recall"), "应录 memory.recall span");
        assertTrue(names.contains("memory.inject"), "应录 memory.inject span");
        assertTrue(names.contains("compaction"), "应录 compaction span");
        assertTrue(names.contains("agent.end"), "应录 agent.end span");
        // 事件桥 span 之前先有 memory span
        assertTrue(names.indexOf("memory.recall") < names.indexOf("agent.end"),
                "memory.recall 应先于 agent.end");
    }

    @Test void appendsMessagesToSessionStore() {
        SessionStore store = new SessionStore(tmp);
        TelemetryRecorder telemetry = new TelemetryRecorder();
        MemoryRecallStrategy strategy =
                new MemoryRecallStrategy(store, new SummarizingCompactor(100000), CWD, telemetry);
        MockLlm llm = MockLlm.scripted(new AssistantReply("明白", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry().register(new EchoTool()), 10, strategy);

        loop.execute("测试持久化", new AgentEventTelemetryBridge(telemetry));

        assertEquals(1, store.list().size(), "应持久化 1 个会话");
        Session s = store.list().get(0);
        assertTrue(s.entries().size() >= 2, "会话应至少含 user + assistant");
    }
}