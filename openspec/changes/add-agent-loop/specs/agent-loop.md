# Spec — Agent Loop (Acceptance Criteria)

## Feature: add-agent-loop（最小手写 Agent Loop）

### AC-1 契约类型存在且可用
- 存在 `Message`（role/user/user/assistant/tool）、`ToolCall(id,name,args)`、`AssistantReply(text, toolCalls, stopReason)`、`ToolRegistry(get/register)`、`AgentTool` 接口、`ToolExecutionException`。

### AC-2 Provider seam 可插拔
- `LlmProvider.chat(List<Message>) -> AssistantReply` 为接口。
- 提供 `MockLlm`：按脚本（Queue）依次返回预设 `AssistantReply`，供测试离线运行、不依赖网络/密钥。

### AC-3 无工具调用 ⇒ 直接返回文本
- 给定脚本让模型只返回文本、无 `toolCalls`，`AgentLoop` 返回该文本，并只产生一次模型调用。

### AC-4 工具失败闭环
- 模型调用**不存在**的工具 ⇒ 回填 `tool_use_error`（isError=true）而非崩溃。
- 工具 `execute` 抛异常 ⇒ 回填 error；模型重试成功后最终返回正确文本。
- 历史中工具结果按调用顺序回填。

### AC-5 JSON salvage 简化（截断/非法容错）
- 参数为非法/截断 JSON ⇒ 不抛异常，回填 `tool_use_error`，提示"参数不完整，请以完整合法 JSON 重新给参数"，循环继续。

### AC-6 maxTurns 防无限循环
- 默认上限 10。连续只生成工具调用而不收敛 ⇒ 抛 `MaxTurnsReached`，不死循环。

### AC-7 历史 append-only & 派生
- 历史为 `List<Message>` 追加；每个 assistant 的工具调用随后都紧跟对应 tool_result；请求串由历史派生（`LlmProvider` 以只读历史为入参）。

### AC-8 真实 OpenAI 兼容 provider
- 提供 `OpenAILlmProvider`，读 env `OPENAI_API_KEY` 调 `chat/completions`。无 key 时集成测试自动跳过（不影响 `mvn test`)。

### AC-9 可验证
- `mvn test` 全绿。JUnit 覆盖 AC-3、AC-4、AC-5、AC-6。

### AC-10 finishTurn 可插拔退出点（pi 留口，v0 空钩子）
- `AgentLoop` 暴露函数式 `FinishTurn` 钩子，在"无工具调用"后调用，返回 `end|continue`。
- v0 默认器在"无工具调用"时返回 end（不额外检测任务完成）。测试验证：插入返回 continue 的钩子能让 loop 继续一轮、返回 end 则本轮结束。