# Proposal

## Why

《AIGC 应用构建》一书的 "1.3 动手体验：5 分钟实现第一个智能体" 用 Python（`requests` + `tavily-python` + `openai` SDK）演示了一个 ReAct 风格的真实旅行助手。本项目要把它原样移植到 Java，用纯 JDK 手写一个零第三方依赖的等价实现，既讲透 ReAct 原理，又让读者能本地离线跑通。

## What Changes

- 将 Python 的 `get_weather(city)` 移植为 Java `WeatherTool`（调用 wttr.in `?format=j1`，返回自然语言天气描述）。
- 将 Python 的 `get_attraction(city, weather)` 移植为 Java `AttractionTool`（调用 Tavily HTTP API，因 Java 无官方 SDK，直接拼 JSON 请求体等价于 `tavily.search`）。
- 将 Python 的 `OpenAICompatibleClient` 移植为 `OpenAiCompatibleChatClient`（对着 OpenAI 兼容的 Chat Completions HTTP 接口手写客户端，等价于 `openai` SDK；key/base_url/model 从环境变量读取）。
- 将 Python 的 `available_tools` dict 移植为 `Tool` 接口 + `ToolRegistry` 注册表。
- 将 Python 主循环（解析 Thought/Action、派发工具、回喂 Observation、`Finish` 收尾、轮数上限）移植为 `ReActAgent` + `ActionParser`。
- 新增 Python 版本没有的 `MockChatClient`：不联网即可离线跑通整个循环，便于验证和学习。
- 新增手写极简 `Json` 解析器，支撑天气/Tavily/LLM 返回的解析（保持"纯 JDK 零依赖"目标）。

## Capabilities

### New Capabilities
- `travel-agent`: 一个由真实 LLM 驱动的 ReAct 旅行助手——能按 Thought-Action-Observation 循环分步解决"查天气→荐景点"类任务，支持天气查询、景点推荐两个工具，以及离线 mock 演示模式。

### Modified Capabilities
<!-- 无：specs 库存为空，本 change 为全新引入 -->

## Impact

- 代码：`src/agent/{ReActAgent,ActionParser,Main}`、`src/llm/{ChatClient,OpenAiCompatibleChatClient,MockChatClient}`、`src/tools/{Tool,ToolRegistry,WeatherTool,AttractionTool}`、`src/util/Json`（均已存在于基线提交，本 change 固化其行为契约）。
- 外部依赖：wttr.in（天气）、Tavily Search API（景点推荐）、任一家 OpenAI 兼容 LLM 服务。运行需 `TAVILY_API_KEY`、`LLM_API_KEY`（mock 模式可不配）。
- 系统：Windows 本地 `java -cp out agent.Main [mock] [问题]` 运行。
