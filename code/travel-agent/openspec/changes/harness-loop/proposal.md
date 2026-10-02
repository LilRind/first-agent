# Proposal

## Why

现有 `ReActAgent`(src/agent/ReActAgent.java:26)把 **agent 循环、状态持有、控制台打印** 全部揉在一个 `run` 方法里。这作为"5 分钟实现第一个智能体"的演示足够,但它不可订阅、不可流式、无法测试。要像 pi/framework 那样理解"agent loop 是什么",第一步必须把这个核心类**按关注点拆开**:引擎只发事件,外壳持状态,让循环本身和 UI/打印解耦。

拆分后循环仍是同一个纯 ReAct 循环——行为不变,但为后续引入钩子、双队列、上下文治理打好骨架。这是"从骨架到框架"的一跃,也是理解 harness 三要素(loop/tool/memory)里第一个:loop。

## What Changes

- **BREAKING**: 新增 `Message` 统一消息模型(src/tools 级纯数据,沿用 `llm.ChatClient.Message` 形态),承载 role(system/user/assistant/tool)+ content。
- **BREAKING**: 新增 `ToolResult`(工具执行结果:原文 + isError 标记 + 可选文本),替代 `ReActAgent` 里手拼的"工具返回: {obs}"字符串。
- **新增** `AgentEvent` 事件模型:turn_start/turn_end、message_start/update/end、tool_execution_start/end、agent_start/end。
- **新增** `Agent` 外壳类:持有 transcript、维护订阅者集合、触发事件;提供 `subscribe(EventListener)`、`run(userRequest)`。
- **新增** `runLoop` 引擎:从 `ReActAgent` 抽出纯循环逻辑,只经回调发事件、不打印、不持有 UI 状态。
- **重构**: `ReActAgent.run` 拆分为 `Agent`(壳)+ `runLoop`(引擎)+ 事件管道;`Main` 装配方式不变,仍 `java -cp out agent.Main mock ...` 可跑。
- 保留 `System.out` 输出但移到订阅者(Main 里挂一个 console 监听器),而非引擎内部硬编码。

## Capabilities

### New Capabilities
- `harness/agent-loop`: 一个"引擎 + 外壳 + 事件"分离的 agent loop。引擎(`runLoop`)跑纯 ReAct 循环并只发事件;外壳(`Agent`)持 transcript、管理订阅者,可被多个监听器实时观察;消息(`Message`)、工具结果(`ToolResult`)、事件(`AgentEvent`)均为纯数据模型。行为与原 `ReActAgent` 一致,可离线 mock 验证。

### Modified Capabilities
<!-- 无：specs 库存为空，本 change 为全新引入，feature-travel-agent 尚未归档 -->

## Impact

- 代码：`src/agent/ReActAgent.java`(拆分)、`src/agent/Main.java`(改为挂事件监听器)、新增 `src/agent/Agent.java`、`src/core/{Message,ToolResult,AgentEvent}`(或置于合适包)。
- 兼容：`run-mock.sh` 与现有 mock 脚本路径不变,仍无第三方依赖、纯 javac 编译。
- 不改变外部依赖：不新增工具、不改 LLM 客户端、不改 wttr.in/Tavily。