package agent;

import core.AgentEvent;
import llm.ChatClient;
import llm.ChatClientFactory;
import llm.MockChatClient;
import tools.AttractionTool;
import tools.ToolRegistry;
import tools.WeatherTool;

/**
 * 入口 —— 把各个零件拼起来，跑一次旅行咨询。
 *
 * 拆分后的装配方式：用 Agent 外壳 + 一个 console 监听器。
 * Agent 只负责跑循环、发事件；监听器从事件流里还原出逐轮打印。
 * —— 这正是"引擎与显示分离"的演示：想换 UI 只换监听器，不动引擎。
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
                : ChatClientFactory.create(System.getenv().getOrDefault("LLM_PROTOCOL", "openai"));
        if (useMock) {
            System.out.println("[模式] 使用 MockChatClient 离线演示（配了 LLM_API_KEY 可切真实模型）。\n");
        }

        // 组装外壳 + 订阅 console 监听器（事件驱动打印）
        Agent agent = new Agent(registry, llm);
        agent.subscribe(Main::render);

        String question = args.length > 1 && !"mock".equalsIgnoreCase(args[0])
                ? args[1]
                : "我想杭州玩，帮我推荐一下？";
                // : "我想去北京玩，帮我推荐一下？";
        System.out.println("[用户] " + question);

        String answer = agent.run(question);
        System.out.println("\n[最终答案] " + answer);
    }

    /** 从事件流还原出与引擎内直接打印等价的控制台输出。 */
    private static void render(AgentEvent event) {
        if (event instanceof AgentEvent.TurnStarted t) {
            System.out.println("\n[round " + t.round() + "] LLM 回复:");
        } else if (event instanceof AgentEvent.MessageStarted m) {
            System.out.println(m.message().content());
        } else if (event instanceof AgentEvent.ToolStarted t) {
            System.out.println("[round 工具] 调用了 " + t.toolName());
        } else if (event instanceof AgentEvent.ToolEnded t) {
            System.out.println("[round 工具] " + t.toolName() + " 返回: " + t.resultText());
        }
        // AgentStarted / TurnEnded / MessageEnded / MessageUpdated / AgentEnded
        // 由[用户]/[最终答案] 或其他监听器处理,这里不重复打印。
    }
}