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
 * Agent 外壳 —— 持 transcript、管理订阅者、触发 agent_start/agent_end 生命周期。
 * RED:类不存在,编译失败即特性缺失。
 *
 * 验证:
 * 1. run() 触发 agent_start →(逐轮 turn/message/tool 事件)→ agent_end,首尾正确;
 * 2. 事件按发生顺序送达订阅者;
 * 3. subscribe 返回退订句柄,退订后不再收到事件。
 */
public class AgentTest {

    private static final class Recorder implements java.util.function.Consumer<AgentEvent> {
        final List<AgentEvent> events = new ArrayList<>();
        public void accept(AgentEvent e) { events.add(e); }
        boolean sawAgentStart() { return events.stream().anyMatch(e -> e instanceof AgentEvent.AgentStarted); }
        boolean sawAgentEnd() { return events.stream().anyMatch(e -> e instanceof AgentEvent.AgentEnded); }
        boolean lastIsAgentEnd() { return !events.isEmpty() && events.get(events.size() - 1) instanceof AgentEvent.AgentEnded; }
    }

    public static void main(String[] args) {
        testRunEmitsLifecycleStartToEnd();
        testSubscribeReturnsUnsubscribeHandle();
        System.out.println("PASS " + AgentTest.class.getName());
    }

    private static void testRunEmitsLifecycleStartToEnd() {
        Agent agent = buildAgent();
        Recorder rec = new Recorder();
        agent.subscribe(rec);
        String answer = agent.run("推荐一下北京");

        Assert.true_(answer.contains("故宫"), "应返回 Final 答案,实际=" + answer);
        Assert.true_(rec.sawAgentStart(), "应触发 agent_start");
        Assert.true_(rec.sawAgentEnd(), "应触发 agent_end");
        Assert.true_(rec.lastIsAgentEnd(), "agent_end 应为最后一条事件");
        Assert.true_(rec.events.size() >= 9, "事件数应覆盖 start+多轮,实际=" + rec.events.size());
    }

    private static void testSubscribeReturnsUnsubscribeHandle() {
        Agent agent = buildAgent();
        Recorder rec = new Recorder();
        Runnable unsubscribe = agent.subscribe(rec);
        // 第一次 run 收到
        agent.run("推荐一下北京");
        int afterFirstRun = rec.events.size();
        Assert.true_(afterFirstRun > 0, "订阅者应收到首轮事件");

        // 退订后再 run,不再收到
        unsubscribe.run();
        agent.run("推荐一下北京");
        Assert.equal(afterFirstRun, rec.events.size(), "退订后不应再收到事件");
    }

    private static Agent buildAgent() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WeatherTool());
        registry.register(new AttractionTool());
        return new Agent(registry, MockChatClient.travelDemo());
    }
}