package core;

import test.Assert;

/**
 * core.AgentEvent —— sealed 事件模型。RED:此时类不存在,编译失败即特性缺失。
 *
 * 验证两件事:
 * 1. 全部 9 种事件可构造、字段正确;
 * 2. if-instanceof 能穷尽匹配(sealed 保证无遗漏 —— 匹配里 else 抛异常,
 *    若漏了某子类型且该类型真的出现,会触发异常暴露缺口)。
 */
public class AgentEventTest {

    public static void main(String[] args) {
        // —— 构造每种事件并断言字段 ——
        AgentEvent.agentStarted();
        AgentEvent.agentEnded("最终答案");

        AgentEvent.TurnStarted ts = AgentEvent.turnStarted(1);
        Assert.equal(1, ts.round());
        AgentEvent.TurnEnded te = AgentEvent.turnEnded(1);
        Assert.equal(1, te.round());

        Message m = Message.assistant("思考中");
        AgentEvent.MessageStarted ms = AgentEvent.messageStarted(m);
        Assert.equal("思考中", ms.message().content());
        AgentEvent.MessageUpdated mu = AgentEvent.messageUpdated(m);
        Assert.true_(mu.message().equals(m), "updated 事件应携带同一消息");
        AgentEvent.MessageEnded me = AgentEvent.messageEnded(m);
        Assert.equal("assistant", me.message().role());

        AgentEvent.ToolStarted toolStart = AgentEvent.toolStarted("get_weather");
        Assert.equal("get_weather", toolStart.toolName());
        AgentEvent.ToolEnded toolEnd = AgentEvent.toolEnded("get_weather", "北京晴，气温20℃");
        Assert.equal("get_weather", toolEnd.toolName());
        Assert.equal("北京晴，气温20℃", toolEnd.resultText());

        // —— equals/hashCode:同构事件相等,异构不等 ——
        Assert.equalsContract(ts, AgentEvent.turnStarted(1), AgentEvent.turnStarted(2));
        Assert.equalsContract(toolEnd, AgentEvent.toolEnded("get_weather", "北京晴，气温20℃"), AgentEvent.toolEnded("get_weather", "别的结果"));
        Assert.true_(!ms.equals(me), "message_started 与 message_ended 是不同类型事件,不该相等");

        // —— if-instanceof 穷尽匹配:遍历一个集合,每个都落到已知分支 ——
        AgentEvent[] all = {
            AgentEvent.agentStarted(), AgentEvent.agentEnded("x"),
            ts, te,
            ms, mu, me,
            toolStart, toolEnd,
        };
        for (AgentEvent e : all) {
            dispatch(e);
        }

        System.out.println("PASS " + AgentEventTest.class.getName());
    }

    /** 穷尽匹配示例 —— 任何未覆盖的 sealed 子类型在此抛异常,证明漏了分支。 */
    private static String dispatch(AgentEvent e) {
        if (e instanceof AgentEvent.AgentStarted) return "agent_start";
        if (e instanceof AgentEvent.AgentEnded ae) return "agent_end:" + ae.finalAnswer();
        if (e instanceof AgentEvent.TurnStarted t) return "turn_start:" + t.round();
        if (e instanceof AgentEvent.TurnEnded t) return "turn_end:" + t.round();
        if (e instanceof AgentEvent.MessageStarted) return "msg_start";
        if (e instanceof AgentEvent.MessageUpdated) return "msg_update";
        if (e instanceof AgentEvent.MessageEnded me) return "msg_end:" + me.message().role();
        if (e instanceof AgentEvent.ToolStarted t) return "tool_start:" + t.toolName();
        if (e instanceof AgentEvent.ToolEnded t) return "tool_end:" + t.toolName() + ":" + t.resultText();
        throw new IllegalStateException("未覆盖的事件类型: " + e);
    }
}