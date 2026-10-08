package dev.firstagent.app;

import dev.firstagent.*;
import dev.firstagent.LoopStrategy;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.llm.OpenAILlmProvider;
import dev.firstagent.llm.ToolSpec;
import dev.firstagent.tools.CalculatorTool;
import dev.firstagent.tools.EchoTool;

import java.util.List;

/**
 * ReAct 完整闭环 demo —— 提问 → 思考 → 行动 → 观察 → Finish。
 * 真实模型：配 OPENAI_API_KEY（.env）→ OpenAILlmProvider 带 tools 声明，模型可真实发起工具调用。
 * 离线兜底：无 key 时 MockLlm 脚本演示完整闭环轨迹。
 */
public final class ReActDemo {
    private static final String SYSTEM = "你是一个演示助手。可以调用工具收集信息后准确回答。";
    private static final String QUESTION = "(123+456)*789/12 的结果是多少？";

    public static void main(String[] args) {
        ToolRegistry tools = new ToolRegistry().register(new CalculatorTool()).register(new EchoTool());
        AppConfig cfg = new AppConfig();
        LlmProvider llm = cfg.has("OPENAI_API_KEY")
                ? new OpenAILlmProvider(cfg, List.of(ToolSpec.from(new CalculatorTool()), ToolSpec.from(new EchoTool())))
                : MockLlm.scripted(
                        new AssistantReply("我需要用计算工具先算准确。",
                                List.of(new ToolCall("c1", "calculate", "{\"expression\":\"(123+456)*789/12\"}")), "tool_use"),
                        new AssistantReply("根据计算，结果为 38069.25。", List.of(), "end_turn"));
        new ReActDemo().run(llm, tools);
    }

    public void run(LlmProvider llm, ToolRegistry tools) {
        ReActTrace trace = new ReActTrace();
        AgentLoop loop = new AgentLoop(SYSTEM, llm, tools, 8, LoopStrategy.endOnNoToolCall());
        String answer = loop.execute(QUESTION, trace);
        trace.finish(answer);

        System.out.println("\n==== 问题 ====\n" + QUESTION);
        System.out.println("\n==== ReAct 轨迹 ====");
        for (ReActTrace.Step s : trace.steps()) {
            System.out.println("Thought: " + (s.thought() == null ? "(无思考文本)" : s.thought()));
            System.out.println("Action: " + s.action());
            System.out.println("Observation: " + s.observation());
        }
        System.out.println("Finish: " + trace.finish());
        System.out.println("\n==== 最终答案 ====");
        System.out.println(answer);
    }

    /** 测试入口：注入脚本 LLM，返回格式化轨迹。 */
    public String runWith(LlmProvider llm, ToolRegistry tools) {
        ReActTrace trace = new ReActTrace();
        AgentLoop loop = new AgentLoop(SYSTEM, llm, tools, 8, LoopStrategy.endOnNoToolCall());
        String answer = loop.execute(QUESTION, trace);
        trace.finish(answer);
        StringBuilder sb = new StringBuilder();
        for (ReActTrace.Step s : trace.steps()) {
            sb.append("Thought: ").append(s.thought() == null ? "(无思考文本)" : s.thought()).append('\n');
            sb.append("Action: ").append(s.action()).append('\n');
            sb.append("Observation: ").append(s.observation()).append('\n');
        }
        sb.append("Finish: ").append(trace.finish());
        return sb.toString();
    }
}