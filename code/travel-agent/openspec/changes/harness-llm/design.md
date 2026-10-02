# Design: AnthropicChatClient + 协议开关

## 1. 协议差异映射（Anthropic vs OpenAI）

| 维度 | OpenAI | Anthropic Messages API |
|---|---|---|
| 端点 | `{base}/chat/completions` | `{base}/messages` |
| system | 列表里一条 `role=system` | 顶层独立字段 `system`（不进 messages） |
| messages 角色 | system/user/assistant/tool | 仅 `user` / `assistant` |
| 必填 | 无 | `max_tokens`（必填） |
| 回答位置 | `choices[0].message.content` | `content[0].text` |
| 认证 | `Authorization: Bearer` | `Authorization: Bearer` / `x-api-key` |

**关键**：Anthropic 要求 system 独立顶层、messages 无 system 角色。本项目 `RunLoop` 恰好把系统说明书作为第一条 `Message.system(...)`，把工具观测作为 `Message.user("工具返回: ...")` 回喂——所以转换规则很干净：
- 抽取所有 `role==system` 的消息，`\n\n` 拼进顶层 `system`；
- 其余消息按 `role` 透传（仅 user/assistant 会出现），去掉 `tool` 角色（本项目不用 `Message.tool` 走循环）。

## 2. `AnthropicChatClient` 结构

```java
public class AnthropicChatClient implements ChatClient {
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final HttpClient http;          // 可注入，便于假网关测试
    private final String apiKey;
    private final String baseUrl;           // 默认 https://api.anthropic.com/v1
    private final String model;
    private final int maxTokens;            // 默认 1024，可 LLM_MAX_TOKENS 覆盖

    public AnthropicChatClient() { this(HttpClient.newBuilder()...build(), System.getenv()...); }
    public AnthropicChatClient(HttpClient http, String apiKey, String baseUrl, String model, int maxTokens) { ... }

    @Override public String chat(List<Message> messages) { ... }
}
```

`chat` 内部：
1. 遍历消息，system 单独收集，其余进 `List<Map>`（role/content）。
2. 拼 JSON 请求体：`{model, max_tokens, system?, messages:[{role, content}]}`，content 用与 OpenAI 客户端相同的 `jsonQuote` 转义。
3. `POST {base}/messages`，头：`Authorization: Bearer <key>`、`content-type: application/json`。
4. 解析 `content[0].text`；非 200 或解析失败，抛含原始响应片段的 `IllegalStateException`。

`jsonQuote` 目前是 `OpenAiCompatibleChatClient` 的私有静态方法——**抽到 `util` 或做成包内共享**，避免复制。倾向抽一个小工具（如 `Json` 里加 `quote`），两个客户端共用。

## 3. 协议开关（`Main`）

```java
String protocol = System.getenv().getOrDefault("LLM_PROTOCOL", "openai");
ChatClient llm = "anthropic".equalsIgnoreCase(protocol)
        ? new AnthropicChatClient()
        : new OpenAiCompatibleChatClient();
```

env 名复用：`LLM_API_KEY` / `LLM_BASE_URL` / `LLM_MODEL`（新增可选 `LLM_MAX_TOKENS`）。mock 判定逻辑不变（`useMock` 仍优先）。

## 4. 测试策略（TDD，纯 JDK）

`AnthropicChatClient` 要真发 HTTP，但**不联网**即可测——用 JDK 内置 `com.sun.net.httpserver.HttpServer` 起一个本地假网关，监听 `localhost` 随机端口：

- **AnthropicChatClientTest**：
  - 假网关捕获请求体 → 断言 `system` 在顶层、`messages` 只含 user/assistant、含 `max_tokens`、带 Bearer 头。
  - 假网关返回 `{"content":[{"type":"text","text":"..."}]}` → 断言返回该文本。
  - 假网关返回 500 / 非 JSON → 断言抛出的异常含原始片段。
- **MainTest（协议装配）**：不真发网络，只测 `LLM_PROTOCOL` → 选型逻辑。若抽成小工厂则测工厂；否则测一个可注入的选型方法。

## 5. 本次变更同时落位

- 已改未提交的 `OpenAiCompatibleChatClient` 解析诊断（try-catch 带响应片段）——作为本变更的"解析错误诊断增强"要求正式收编，不再游离在 openspec 外。

## 6. 边界

- `LLM_MAX_TOKENS` 未设时用默认 1024；用户真实跑时按自己套餐调。
- 假网关测试用 `localhost` 回环，确定性、无外部依赖，不违反"纯 JDK"约束。
- 本变更不改 `harness-loop` 冻结的引擎/事件层。