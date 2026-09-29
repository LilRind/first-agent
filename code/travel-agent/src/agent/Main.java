package agent;

import llm.ChatClient;
import llm.MockChatClient;
import llm.OpenAiCompatibleChatClient;
import tools.AttractionTool;
import tools.ToolRegistry;
import tools.WeatherTool;

/**
 * 入口 —— 把各个零件拼起来，跑一次旅行咨询。
 *
 * 运行模式（看环境变量 / 命令行参数）：
 *   - 传参 "mock" 或没配 LLM_API_KEY  → 用 MockChatClient 离线跑通，不需要 key；
 *   - 配了 LLM_API_KEY                → 走真实 OpenAI 兼容接口（可改 LLM_BASE_URL/LLM_MODEL）。
 *
 * 用法：
 *   java -cp out agent.Main mock "我想去北京玩，有什么推荐？"
 */
public class Main {

    public static void main(String[] args) {
        // 组装工具（对应书本的 available_tools dict）
        ToolRegistry registry = new ToolRegistry()
                .register(new WeatherTool())
                .register(new AttractionTool());

        // 组装 LLM：mock 或真实
        boolean useMock = args.length > 0 && "mock".equalsIgnoreCase(args[0])
                || System.getenv("LLM_API_KEY") == null;
        ChatClient llm = useMock ? MockChatClient.travelDemo()
                : new OpenAiCompatibleChatClient();
        if (useMock) {
            System.out.println("[模式] 使用 MockChatClient 离线演示（配了 LLM_API_KEY 可切真实模型）。\n");
        }

        ReActAgent agent = new ReActAgent(registry, llm);

        String question = args.length > 1 && !"mock".equalsIgnoreCase(args[0])
                ? args[1]
                : "我想去北京玩，帮我推荐一下？";
        System.out.println("[用户] " + question);

        String answer = agent.run(question);
        System.out.println("\n[最终答案] " + answer);
    }
}
