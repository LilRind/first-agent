# Spec: Agent Loop Harness

> Sources:
> - src/core/Agent.java
> - src/core/RunLoop.java
> - src/core/Message.java
> - src/core/ToolResult.java
> - src/core/AgentEvent.java

## Purpose

「引擎 + 外壳 + 事件」分离的 agent loop。`RunLoop` 跑纯 ReAct 循环并只发事件;`Agent` 外壳持 transcript、管理订阅者,可被多个监听器实时观察。消息、工具结果、事件均为纯数据模型。行为与原 `ReActAgent` 一致,可离线 mock 验证。

## ADDED Requirements

### Requirement: 建模：工件引用包为 `core`

- `core.Message` MUST 承载 role(system/user/assistant/tool)与 content,不可变。
- `core.ToolResult` MUST 承载工具执行原文文本与 `isError` 标记。
- `core.AgentEvent` MUST 是 sealed 事件类型,涵盖 turn_start/turn_end、message_start/update/end、tool_execution_start/end、agent_start/end。

#### Scenario: 纯数据模型可构造与比对

Given 需要构造一条 system 消息
When 新建 `Message("system", "你是助手")`
Then role 为 system、content 正确,且 equals/hashCode 与该值一致的副本相同,与不同值不同

Given 一个工具返回文本与错误标记
When 新建 `ToolResult("北京晴", false)`
Then text 为"北京晴"、isError 为 false,isError 可独立区分为 true

### Requirement: 引擎：`RunLoop`

- `RunLoop.loop(...)` MUST 驱动纯 ReAct 循环:全历史回喂 → 解析 Action → 执行工具把 `ToolResult` 回喂 → 直到 `Finish` 或 `maxRounds` 用尽。
- `RunLoop` MUST 通过 emit 回调派发 `AgentEvent`,实现事件/状态/UI 分离。
- `RunLoop` 不得 `System.out` 或持有 UI 状态。

#### Scenario: 引擎驱动 mock 脚本完整往返

Given 一个返回"查天气→荐景点→Finish"三步脚本的 `MockChatClient`
When 经 `RunLoop` 驱动循环
Then 依次执行天气、景点两个工具并把 `ToolResult` 回喂,最终以 `Finish` 返回答案
And 引擎通过 emit 派发 message/tool 事件,自身不打印

#### Scenario: maxRounds 兜底不无限循环

Given 一个始终不 Finish 的 mock 脚本
When 运行循环
Then 跑满 `maxRounds` 后返回兜底提示、事件序列以 agent_end 收尾、不产生额外模型调用

### Requirement: 外壳：`Agent`

- `Agent` MUST 持有 transcript(`List<Message>`)并管理订阅者集合。
- `Agent.subscribe(listener)` MUST 返回退订句柄(Runnable)。
- `Agent.run(userRequest)` MUST 触发 `agent_start`→ 逐轮事件 →`agent_end`,并返回最终答案。
- `Agent` 状态改变 MUST 通过事件按发生顺序通知全部已订阅监听器。

#### Scenario: 订阅者按序收到事件并可退订

Given 一个 `Agent` 已订阅一个 console 监听器
When 调用 `run` 并中途退订该监听器
Then 退订前事件按发生顺序送达,退订后不再收到后续事件,首尾分别为 agent_start/agent_end

### Requirement: 一致性约束

- 拆分后系统 MUST 保持与原 `ReActAgent` 行为一致:同一 `MockChatClient` 脚本,逐轮输出与原实现相同。
- 挂 console 监听器打印 MUST 等价于原引擎内直接打印。

#### Scenario: mock 三阶段输出与原实现逐位一致

Given 同一份 mock 脚本与原 `ReActAgent` 实现
When `java -cp out agent.Main mock "推荐一下北京"` 运行
Then 三条阶段(天气→景点→Finish)的逐轮文本与原实现相同,最终答案一致