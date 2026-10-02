# Design

## Context

proposal.md: 把 `ReActAgent.run`(循环+状态+打印揉在一起)拆成"引擎发事件、外壳持状态、监听器消费输出"三部分。目标是理解/教学级的 agent loop 骨架,行为不变。

约束:严格零第三方依赖(纯 JDK)、无构建工具、直接 `javac` 编译到 `out/`。这是 `feature-travel-agent` 已立下的硬约束,本 change 沿用。

## Goals / Non-Goals

**Goals:**
- 拆出可复用的 `runLoop` 引擎,与状态(transcript/订阅者)解耦。
- 引入 `AgentEvent` 事件模型,让 agent 可被多个监听器实时观察。
- 纯数据模型 `Message`/`ToolResult` 现形,替代手拼字符串。
- mock 模式下离线验证:行为与原 `ReActAgent` 逐位一致。

**Non-Goals:**
- 不引入钩子机制(`beforeToolCall`/`afterToolCall`/`finishTurn`)——那是下一个 change。
- 不做 steering/follow-up 双队列。
- 不做上下文治理/记忆/持久化。
- 不做流式 LLM(当前 `ChatClient` 仍是同步整段返回)。

> 判断:本 change 刻意保持"最小可跑"。Pi 的 `AgentState`/`AgentLoopConfig`/双队列都是马后炮;老师级讲法是 "先拆 loop 和 state,再谈要往 loop 里加什么"。拆完,下一 change 才有插入点。

## Decisions

1. **包结构与责任划分:引擎 vs 外壳 vs 模型。**
   ```
   src/core/Message.java     # role + content(纯数据,threadsafe immutable)
   src/core/ToolResult.java  # text + isError(纯数据)
   src/core/AgentEvent.java  # sealed:TurnStarted/TurnEnded/MessageEvent/ToolExecEvent...
   src/core/Agent.java       # 外壳:transcript + listeners,触发事件,持 runLoop
   src/core/RunLoop.java     # 纯循环引擎,只发事件,不碰状态/UI
   ```
   `Agent` 持 `List<Message>` transcript 与 `List<EventListener>`;`RunLoop` 接收 `transcript + 回调 + registry + llm`,返回最终答案并沿途 `emit(AgentEvent)`。
   替代方案:全部留在 `ReActAgent` + 加几个回调参数。审查后否决——那样 Agent 既持状态又跑循环,"壳 vs 引擎"没拆开,后续加钩子仍无处落脚。

2. **`ReActAgent` 的去留。**
   选择:保留 `ReActAgent` 作为薄适配层(`Agent` + `RunLoop` 的组合而不仅是替换),让 `Main` 与既有调用点不用改。企及:零破坏、旧行为原样。评审后觉得比"直接删"更稳妥——先并存一个 change,下个 change 再清理。

3. **事件模型用 `sealed interface` 而非类层级。**
   ```java
   public sealed interface AgentEvent
       permits TurnEvent, MessageEvent, ToolExecEvent, AgentLifecycleEvent { ... }
   ```
   理由:事件种类已知且有限,sealed 让 `switch` 穷尽(Java 21 可用 switch pattern,JDK17 用 if-instanceof 也可)。比简单 enum + payload 字段更类型安全。

4. **`AgentEvent` 字段按需携带,而非全量快照。**
   每条事件断言携带本轮最小数据(`message`/`toolName`/`round` 等)。理由:监听器(console/未来 UI)通常只关心增量,全量 snapshot 是浪费也是 Pi `AgentEvent` 的教训。

5. **打印移出引擎。**
   `RunLoop` 不 `System.out`,改为 `emit(new MessageEvent(...))`;`Main` 挂一个 console 监听器做打印。这样引擎纯、且未来换 UI 零侵入。

## Risks / Trade-offs

- [拆分改变类拓扑但行为须逐位一致] → 验收任务含"同一 mock 脚本逐轮输出与原实现相同";回归锚点是 mock 三阶段输出。
- [sealed interface 引入 Java 版本边界] → 既定 JDK17,`if-instanceof` 足够;不强行用 switch pattern。
- [`ReActAgent` 并存造成临时冗余] → 下个 change 清理,当前接受以换零破坏。

## Open Questions

<!-- 无 -->