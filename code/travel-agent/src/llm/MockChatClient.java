package llm;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 离线 Mock LLM —— 不联网也能跑通整个 ReAct 循环。
 *
 * 为什么要 mock？真实调 LLM 需要 key、要联网、还花钱。写 agent 循环的核心逻辑时，
 * 我们希望先确认"循环本身"是对的：解析 Thought/Action、派发工具、把观测结果回喂、
 * 在 Finish 处收尾。用一个返回固定台词的 Mock 顶替 LLM，就能在本地反复验证循环，
 * 而不依赖外部服务。这也是测试里常见的"依赖注入 + 假实现"手法。
 *
 * 它按顺序吐出一串预设回复（脚本），依次：查天气 -> 查景点 -> Finish。
 * 每轮 LLM 历史里会多出上一轮的观测，我们 mock 就简单忽略，按脚本继续吐。
 */
public class MockChatClient implements ChatClient {

    private final Deque<String> script = new ArrayDeque<>();

    public MockChatClient(List<String> script) {
        this.script.addAll(script);
    }

    @Override
    public String chat(List<Message> messages) {
        if (script.isEmpty()) {
            // 脚本走完还没被收尾，就给一个兜底 Finish，避免无限循环
            return "Thought:我已经尽力了。\nAction: Finish[抱歉，无法继续查询。]";
        }
        return script.removeFirst();
    }

    /** 一个现成的旅行 demo 脚本：两次查工具，然后 Finish。 */
    public static MockChatClient travelDemo() {
        // 这里偷懒直接写死城市=北京，方便离线验证；真实场景由 LLM 决定参数。
        return new MockChatClient(List.of(
                "Thought:用户想了解北京的旅行建议，我需要先查天气。\n"
                        + "Action: get_weather(city=\"北京\")",
                "Thought:已知道天气，现在根据天气搜索北京景点。\n"
                        + "Action: get_attraction(city=\"北京\", weather=\"晴天\")",
                "Thought:已经拿到天气和景点，信息足够回答用户了。\n"
                        + "Action: Finish[北京今天天气晴，推荐去故宫、颐和园和长城。]"
        ));
    }
}