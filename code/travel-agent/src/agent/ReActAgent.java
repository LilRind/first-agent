package agent;

import java.util.ArrayList;
import java.util.List;

import llm.ChatClient;
import llm.ChatClient.Message;
import tools.Tool;
import tools.ToolRegistry;

/**
 * ReAct 主循环 —— agent 的引擎，对应书本最后一个循环逻辑。
 *
 * 循环大致是：
 *   1. 用系统提示词（说明书）+ 用户问题，发给 LLM；
 *   2. LLM 返回一对 Thought / Action；
 *   3. 解析 Action：
 *      - 如果是 Finish[答案]，结束，返回答案；
 *      - 如果是工具调用，查注册表执行，把观测结果作为新消息回喂 LLM；
 *   4. 回到 1，直到 Finish 或达到轮数上限。
 *
 * 关键点：每一轮都把**完整对话历史**（含上一步的观测结果）发给 LLM，
 * 这样 LLM 才记得自己已经查过天气、拿到过结果。maxRounds 是保险丝，
 * 防止 LLM 一直不 Finish 造成无限循环。
 */
public class ReActAgent {

    private final ToolRegistry registry;
    private final ChatClient llm;
    private final int maxRounds;

    public ReActAgent(ToolRegistry registry, ChatClient llm) {
        this(registry, llm, 5);
    }

    public ReActAgent(ToolRegistry registry, ChatClient llm, int maxRounds) {
        this.registry = registry;
        this.llm = llm;
        this.maxRounds = maxRounds;
    }

    /** 跑一轮对话，返回最终答案。 */
    public String run(String userRequest) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(buildSystemPrompt()));
        messages.add(Message.user(userRequest));

        for (int round = 1; round <= maxRounds; round++) {
            String llmReply = llm.chat(messages);
            messages.add(Message.assistant(llmReply));
            System.out.println("\n[round " + round + "] LLM 回复:\n" + llmReply);

            ActionParser.Parsed parsed = ActionParser.parse(llmReply);
            if (parsed == null) {
                System.out.println("[round " + round + "] 无法解析 Action，尝试继续。");
                continue;
            }

            if (parsed instanceof ActionParser.Finish fin) {
                return fin.answer();
            }

            if (parsed instanceof ActionParser.ToolCall call) {
                Tool tool = registry.get(call.name());
                if (tool == null) {
                    System.out.println("[round " + round + "] 工具不存在: " + call.name());
                    messages.add(Message.user("错误:没有叫 " + call.name() + " 的工具，请换一个。" + "\n请输出新的 Thought 和 Action。"));
                    continue;
                }
                String observation = tool.execute(call.args());
                System.out.println("[round " + round + "] 工具 " + call.name() + " 返回: " + observation);
                // 观测结果回喂给 LLM，作为下一轮推理的依据
                messages.add(Message.user("工具返回: " + observation + "\n请继续你的 Thought 和 Action。"));
            }
        }

        // 轮数用尽还没收尾，兜底返回
        return "抱歉，尝试了 " + maxRounds + " 轮仍未得到最终答案，请换个问法再试。";
    }

    /** 组装系统提示词：固定说明书 + 当前注册的工具描述。 */
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
