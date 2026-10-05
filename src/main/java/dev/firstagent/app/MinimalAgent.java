package dev.firstagent.app;

import dev.firstagent.*;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.memory.MemoryRecallStrategy;
import dev.firstagent.session.SessionStore;
import dev.firstagent.session.SummarizingCompactor;
import dev.firstagent.telemetry.AgentEventTelemetryBridge;
import dev.firstagent.telemetry.TelemetryRecorder;
import dev.firstagent.tools.EchoTool;
import dev.firstagent.tools.FailTool;

import java.nio.file.Path;
import java.util.List;

/**
 * 最小 Agent CLI —— 先跑起来看到结果。
 * 复用 MockLlm 离线跑通「工具调用 → 收尾」闭环，打 telemetry span + 最终答案；会话落盘 sessions/。
 * 换真模型：把 MockLlm 换成 OpenAILlmProvider() 并配 OPENAI_API_KEY 即可（可选）。
 */
public final class MinimalAgent {

    private static final String SYSTEM = "你是一个演示助手。可以调用工具收集信息后回答。";

    public static void main(String[] args) {
        String userInput = args.length > 0 ? args[0] : "请用 echo 工具回显一句话，然后告诉我结果。";

        SessionStore store = new SessionStore(Path.of("sessions"));
        SummarizingCompactor compactor = new SummarizingCompactor(512);
        TelemetryRecorder telemetry = new TelemetryRecorder();
        String cwd = Path.of("").toAbsolutePath().toString();
        MemoryRecallStrategy strategy = new MemoryRecallStrategy(store, compactor, cwd, telemetry);

        ToolRegistry tools = new ToolRegistry().register(new EchoTool()).register(new FailTool());
        LlmProvider llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("call_1", "echo", "{\"msg\":\"你好\"}")), "tool_use"),
                new AssistantReply("已用 echo 工具完成回显，工具结果见调用记录。", List.of(), "end_turn"));

        AgentLoop loop = new AgentLoop(SYSTEM, llm, tools, 10, strategy);
        AgentEventTelemetryBridge bridge = new AgentEventTelemetryBridge(telemetry);

        String answer = loop.execute(userInput, bridge);

        System.out.println("\n==== telemetry spans ====");
        telemetry.prettyPrint();
        System.out.println("\n==== 最终答案 ====");
        System.out.println(answer);
    }
}