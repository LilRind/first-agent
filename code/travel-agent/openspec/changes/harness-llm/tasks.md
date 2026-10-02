# Tasks

> 实现 change：`harness-llm`。严格 TDD：每步先写/改能证明行为的测试，红了再写最小实现，绿了才进下一步。
> 约束：纯 JDK17、无第三方依赖、编译到 `out/`；测试用 JDK 内置 `com.sun.net.httpserver` 起本地假网关，不联网。

## 1. 共享工具：JSON 字符串转义

- [x] 1.1 把 `OpenAiCompatibleChatClient` 私有的 `jsonQuote` 抽成共享（`util.Json.quote`），OpenAI 客户端改用它。**验收**：编译通过、现有测试全绿、行为不变（`util.JsonTest` 覆盖转义）。

## 2. Anthropic 客户端（TDD）

- [x] 2.1 RED：`AnthropicChatClientTest` —— 假网关捕获请求体，断言顶层含 `system`+`max_tokens`、`messages` 只含 user/assistant、带 `Authorization: Bearer` 头。看到失败（类不存在）。
- [x] 2.2 GREEN：最小实现 `AnthropicChatClient`，让 2.1 通过。
- [x] 2.3 RED：假网关返回 `{"content":[{"type":"text","text":"推荐去故宫"}]}`，断言 `chat` 返回 `"推荐去故宫"`。看到失败。
- [x] 2.4 GREEN：实现响应解析 `content[0].text`，让 2.3 通过。
- [x] 2.5 RED：假网关返回非 JSON（HTTP 200），断言抛出含原始响应片段的异常。看到失败。
- [x] 2.6 GREEN：非 200 / 解析失败都抛含原始片段的 `IllegalStateException`，让 2.5 通过。

> **发现的隐藏 bug（本变更一并修复）**：`Json.get` 路径解析对 `[i].key`（下标后直接跟点号，如 `content[0].text`）取不到值——else 分支把 `.` 当段边界算出空 key。`JsonTest` 补回归测试（RED）后修复（段首跳过 `.`）。此 bug 同样影响 `WeatherTool` 的 `current_condition[0].weatherDesc[0].value` 与 OpenAI 的 `choices[0].message.content`，一并解除。

## 3. 协议开关

- [x] 3.1 RED：`ChatClientFactoryTest` —— `LLM_PROTOCOL=anthropic` 选 `AnthropicChatClient`；未设/`openai`/空/未知 选 `OpenAiCompatibleChatClient`。看到失败。
- [x] 3.2 GREEN：`ChatClientFactory.create(protocol)` + `Main` 用 `LLM_PROTOCOL` 选型，让 3.1 通过。

## 4. OpenAI 客户端诊断收编 + 默认值

- [x] 4.1 给 `OpenAiCompatibleChatClient` 加可注入构造（对称 Anthropic），`OpenAiCompatibleChatClientTest` 用假网关断言非 JSON 响应抛含原始片段的异常。
- [x] 4.2 默认值保持中性（OpenAI 官方 URL / `gpt-3.5-turbo`）；个人网关与模型经环境变量 `LLM_BASE_URL` / `LLM_MODEL` 本地配置，不写死进仓库。

## 5. 验证与归档

- [x] 5.1 全量 `bash run-tests.sh` 全绿（9 个测试类：新增 JsonTest / AnthropicChatClientTest / ChatClientFactoryTest / OpenAiCompatibleChatClientTest）。
- [x] 5.2 `openspec validate --changes harness-llm` 通过；`run-mock.sh` 端到端无回归。
- [ ] 5.3 一次原子 commit，信息含 change 名；提交含 `src/`、`test/` 与 `openspec/changes/harness-llm/`。
