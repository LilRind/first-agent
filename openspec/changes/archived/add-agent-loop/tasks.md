# Tasks — Minimal Agent Loop

> 每个任务对应 specs/agent-loop.md 里一条验收标准。按 writing-plans → executing-plans → TDD 执行。

- [ ] T1 数据契约类型（Message/ToolCall/AssistantReply/ToolRegistry...）→ AC-1
- [ ] T2 LlmProvider seam + MockLlm（脚本化）→ AC-2
- [ ] T3 AgentLoop 无工具直接返回文本 → AC-3
- [ ] T4 AgentLoop 工具失败闭环（缺工具/异常 → error 回填 → 模型重试成功）→ AC-4
- [ ] T5 JsonSalvage：非法/截断参数 → 回填"参数不完整请重发"不崩 → AC-5
- [ ] T6 maxTurns 防无限循环（cap=10，超限抛 MaxTurnsReached）→ AC-6
- [ ] T7 历史 append-only：(assistant→tool_result 顺序回填)，请求派生自历史 → AC-7
- [ ] T8 真实 OpenAI 兼容 provider（env key 驱动，集成测试跳过无 key）→ AC-8
- [ ] T9 `mvn test` 全绿 + README → AC-9