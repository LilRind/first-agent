# Tasks

> 实现 change：`harness-loop`。
> 本 change 聚焦"引擎与外壳分离 + 事件模型"，严格 TDD：每个任务先写（或先移）能证明行为的测试，绿了才进下一步。
> 约束：纯 JDK17、无第三方依赖、`javac` 编译到 `out/`。

## 1. 纯数据模型

- [ ] 1.1 新增 `core.Message`(role + content,不可变)。**验收**：单元测试断言 system/user/assistant/tool 四种 role 可构造、内容正确、equals/hashCode 一一对应。
- [ ] 1.2 新增 `core.ToolResult`(text + isError)。**验收**：测工具返回原文与 isError 标记可独立读写。
- [ ] 1.3 新增 `core.AgentEvent`(sealed 接口 + 各子类型 turn/message/tool_exec/agent_lifecycle)。**验收**：编译期对事件做 `if-instanceof` 穷尽匹配的测试通过,无非法事件可构造。

## 2. runLoop 引擎

- [ ] 2.1 抽出 `RunLoop.loop(...)`,纯循环只发事件、不打印、不持状态。**验收**：mock 脚本三阶段(查天气→荐景点→Finish)在 `RunLoop` 驱动下正确终止并返回答案;引擎内无 `System.out`(评审确认)。
- [ ] 2.2 maxRounds 兜底:始终不 Finish 的脚本跑满即停、返回兜底提示、不无限循环。**验收**：计数断言模型调用次数 == maxRounds。

## 3. Agent 外壳

- [ ] 3.1 `Agent` 持 transcript + 订阅者集合,`subscribe` 返回退订句柄。**验收**：事件监听器能按序收到消息;退订后不再收到后续事件。
- [ ] 3.2 `Agent.run()` 触发 agent_start→逐轮 turn/message/tool 事件→agent_end。**验收**：记录事件序列,断言首尾为 agent_start/agent_end、中间依次包含预期的消息与工具事件。

## 4. 集成与一致性

- [ ] 4.1 `Main` 改为挂 console 监听器打印,行为与原 `ReActAgent.run` 逐位一致。**验收**：`java -cp out agent.Main mock "推荐一下北京"` 输出与原实现相同的三阶段逐轮文本 + 最终答案。
- [ ] 4.2 `run-mock.sh` 编译通过并跑通一次,无第三方依赖。**验收**：脚本一次执行到"最终答案"输出。

## 5. 归档

- [ ] 5.1 `openspec validate --change harness-loop` 通过。
- [ ] 5.2 git 提交为一次原子 commit,信息含 change 名;行为经 mock 回归确认无破坏。