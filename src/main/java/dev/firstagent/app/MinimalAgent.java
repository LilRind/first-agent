package dev.firstagent.app;

import dev.firstagent.*;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.llm.OpenAILlmProvider;
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
 * 真实模型：.env 或环境变量配 OPENAI_API_KEY → OpenAILlmProvider，读 .env 的 BASE_URL/MODEL。
 * 离线兜底：没配 key 时退回 MockLlm，跑通「工具调用 → 收尾」闭环，打 telemetry span + 最终答案；会话落盘 sessions/。
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

        // .env 或环境变量配了 key → 真实模型（读 OPENAI_BASE_URL/OPENAI_MODEL，兼容任意网关/本地 ollama）；
        // 没配 → 退回 MockLlm 离线演示，无需任何 key 也能看到运行结果。
        AppConfig cfg = new AppConfig();
        LlmProvider llm = cfg.has("OPENAI_API_KEY")
                ? new OpenAILlmProvider(cfg)
                : MockLlm.scripted(
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