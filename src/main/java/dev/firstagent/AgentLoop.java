package dev.firstagent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 手写最小 Agent Loop —— 按 pi 的 runLoop 结构做简化重构。
 *
 * 骨架对齐 pi agent-loop.ts:163：
 * - 双 while：内层 = 工具回合 + 灌注消息（hasMoreToolCalls || pending 非空）；外层 = follow-up 衔接。
 * - 引擎纯 emit：发 {@link AgentEvent}（turn_start/message_start/turn_end/agent_end + tool 级），不打印；外壳 subscribe。
 * - 策略钩子 {@link LoopStrategy}：prepareNextTurn / prepareRequest / finishTurn(end|continue) / getSteeringMessages / getFollowUpMessages。
 * - append-only 派生历史：请求由日志派生（"model-visible means logged"），工具结果永远回填（错也回填）。
 *
 * 剪掉 pi 的：流式、declareToolChanges 工具增量（v0 工具固定）、finishTurn 的 retry、工具并行 + terminate。
 * maxIterations 保留为安全兜底（Pi 主停车靠 finishTurn 决策；计数器防空转，语义与旧版一致）。
 */
public class AgentLoop {
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    private final String systemPrompt;
    private final LlmProvider llm;
    private final ToolRegistry tools;
    private final int maxIterations;
    private final LoopStrategy strategy;

    public AgentLoop(String systemPrompt, LlmProvider llm, ToolRegistry tools) {
        this(systemPrompt, llm, tools, DEFAULT_MAX_ITERATIONS, LoopStrategy.endOnNoToolCall());
    }

    public AgentLoop(String systemPrompt, LlmProvider llm, ToolRegistry tools,
                     int maxIterations, LoopStrategy strategy) {
        this.systemPrompt = systemPrompt;
        this.llm = llm;
        this.tools = tools;
        this.maxIterations = maxIterations;
        this.strategy = strategy;
    }

    /** 跑完整循环，返回最终回答（默认丢弃事件流）。 */
    public String execute(String userInput) {
        return execute(userInput, e -> { });
    }

    /** 跑完整循环并 emit 事件；引擎不打印，外壳(Consumer)负责渲染/落日志。 */
    public String execute(String userInput, Consumer<AgentEvent> emit) {
        List<Message> history = new ArrayList<>();
        history.add(Message.system(systemPrompt));     // dsh：请求由日志派生（只读历史）
        history.add(Message.user(userInput));

        // 开场先读一次 steering（等待期间可能已有人追加输入）
        List<Message> pending = new ArrayList<>(strategy.getSteeringMessages());
        boolean explicitContinue = false;
        AssistantReply lastReply = null;
        int turns = 0;

        outer:
        while (true) {                                  // —— 外层：follow-up 衔接 ——
            boolean hasMoreToolCalls = true;
            while (hasMoreToolCalls || !pending.isEmpty()) {   // —— 内层：工具回合 + 灌注 ——
                if (++turns > maxIterations) throw new MaxTurnsReached(maxIterations);  // 计数兜底
                strategy.prepareNextTurn(lastReply);   // pi prepareNextTurn：压缩/换模型（MVP no-op）
                emit.accept(new AgentEvent.TurnStarted());

                // 灌注 pending 消息入历史（对齐 pi 的 declareToolChanges 前 push）
                for (Message m : pending) {
                    emit.accept(new AgentEvent.MessageStarted(m));
                    history.add(m);
                    emit.accept(new AgentEvent.MessageEnded(m));
                }
                pending = strategy.getSteeringMessages();   // 排空后再轮询（对齐 Pi 单条语义）

                List<Message> requestMessages = strategy.prepareRequest(history);  // 压缩/记忆在此生效

                AssistantReply reply = llm.chat(requestMessages);      // 请求 = prepareRequest 的返回值
                lastReply = reply;
                Message assistant = Message.assistant(reply.text(), reply.toolCalls());
                history.add(assistant);
                emit.accept(new AgentEvent.MessageStarted(assistant));
                emit.accept(new AgentEvent.MessageEnded(assistant));

                hasMoreToolCalls = false;
                if (reply.hasToolCalls()) {
                    for (ToolCall call : reply.toolCalls()) {   // v0 顺序执行，未做并行
                        emit.accept(new AgentEvent.ToolStarted(call));
                        Message result = executeTool(call);     // 错也回填，模型自纠正
                        history.add(result);
                        emit.accept(new AgentEvent.ToolEnded(call, result));
                    }
                    hasMoreToolCalls = true;            // 本轮发了工具 → 内层继续，看模型是否收尾
                }

                TurnDecision decision = strategy.finishTurn(history, reply);
                emit.accept(new AgentEvent.TurnEnded());

                if (!hasMoreToolCalls && decision.action() == TurnDecision.Action.END) {
                    emit.accept(new AgentEvent.AgentEnded(history));
                    return reply.text();
                }
                explicitContinue = !hasMoreToolCalls && decision.action() == TurnDecision.Action.CONTINUE;
            }

            // 内层停车（无工具、无灌注）→ 外层看是否衔接
            List<Message> follow = strategy.getFollowUpMessages();
            if (!follow.isEmpty()) {
                explicitContinue = false;
                pending = follow;
                continue outer;
            }
            if (explicitContinue) {
                explicitContinue = false;
                continue outer;
            }
            break outer;
        }
        throw new MaxTurnsReached(maxIterations);      // 理论不可达兜底（END 会在内层 return）
    }

    /** 执行单个工具；无论成败都回填 tool_result。 */
    private Message executeTool(ToolCall call) {
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return Message.toolResult(call.id(), "tool_use_error: unknown tool: " + call.name(), true);
        }
        try {
            SalvageParser.parse(call.argumentsJson());   // D2 校验参数，非法→抛
            String resultText = tool.execute(call.argumentsJson());
            return Message.toolResult(call.id(), resultText, false);
        } catch (ToolExecutionException e) {
            return Message.toolResult(call.id(), "tool_use_error: " + e.getMessage(), true);
        } catch (Exception e) {                          // 工具自身任意异常
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return Message.toolResult(call.id(), "tool_use_error: " + reason, true);
        }
    }
}