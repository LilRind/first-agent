package dev.firstagent;

import java.util.ArrayList;
import java.util.List;

/**
 * 手写最小 Agent Loop —— 模型驱动 ReAct 决策循环。
 * 骨架 = hermes 主判（发不发工具 + maxIterations 计数兜底）+ pi finishTurn 可编程退出钩子
 *        + dsh append-only 派生历史（请求由日志派生、先落日志）。
 * StepContext（codex 留口：本轮冻结工具清单）v0 刻意不实现 —— 工具固定、无中途注入。
 */
public class AgentLoop {
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    private final String systemPrompt;
    private final LlmProvider llm;
    private final ToolRegistry tools;
    private final int maxIterations;
    private final FinishTurn finishTurn;

    public AgentLoop(String systemPrompt, LlmProvider llm, ToolRegistry tools) {
        this(systemPrompt, llm, tools, DEFAULT_MAX_ITERATIONS, FinishTurn.endOnNoToolCall());
    }

    public AgentLoop(String systemPrompt, LlmProvider llm, ToolRegistry tools,
                     int maxIterations, FinishTurn finishTurn) {
        this.systemPrompt = systemPrompt;
        this.llm = llm;
        this.tools = tools;
        this.maxIterations = maxIterations;
        this.finishTurn = finishTurn;
    }

    /** 跑完整循环，返回最终回答。 */
    public String execute(String userInput) {
        List<Message> history = new ArrayList<>();
        history.add(Message.system(systemPrompt));
        history.add(Message.user(userInput));

        for (int turn = 1; turn <= maxIterations; turn++) {
            AssistantReply reply = llm.chat(history);                     // dsh：请求由日志派生（只读历史）
            history.add(Message.assistant(reply.text(), reply.toolCalls())); // dsh：先落日志

            if (reply.hasToolCalls()) {
                for (ToolCall call : reply.toolCalls()) {                 // v0 顺序执行，未做并行
                    history.add(executeTool(call));                       // 错也回填，模型自纠正
                }
            } else {
                if (finishTurn.isDone(history, reply)) {                  // pi 可编程退出点
                    return reply.text();
                }
                // finishTurn 返回 false(continue) → 继续一轮，但不发工具
            }
        }
        throw new MaxTurnsReached(maxIterations);                         // hermes 计数兜底，防死循环
    }

    /** 执行单个工具；无论成败都回填 tool_result。 */
    private Message executeTool(ToolCall call) {
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return Message.toolResult(call.id(), "tool_use_error: unknown tool: " + call.name(), true);
        }
        try {
            SalvageParser.parse(call.argumentsJson());                    // D2 校验参数，非法→抛
            String resultText = tool.execute(call.argumentsJson());
            return Message.toolResult(call.id(), resultText, false);
        } catch (ToolExecutionException e) {
            return Message.toolResult(call.id(), "tool_use_error: " + e.getMessage(), true);
        } catch (Exception e) {                                           // 工具自身任意异常
            return Message.toolResult(call.id(), "tool_use_error: " + e.getMessage(), true);
        }
    }
}