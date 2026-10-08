package dev.firstagent.plan;

import dev.firstagent.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * 执行器 —— 严格按计划逐步骤执行。每步 = 一次受控 AgentLoop 子循环（步级 system 携带完整上下文），
 * 子循环最终文本 = 该步结果；结果累积成文本流入下一步。最后一步结果 = 最终答案。
 *
 * 每步子循环天然获得"可调工具"（子循环内模型可自由发工具调用），历史只累积结果文本（省上下文）。
 * v1 线性执行，不做动态重规划（改善项）。
 */
public final class PlanExecutor {
    private final LlmProvider llm;
    private final ToolRegistry tools;
    private final int stepMaxTurns;
    private final Consumer<AgentEvent> emit;

    public PlanExecutor(LlmProvider llm, ToolRegistry tools, int stepMaxTurns, Consumer<AgentEvent> emit) {
        this.llm = llm;
        this.tools = tools;
        this.stepMaxTurns = stepMaxTurns;
        this.emit = emit;
    }

    public String execute(String question, List<String> plan) {
        emit.accept(new AgentEvent.PlanStarted(question, plan));
        StringBuilder history = new StringBuilder("无");
        String result = "";
        for (int i = 0; i < plan.size(); i++) {
            String step = plan.get(i);
            emit.accept(new AgentEvent.PlanStepStarted(i, step));
            String stepSystem = """
                    你是一个执行专家。严格按照给定的计划逐步解决问题。
                    原始问题: %s
                    完整计划: %s
                    已完成步骤与结果:
                    %s
                    当前步骤: %s
                    如需工具请直接调用；输出仅针对当前步骤的结果文本。""".formatted(
                    question, plan, history, step);
            AgentLoop stepLoop = new AgentLoop(stepSystem, llm, tools, stepMaxTurns,
                    LoopStrategy.endOnNoToolCall());
            result = stepLoop.execute(step, emit);
            history.append("\n步骤 ").append(i + 1).append(": ").append(step)
                    .append("\n结果: ").append(result);
            emit.accept(new AgentEvent.PlanStepEnded(i, step, result));
        }
        return result;
    }
}