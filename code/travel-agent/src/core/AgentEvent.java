package core;

/**
 * agent 生命周期事件 —— sealed 接口 + 固定子类型。
 *
 * 引擎不直接打印、不碰 UI,而是 emit 这些事件;外壳(Agent)持状态、监听器订阅观察。
 * sealed 保证事件种类在编译期闭合:新增一种事件必须改这里,if-instanceof 匹配才能
 * 穷尽(dispatch 里漏了某个子类型,编译器在匹配处无法感知,但 sealed 让"新增"显式)。
 *
 * 事件分 4 类共 9 种:
 *  - 生命周期: AgentStarted / AgentEnded
 *  - 轮次:      TurnStarted / TurnEnded
 *  - 消息:      MessageStarted / MessageUpdated / MessageEnded
 *  - 工具执行:  ToolStarted / ToolEnded
 */
public sealed interface AgentEvent {

    // ---- 生命周期 ----
    record AgentStarted() implements AgentEvent {}
    record AgentEnded(String finalAnswer) implements AgentEvent {}

    // ---- 轮次 ----
    record TurnStarted(int round) implements AgentEvent {}
    record TurnEnded(int round) implements AgentEvent {}

    // ---- 消息 ----
    record MessageStarted(Message message) implements AgentEvent {}
    record MessageUpdated(Message message) implements AgentEvent {}
    record MessageEnded(Message message) implements AgentEvent {}

    // ---- 工具执行 ----
    record ToolStarted(String toolName) implements AgentEvent {}
    record ToolEnded(String toolName) implements AgentEvent {}

    // ---- 工厂 ----
    static AgentStarted agentStarted() { return new AgentStarted(); }
    static AgentEnded agentEnded(String finalAnswer) { return new AgentEnded(finalAnswer); }

    static TurnStarted turnStarted(int round) { return new TurnStarted(round); }
    static TurnEnded turnEnded(int round) { return new TurnEnded(round); }

    static MessageStarted messageStarted(Message message) { return new MessageStarted(message); }
    static MessageUpdated messageUpdated(Message message) { return new MessageUpdated(message); }
    static MessageEnded messageEnded(Message message) { return new MessageEnded(message); }

    static ToolStarted toolStarted(String toolName) { return new ToolStarted(toolName); }
    static ToolEnded toolEnded(String toolName) { return new ToolEnded(toolName); }
}