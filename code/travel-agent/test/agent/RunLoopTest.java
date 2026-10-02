package agent;

import java.util.ArrayList;
import java.util.List;

import core.AgentEvent;
import llm.MockChatClient;
import test.Assert;
import tools.AttractionTool;
import tools.ToolRegistry;
import tools.WeatherTool;

/**
 * RunLoop —— 纯循环引擎。RED:类不存在,编译失败即特性缺失。
 *
 * 验证:
 * 1. 引擎驱动 mock 脚本三阶段(查天气→荐景点→Finish)正确终止并返回最终答案;
 * 2. 全程经 emit 回调派发事件(turn_start/end、message、tool_start/end),引擎自身不打印;
 * 3. maxRounds 兜底:始终不 Finish 的脚本跑满即停、不无限循环。
 */
public class RunLoopTest {

    /** 收集引擎派发的事件序列。 */
    private static final class Recorder implements java.util.function.Consumer<AgentEvent> {
        final List<AgentEvent> events = new ArrayList<>();
        public void accept(AgentEvent e) { events.add(e); }
    }

    public static void main(String[] args) {
        testTravelDemoRunsAndEmitsEvents();
        testMaxRoundsBailout();
        System.out.println("PASS " + RunLoopTest.class.getName());
    }

    private static void testTravelDemoRunsAndEmitsEvents() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WeatherTool());
        registry.register(new AttractionTool());
        MockChatClient llm = MockChatClient.travelDemo();

        RunLoop loop = new RunLoop(registry, llm);
        Recorder rec = new Recorder();
        String answer = loop.run("推荐一下北京", rec);

        // 最终答案来自 Finish
        Assert.true_(answer.contains("故宫"), "应返回 Final 中的景点答案,实际=" + answer);

        // 事件序列覆盖关键节点
        boolean sawToolStart = false, sawToolEnd = false, sawTurn = false;
        for (AgentEvent e : rec.events) {
            if (e instanceof AgentEvent.ToolStarted t) { if ("get_weather".equals(t.toolName())) sawToolStart = true; }
            if (e instanceof AgentEvent.ToolEnded t) { if ("get_weather".equals(t.toolName())) sawToolEnd = true; }
            if (e instanceof AgentEvent.TurnStarted) sawTurn = true;
        }
        Assert.true_(sawToolStart, "应派发 get_weather 的 tool_start");
        Assert.true_(sawToolEnd, "应派发 get_weather 的 tool_end");
        Assert.true_(sawTurn, "应派发 turn_start");
    }

    private static void testMaxRoundsBailout() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WeatherTool());
        MockChatClient llm = new MockChatClient(List.of(
            "Thought:不查了。\nAction: get_weather(city=\"北京\")" // 永远要查,不 Finish
        ));
        // 脚本 PullFinish 走光了就反复吐兜底 Finish,但这会提前终止。
        // 为验证 maxRounds,用 maxRounds=1 + 一个必然走工具的脚本,确认第1轮就停。
        RunLoop loop = new RunLoop(registry, llm, 1);
        Recorder rec = new Recorder();
        String answer = loop.run("查天气", rec);

        // maxRounds=1 时:第1轮调一次模型,走工具后不再有第2轮 → 轮数用尽兜底
        int turns = 0;
        for (AgentEvent e : rec.events) if (e instanceof AgentEvent.TurnStarted) turns++;
        Assert.equal(1, turns);
        Assert.true_(answer.contains("尝试"), "maxRounds 用尽应返回兜底提示,实际=" + answer);
    }
}