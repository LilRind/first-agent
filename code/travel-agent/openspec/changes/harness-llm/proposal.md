# Proposal: LLM 层支持 Anthropic 协议 + OpenAI/Anthropic 协议开关

## Context

目标是一个**多协议中转网关**，同时支持 OpenAI 协议（`/v1/chat/completions`）与 Anthropic 原生协议（`/v1/messages`）。我们现有的 `OpenAiCompatibleChatClient` 只会说 OpenAI 协议。用户希望用 Claude 模型，因此需要新增 Anthropic 协议实现，并让 agent 能在这两种协议间切换。

> 已核实（只读探测无 key）：`POST /v1/chat/completions` 与 `POST /v1/messages` 均存在且接受 `Authorization: Bearer` / `x-api-key` / `x-goog-api-key`。

## What (要做什么)

1. 新增 `AnthropicChatClient implements ChatClient`，按 Anthropic Messages API 格式收发。
2. 在 `Main` 装配处以 `LLM_PROTOCOL`（`openai` 默认 / `anthropic`）选择客户端。
3. 增强 `OpenAiCompatibleChatClient` 的解析错误诊断（把原始响应开头带进异常）。

协议差异被隔离在客户端内部，`RunLoop`/`Agent`/事件模型完全无感。

## Non-goals (不做)

- 不改 `core.Message` / `core.AgentEvent` / `RunLoop` / `Agent` 这些已冻结的层。
- 不做原生 function-calling / 结构化工具调用（那是 `harness-tools` 的事）。
- 不改 mock 模式与离线演示路径。
- 不在 CLI 里新增 `change new`（该命令不存在，本变更目录手动创建）。

## Success Criteria

- `AnthropicChatClient` 的请求体/响应解析/认证头，经本地假网关（`com.sun.net.httpserver`）TDD 验证通过。
- `LLM_PROTOCOL=anthropic` 时装配出 `AnthropicChatClient`；默认/`openai` 时装配出 `OpenAiCompatibleChatClient`。
- OpenAI 客户端对非 JSON 响应抛出含原始片段的可读异常。
- 现有 5 个测试类仍全绿；`openspec validate --changes harness-llm` 通过。