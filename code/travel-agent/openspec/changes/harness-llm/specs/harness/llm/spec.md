# Spec: LLM 多协议适配（OpenAI / Anthropic）

> Sources:
> - src/llm/ChatClient.java
> - src/llm/OpenAiCompatibleChatClient.java
> - src/llm/AnthropicChatClient.java（新增）
> - src/agent/Main.java

## Purpose

`ChatClient` 接口保持不变，为它新增一个 **Anthropic 原生协议** 实现，并在装配处（`Main`）用协议开关选择。Anthropic 与 OpenAI 的协议差异——顶层 `system` 字段、`messages` 仅含 user/assistant 角色、返回在 `content[0].text`、必填 `max_tokens`——全部隔离在 `AnthropicChatClient` 内部，对上层引擎（`RunLoop`/`Agent`）透明。同时增强 OpenAI 客户端的解析错误诊断，便于排查网关返回非 JSON 的情况。

背景：一个多协议中转网关同时支持 OpenAI 协议（`POST /v1/chat/completions`）与 Anthropic 原生协议（`POST /v1/messages`），两者均接受 `Authorization: Bearer` 认证。本变更让 agent 在这两种协议间可切换。

## ADDED Requirements

### Requirement: 新增 `AnthropicChatClient` 实现 `ChatClient`

- 继承 `ChatClient`，`chat(List<Message>)` 把消息历史映射为 Anthropic Messages API 请求。
- MUST 将 `role==system` 的消息抽取到请求顶层 `system` 字段；剩余消息仅保留 user/assistant 角色，去掉 `tool` 角色（本项目 ReAct 观测以 user 消息回喂）。
- MUST 携带必填 `max_tokens` 字段（可经 `LLM_MAX_TOKENS` 环境变量配置，默认 1024）。
- MUST 从 `LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL` 读取 key / 地址 / 模型名；endpoint = `{LLM_BASE_URL}/messages`。
- MUST 从响应 `content[0].text` 读取模型回答。
- 构造函数接受注入的 `HttpClient`，便于本地 HTTP 假网关测试（纯 JDK `com.sun.net.httpserver.HttpServer`）。

#### Scenario: Anthropic 请求体映射

Given 消息历史含 system 说明书 + user 问题 + assistant 回复
When 调用 `chat`
Then POST 到 `{base}/messages`，请求体顶层含 `system` 与 `max_tokens`，`messages` 数组只含 user/assistant 角色

#### Scenario: 解析 Anthropic 响应

Given 假网关返回 `{"content":[{"type":"text","text":"推荐去故宫"}]}`
When 调用 `chat`
Then 返回 `"推荐去故宫"`

#### Scenario: 认证头

Given 配置了 `LLM_API_KEY`
When 调用 `chat`
Then 请求带 `Authorization: Bearer <key>` 头

### Requirement: 协议开关（OpenAI / Anthropic）

- `Main` MUST 依据 `LLM_PROTOCOL` 环境变量（`openai` 默认 / `anthropic`）选择客户端；key、地址、模型三个 env 名复用一致，不新增。

#### Scenario: 按协议装配客户端

Given `LLM_PROTOCOL=anthropic` 且 `LLM_API_KEY` 已配
When 构造真实 LLM
Then 使用 `AnthropicChatClient`，endpoint 为 `/messages`

#### Scenario: 默认 OpenAI

Given 未设 `LLM_PROTOCOL`、`LLM_API_KEY` 已配
When 构造真实 LLM
Then 使用 `OpenAiCompatibleChatClient`，endpoint 为 `/chat/completions`

### Requirement: OpenAI 客户端解析错误诊断增强

- 解析失败 MUST 抛出包含原始响应开头的异常，便于定位网关返回非 JSON 的情况，而非裸 `NumberFormatException`。

#### Scenario: 非 JSON 响应给出诊断

Given 网关返回 HTML / 非 JSON（HTTP 200）
When 调用 OpenAI 客户端 `chat`
Then 抛出的异常信息包含响应开头片段
