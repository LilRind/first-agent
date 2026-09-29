# travel-agent（first-agent）

一个**从零手写、纯 JDK【零第三方依赖】的 ReAct 旅行智能体**——演示 Thought → Action → Observation 的决策循环，如何让一个真实 LLM 分步使用工具完成任务。

本仓库对应《AIGC 应用构建》"1.3 动手体验：5 分钟实现第一个智能体"（原 Python 案例）的 **Java 移植版**，并引入 OpenSpec 做规范驱动开发（spec-driven）。

---

## 它能做什么

以 "帮我查今天北京的天气，并根据天气推荐一个合适的旅游景点" 为例，智能体会：

1. **Thought**：用户想了解北京旅行建议，先查天气
2. **Action**: `get_weather(city="北京")` → 调 [wttr.in](https://wttr.in) 拿实时天气
3. **Observation**: `北京当前天气:Sunny，气温26摄氏度`
4. **Thought**：已知天气，再根据天气推荐景点
5. **Action**: `get_attraction(city="北京", weather="Sunny")` → 调 [Tavily Search](https://tavily.com) 搜景点
6. **Observation**: 返回景点推荐
7. **Finish[...]**: 综合信息给出最终答案

整个循环由 `ReActAgent` 引擎驱动，把**全量对话历史**（含每一步观测）回喂给 LLM，使它能"记得"自己已经查过什么。

---

## 模块结构

```
src/
├── agent/                        # ReAct 引擎
│   ├── ReActAgent.java           # 主循环：全历史回喂 + maxRounds 保险丝
│   ├── ActionParser.java         # 把 LLM 的 Thought/Action 解析成结构化动作
│   └── Main.java                 # 入口：组装工具 + LLM，二选一运行
├── llm/                          # LLM 客户端
│   ├── ChatClient.java           # 抽象：喂消息列表，收文本
│   ├── OpenAiCompatibleChatClient.java  # 真实客户端：直连 OpenAI 兼容接口
│   └── MockChatClient.java       # 离线 mock：不联网也能跑通循环
├── tools/                        # 工具层
│   ├── Tool.java                 # 工具抽象接口
│   ├── ToolRegistry.java         # 工具注册表（等价 Python 的 available_tools dict）
│   ├── WeatherTool.java          # 查天气（wttr.in）
│   └── AttractionTool.java       # 荐景点（Tavily Search）
└── util/
    └── Json.java                 # 手写极简 JSON 解析器（零依赖）
```

---

## 快速开始

环境要求：**JDK 17+**（项目零第三方依赖，无需 Maven/Gradle）。

### 离线演示（不需要任何 API key）

```bash
bash run-mock.sh
# 或用预设脚本：java -cp out agent.Main mock "推荐一下北京"
```

mock 模式用 `MockChatClient` 按预设脚本依次驱动：查天气 → 荐景点 → Finish，完整复现 ReAct 循环。

### 真实 LLM（需配置环境变量）

```bash
export LLM_API_KEY=<your key>
export LLM_BASE_URL=https://api.openai.com/v1   # 可换任意 OpenAI 兼容厂商
export LLM_MODEL=gpt-3.5-turbo
export TAVILY_API_KEY=<your tavily key>          # get_attraction 需要

javac -encoding UTF-8 -d out $(find src -name '*.java')
java -Dfile.encoding=UTF-8 -cp out agent.Main "我想去北京玩，帮我推荐一下？"
```

> 三个 LLM 配置都从环境变量读取，可切换到 DeepSeek / Kimi / GLM 等任意 OpenAI 兼容服务而不改代码。
>
> 不配 `LLM_API_KEY` 时 Main 会自动回退到 mock 模式。

---

## 设计要点（为什么这样写）

- **为什么手写 JSON 解析而不用 Jackson？** 项目目标是"纯 JDK 零依赖手写 agent"，拆轮子看懂原理；HTTP 用内置 `HttpClient`，JSON 用一个够用的自写解析器。生产项目请换 Jackson/Gson。
- **为什么用 `Tool` 接口 + `ToolRegistry`？** 等价 Python 的 `available_tools` dict，主循环只认 `name → execute(arguments)`，不关心工具内部实现。
- **为什么全历史回喂？** 只发最后一句话等于让 LLM 失忆；必须把 system 说明书 + 用户问题 + 之前所有 Thought/Action/观测一起发给 LLM，它才能接着思考下一步。
- **为什么 hand-write 客户端而非官方 SDK？** Java 对 wttr.in/Tavily/OpenAI 都没有统一 SDK，直接对它们的 HTTP 接口手写实现即可，等价且透明。

---

## 规范驱动开发（OpenSpec）

本项目用 [OpenSpec](https://github.com/Fission-AI/OpenSpec) 做 spec-driven 开发：行为契约（spec）先行，设计、任务随后，可验证。

```
openspec/
├── changes/
│   └── feature-travel-agent/     # 当前变更
│       ├── proposal.md           # 为什么做
│       ├── specs/travel-agent/spec.md  # 行为契约（ReAct 循环/工具/LLM/mock）
│       ├── design.md             # 怎么做（设计决策）
│       └── tasks.md              # 实施任务（含验证方式）
```

---

## License

MIT