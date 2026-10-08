package dev.firstagent.app;

import dev.firstagent.*;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.llm.OpenAILlmProvider;
import dev.firstagent.llm.ToolSpec;
import dev.firstagent.plan.PlanExecutor;
import dev.firstagent.plan.Planner;
import dev.firstagent.tools.CalculatorTool;
import dev.firstagent.tools.EchoTool;

import java.util.List;

/**
 * Plan-and-Execute demo —— 规划（Planner 一次性生成计划）→ 执行（PlanExecutor 逐步推进）。
 * 真实模型：配 OPENAI_API_KEY（.env）→ OpenAILlmProvider（Planner + 每步子循环都带 tools 声明）。
 * 离线兜底：无 key 时 MockLlm 脚本演示"计划 → 执行 → 结果"链路。
 */
public final class PlanSolveDemo {
    private static final String QUESTION = "(123+456)*789/12 的结果是多少？";

    public static void main(String[] args) {
        ToolRegistry tools = new ToolRegistry().register(new CalculatorTool()).register(new EchoTool());
        AppConfig cfg = new AppConfig();
        List<ToolSpec> specs = List.of(ToolSpec.from(new CalculatorTool()), ToolSpec.from(new EchoTool()));
        LlmProvider llm = cfg.has("OPENAI_API_KEY")
                ? new OpenAILlmProvider(cfg, specs)
                : MockLlm.scripted(
                        new AssistantReply("[\"调用计算工具求出表达式结果\"]", List.of(), "end_turn"),
                        new AssistantReply("38069.25", List.of(), "end_turn"));
        new PlanSolveDemo().run(llm, tools);
    }

    public void run(LlmProvider llm, ToolRegistry tools) {
        Planner planner = new Planner(llm);
        List<String> plan = planner.plan(QUESTION);
        PlanExecutor executor = new PlanExecutor(llm, tools, 5, e -> { });
        String result = executor.execute(QUESTION, plan);

        System.out.println("\n==== 问题 ====\n" + QUESTION);
        System.out.println("\n==== 计划 ====");
        for (int i = 0; i < plan.size(); i++) {
            System.out.println("步骤 " + (i + 1) + ": " + plan.get(i));
        }
        System.out.println("\n==== 结果 ====");
        System.out.println(result);
    }

    /** 测试入口：注入脚本 LLM，返回格式化输出。 */
    public String runWith(LlmProvider llm, ToolRegistry tools) {
        Planner planner = new Planner(llm);
        List<String> plan = planner.plan(QUESTION);
        PlanExecutor executor = new PlanExecutor(llm, tools, 5, e -> { });
        String result = executor.execute(QUESTION, plan);

        StringBuilder sb = new StringBuilder();
        sb.append("计划: ").append(plan).append('\n');
        for (int i = 0; i < plan.size(); i++) {
            sb.append("步骤 ").append(i + 1).append(": ").append(plan.get(i)).append('\n');
        }
        sb.append("结果: ").append(result);
        return sb.toString();
    }
}