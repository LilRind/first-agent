# Design

## Context

See proposal.md - Why: 把《AIGC 应用构建》"5 分钟实现第一个智能体" 的 Python 案例移植为 Java，并已在基线提交落地一份零第三方依赖的手写实现。本设计说明这套实现的模块切分与关键技术选择，作为后续改动（apply）与理解结构的依据。

约束：严格零第三方依赖（纯 JDK），代码结构力求与 Python 教程一一对应便于对照学习当下已定；不引入构建工具，直接 `javac` 编译到 `out/`。

## Goals / Non-Goals

**Goals:**
- 讲透 ReAct 循环：Thought → Action → Observation，全历史回喂。引入了 `maxRounds` 保险丝。
- 明确"工具注册表 + 工具接口"抽象，替代 Python 的 `available_tools` dict。
- 支持双模式运行：真实 LLM（OpenAI 兼容接口）与离线 Mock。

**Non-Goals:**
- 不引入第三方库（无 Spring、无 Jackson、无 HTTP client 框架）。
- 不做结构化输出/函数调用协议——沿用教程的手写正则解析，重在原理。
- 不做 GUI、多轮状态持久化、或生产级容错。

## Decisions

1. **手写 `Json` 解析器而非 Jackson。**
   教程 Python 用 `requests.get().json()`；Java 若无第三方库就没有现成 JSON。选择自写一个递归下降极简解析器（`util.Json`）。替代方案（引 Jackson/Gson）违背"零依赖"目标。折衷：解析器足够覆盖 wttr.in、Tavily、Chat Completions 三种返回即可，生产项目应换 Jackson。见 `src/util/Json.java:652`。

2. **用 JDK `java.net.http.HttpClient` 替代 Python `requests`。**
   教程 `get_weather` 用 `requests.get("https://wttr.in/{city}?format=j1")`；Java 用内置 `HttpClient` 等价实现。替代方案：OkHttp/Spring RestTemplate，均违背零依赖。见 `src/tools/WeatherTool.java:585`。

3. **Tavily 直接用 HTTP API 而非 SDK。**
   教程用 `tavily-python` SDK；Java 无官方 SDK。改为直接 POST 到 `https://api.tavily.com/search`，手工拼 JSON 请求体（`api_key`/`query`/`search_depth`/`include_answer`），等价于 SDK 的 `search()`。API key 读环境变量 `TAVILY_API_KEY`。见 `src/tools/AttractionTool.java:441`。

4. **LLM 客户端手写 OpenAI 兼容接口，而非官方 SDK。**
   教程用 `openai` SDK；Java 里 `OpenAiCompatibleChatClient` 直接对 `POST {base_url}/chat/completions` 发起请求，把消息列表拼成 JSON 数组，解析 `choices[0].message.content`。三个配置均从环境变量读（`LLM_API_KEY`/`LLM_BASE_URL`/`LLM_MODEL`），可换任意 OpenAI 兼容厂商。见 `src/llm/OpenAiCompatibleChatClient.java:321`。

5. **以 `Tool` 接口 + `ToolRegistry` 组织工具，替代 `available_tools` dict。**
   教程 `available_tools = {"get_weather": fn, ...}`；Java 每个工具实现 `Tool`（`name()`/`description()`/`execute(Map)`），注册表按名查找并派发，还提供 `describeTools()` 拼接进系统提示词。见 `src/tools/ToolRegistry.java:544`。

6. **`ActionParser` 用正则解析 Thought/Action，严格对齐教程格式。**
   与 Python `re.search` 逻辑一一对应：抠 `Action:` 行、识别 `Finish[xxx]`、拆工具名与 `key="value"` 参数。真实工程会换结构化输出，这里保留手写解析以求原理透明。见 `src/agent/ActionParser.java:23`。

7. **新增 `MockChatClient`（Python 教程没有）。**
   真实调 LLM 需 key/联网/花钱。它按预设脚本依次吐回复（查天气→荐景点→Finish），使完整的 ReAct 循环可在本地离线验证。这是"依赖注入 + 假实现"手法，也是教程未覆盖的增强。见 `src/llm/MockChatClient.java:290`。

8. **`ReActAgent` 是全历史回喂的引擎。**
   每轮把 `List<ChatClient.Message>`（system 说明书 + 用户问题 + 全部历史 + 观测）整体发给 LLM，保证 LLM"记得"已查过天气。`maxRounds`（默认 5）作保险丝。unknown 工具、不可解析 Action 均优雅降级而非崩溃。见 `src/agent/ReActAgent.java:155`。

## Risks / Trade-offs

- [手写正则解析 Thought/Action 对格式敏感] → Mock 模式下已固化脚本兜底；未来可切结构化输出。
- [手写 `Json` 解析器覆盖不全] → 仅限三种 API 返回；范围外需求应换 Jackson。
- [真实工具调用依赖外部网络与 key（wttr.in/Tavily/LLM）] → mock 模式可离线验证循环逻辑本身。
- [环境下未配 key 时真实客户端抛异常] → Main 在无 `LLM_API_KEY` 时自动回退 Mock，见 `src/agent/Main.java:100`。

## Open Questions

<!-- 无 -->