package agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import core.AgentEvent;
import core.Message;
import core.ToolResult;
import llm.ChatClient;
import tools.Tool;
import tools.ToolRegistry;

/**
 * RunLoop —— agent 循环引擎。对应 ReActAgent 的核心循环,但不打印、不持 UI 状态。
 *
 * 从 ReActAgent 拆分出来后,引擎只做一件事:驱动循环并通过 emit 回调派发 AgentEvent。
 * "谁在看""怎么显示"完全交给外壳(Agent)与订阅者,引擎保持纯净、可测试。
 * 这与 pi 的 runLoop、nanobot 的 AgentRunner 是同一层抽象:引擎只发事件。
 */
public class RunLoop {

    private final ToolRegistry registry;
    private final ChatClient llm;
    private final int maxRounds;

    public RunLoop(ToolRegistry registry, ChatClient llm) {
        this(registry, llm, 5);
    }

    public RunLoop(ToolRegistry registry, ChatClient llm, int maxRounds) {
        this.registry = registry;
        this.llm = llm;
        this.maxRounds = maxRounds;
    }

    /**
     * 驱动循环,经 emit 派发事件,返回最终答案(Finish 或 maxRounds 兜底)。
     */
    public String run(String userRequest, Consumer<AgentEvent> emit) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(buildSystemPrompt()));
        messages.add(Message.user(userRequest));

        for (int round = 1; round <= maxRounds; round++) {
            emit.accept(AgentEvent.turnStarted(round));

            String llmReply = llm.chat(messages);
            Message assistantMsg = Message.assistant(llmReply);
            messages.add(assistantMsg);
            emit.accept(AgentEvent.messageStarted(assistantMsg));
            emit.accept(AgentEvent.messageUpdated(assistantMsg));
            emit.accept(AgentEvent.messageEnded(assistantMsg));

            ActionParser.Parsed parsed = ActionParser.parse(llmReply);
            if (parsed == null) {
                // 无法解析,继续下一轮(由 maxRounds 兜底)
                emit.accept(AgentEvent.turnEnded(round));
                continue;
            }

            if (parsed instanceof ActionParser.Finish fin) {
                emit.accept(AgentEvent.turnEnded(round));
                emit.accept(AgentEvent.agentEnded(fin.answer()));
                return fin.answer();
            }

            if (parsed instanceof ActionParser.ToolCall call) {
                Tool tool = registry.get(call.name());
                if (tool == null) {
                    // 未知工具:回喂错误提示,由 LLM 换工具或收尾
                    messages.add(Message.user(
                            "错误:没有叫 " + call.name() + " 的工具,请换一个。\n请输出新的 Thought 和 Action。"));
                    emit.accept(AgentEvent.turnEnded(round));
                    continue;
                }
                emit.accept(AgentEvent.toolStarted(call.name()));
                String observation = tool.execute(call.args());
                ToolResult result = observation == null ? ToolResult.error("工具无返回")
                        : new ToolResult(observation, false);
                emit.accept(AgentEvent.toolEnded(call.name()));
                // 观测结果回喂给 LLM,作为下一轮推理依据
                messages.add(Message.user("工具返回: " + result.text() + "\n请继续你的 Thought 和 Action。"));
                emit.accept(AgentEvent.turnEnded(round));
            }
        }

        // 轮数用尽还没收尾,兜底返回
        String fallback = "抱歉,尝试了 " + maxRounds + " 轮仍未得到最终答案,请换个问法再试。";
        emit.accept(AgentEvent.agentEnded(fallback));
        return fallback;
    }

    /** 组装系统提示词:固定说明书 + 当前注册的工具描述。 */
    private String buildSystemPrompt() {
        return """
                你是一个智能旅行助手。你的任务是分析用户的请求，并使用可用工具一步步地解决问题。

                # 可用工具:
                %s

                # 输出格式要求:
                你的每次回复必须严格遵循以下格式，包含一对Thought和Action：

                Thought: [你的思考过程和下一步计划]
                Action: [你要执行的具体行动]

                Action的格式必须是以下之一：
                1. 调用工具：function_name(arg_name="arg_value")
                2. 结束任务：Finish[最终答案]

                # 重要提示:
                - 每次只输出一对Thought-Action
                - Action必须在同一行，不要换行
                - 当收集到足够信息可以回答用户问题时，必须使用 Action: Finish[最终答案] 格式结束

                请开始吧！
                """.formatted(registry.describeTools());
    }
}