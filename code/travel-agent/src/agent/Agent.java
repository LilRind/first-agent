package agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import core.AgentEvent;
import llm.ChatClient;
import tools.ToolRegistry;

/**
 * Agent 外壳 —— 持 transcript、管理订阅者、触发生命周期事件。
 *
 * 与 RunLoop(引擎)的关注点分离:
 * - RunLoop: 只跑循环,发 turn/message/tool 事件,不打印、不持 UI 状态。
 * - Agent: 组合一个 RunLoop,负责发 agent_start/agent_end 生命周期事件,
 *   并把事件广播给所有已订阅的监听器;持有可订阅机制(subscribe/unsubscribe)。
 *
 * 使用者只需要:new Agent(registry, llm) → agent.subscribe(listener) → agent.run("...")。
 */
public class Agent {

    private final ToolRegistry registry;
    private final ChatClient llm;
    private final int maxRounds;
    private final List<Consumer<AgentEvent>> listeners = new ArrayList<>();

    public Agent(ToolRegistry registry, ChatClient llm) {
        this(registry, llm, 5);
    }

    public Agent(ToolRegistry registry, ChatClient llm, int maxRounds) {
        this.registry = registry;
        this.llm = llm;
        this.maxRounds = maxRounds;
    }

    /** 订阅生命周期事件,返回退订句柄。 */
    public Runnable subscribe(Consumer<AgentEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** 触发 agent_start → 逐轮事件 → agent_end,返回最终答案。 */
    public String run(String userRequest) {
        emit(AgentEvent.agentStarted());
        RunLoop loop = new RunLoop(registry, llm, maxRounds);
        String answer = loop.run(userRequest, this::emit);
        emit(AgentEvent.agentEnded(answer));
        return answer;
    }

    private void emit(AgentEvent event) {
        for (Consumer<AgentEvent> listener : new ArrayList<>(listeners)) {
            listener.accept(event);
        }
    }
}