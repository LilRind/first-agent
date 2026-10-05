# Design — 最小 Agent（工具 + 长期记忆 + 压缩 + telemetry + 集成测试）MVP

> 日期：2026-10-05
> 状态：已批准（brainstorming 定稿）
> 目标：在 `first-agent` 基础上，基于**已合并进 main 的 session-mvp**（JSONL 追加日志 + 投影 + 压缩 seam）做一个**最小但真实**的 Agent：能**用工具**、**跨会话长期记忆召回**、**上下文压缩**、**span 级 telemetry 可观测**、**集成小测试框架可测试**，全部简化，**先跑起来看到结果**。
> 落点：新隔离 worktree `first-agent-minimal`，分支 `feature/minimal-agent`，基座 `feature/first-agent`（已含 AgentLoop 重构 + session 层）。

---

## 一、背景与范围决策

### 为什么基于 session-mvp 而非全独立

用户先核对 session 分支合并情况，结论：
- **session-mvp 已合并进本地 `feature/first-agent`**（8 笔提交），但**未推远程**，也**不在 `feature/native-fc`**（我的重构基座）。
- 我的 **AgentLoop 重构（native-fc）也已并入本地 main**。
- 按用户规则："session 已合并进本地" → 走"**参考它定义的方式**"这一支，而不是全独立简化版。

因此复用 session-mvp 已经证明有效的部分，只**补缺 / 增强弱的部分**。

### session-mvp 已给好的（复用，不重写）
| 类 | 复用点 |
|---|---|
| `SessionStore` | JSONL append-only 持久化（create/append/load/list/findMostRecent/delete/rename），崩溃安全、lenient 加载、按 cwd 分组 |
| `SessionProjector` | "日志 → LLM 消息" 投影，经 `ContextCompactor` seam |
| `ContextCompactor` | **压缩 seam**（当前只有 `Noop` 实现——正是我们要补真实现的地方） |
| `MessageEntry`/`Session`/`SessionHeader`/`StoredMessage` | 追加式日志链模型、只读视图 |
| `AgentLoop`/`AgentEvent`/`LoopStrategy`/`ToolRegistry`/`EchoTool`+`FailTool`/`MockLlm` | 双 while + 4 策略钩子 + 事件 + 简单工具 + 脚本 LLM |

### session-mvp 缺 / 弱的（本次补 / 改）
1. `ContextCompactor` 只有 **Noop** —— 压缩逻辑未实现（只有口子）。
2. **无跨会话记忆召回** —— SessionStore 只存单条线性对话，没有 claude-code/nanobot 那种"把先前会话的有用事实注入回来"。
3. **无 telemetry** —— 只有裸 `AgentEvent` emit，没有 pi 那种 span/trace + 属性可聚合。
4. **无集成测试 harness** —— 只有各类的 JUnit 单测，没有"预置会话 → 跑 → 断言 span 时序 + 记忆注入 + 压缩发生"的端到端。
5. **SessionStore 未接进 AgentLoop** —— 是独立持久化层，本次要把它接进循环。

### 方案选型（brainstorming 已确认）
| 设计点 | 选型 | 理由 |
|---|---|---|
| 记忆范围 | **跨会话召回**（B1 最近会话摘要注入） | 启动 `findMostRecent(cwd)` → 压缩投影 → 作为"长期记忆"前缀注入；claude-code 恢复上下文简化版 |
| 压缩实现 | **朴素折叠**（A1） | TokenEstimator（字符/4 启发）超阈值 → 折叠旧 entry 成摘要 entry；确定、无 LLM 调用、可断言 |
| telemetry | **内存录制 + 可断言** | pi 的 `TelemetrySpan` + in-memory recorder 简化版；测试断言 span 时序 |
| worktree 基座 | **feature/first-agent** | 已含重构 + session 层，直接复用，不移植 |
| 集成测试框架 | **JUnit + fixture 驱动** | 沿用 Maven 已有 JUnit，加一个预置会话→断言三点的集成测试，等价 pi 的 fixtures 思路，不另引框架 |

---

## 二、目标形态（简化但真实）

一句话：**把一个"调用工具→收尾"的 AgentLoop，跑在"持久化 + 跨会话召回 + 压缩 + telemetry"之上，并用集成测试锁住这四条链路。**

### 唯一的小改动（对齐 pi，让压缩/记忆真生效）

`LoopStrategy.prepareRequest` 从 `void` 改为返回 `List<Message>`（默认原样返回）：

```java
// 改前
default void prepareRequest(List<Message> history) {}

// 改后 —— 返回真正发给模型的输入；默认恒等（pass-through）
default List<Message> prepareRequest(List<Message> history) { return history; }
```

`AgentLoop` 对应改为用它的返回值当 `llm.chat` 输入：
```java
List<Message> requestMessages = strategy.prepareRequest(history);
AssistantReply reply = llm.chat(requestMessages);
```

- **为什么**：目前 `prepareRequest` 是 no-op void，压缩/记忆改了它也传不到 `llm.chat`。改成返回"实际要发的消息"，压缩（超阈值折叠）与记忆（注入）才能**真正缩小 / 扩充发给模型的输入**，而不是只影响重放。
- **安全**：默认返回 `history` 恒等 → 现有 `AgentLoopTest` 语义完全不变；双 while 骨架不动。

---

## 三、组件与文件（新增，都在 `dev/firstagent/`）

| 模块 | 文件 | 职责 / 关键点 |
|---|---|---|
| telemetry | `telemetry/TelemetrySpan.java` | record：`name`、`attributes Map<String,String>`、`startNanos`、`endNanos`、可选 `parentId`。对齐 pi `packages/telemetry` 的 `TelemetrySpan` |
| telemetry | `telemetry/TelemetryRecorder.java` | in-memory 录制已完成 span 列表（可断言）、可选打印；`startSpan/endSpan(attrs)`；线程安全 |
| telemetry | `telemetry/AgentEventTelemetryBridge.java` | 订阅 `AgentEvent` → 录 span（turn/message/tool/agent 级）；额外对"记忆命中"与"压缩发生"各录一条带属性 span |
| 压缩 | `session/TokenEstimator.java` | 朴素启发：`字符数/4` 估 token；超阈值即需压缩 |
| 压缩 | `session/SummarizingCompactor.java` | 实现 `ContextCompactor`：TokenEstimator 超阈值 → 把旧 entry 折叠成一条摘要 `MessageEntry`（v1 放占位摘要文本），原始日志**绝不删除**；低于阈值 → 原样返回（`Optional.empty`） |
| 记忆 | `memory/MemoryRecallStrategy.java` | 一个 `LoopStrategy` 封装：① 首轮 `SessionStore.findMostRecent(cwd)` → `SessionProjector(compactor)` 投影 → 把旧会话摘要作为"长期记忆"注入 `prepareRequest`；② 每轮把 AgentLoop 产出的 `Message` append 进 SessionStore 持久化；③ `prepareRequest` 返回"记忆注入 + 压缩投影"后的消息列表 |
| app | `app/MinimalAgent.java` | 装配以上 + CLI `main`：跑一轮对话，用 `MockLlm`（脚本）打 telemetry + 最终答案；可注入 `OpenAILlmProvider` 换真模型（可选） |

### 装配（`MinimalAgent`）
```java
SessionStore store      = new SessionStore(Path.of("./sessions"));   // 复用
ContextCompactor comp   = new SummarizingCompactor(new TokenEstimator(512), new NoopSummaryProvider());
MemoryRecallStrategy strat = new MemoryRecallStrategy(store, new SessionProjector(comp), cwd);
AgentLoop loop          = new AgentLoop(SYSTEM, llm, tools, maxIterations, strat);
TelemetryRecorder rec   = new TelemetryRecorder();
new AgentEventTelemetryBridge(rec);        // 订阅事件
String answer = loop.execute(userInput);   // 跑闭环
rec.prettyPrint();                         // CLI 打印 span 列表
```

---

## 四、数据流

```
CLI → MinimalAgent.run(userInput, cwd)
  → MemoryRecallStrategy 首轮：findMostRecent(cwd) → project(compactor) → 注入"长期记忆"摘要
  → AgentLoop 双 while 跑：
       每轮 AgentEvent → TelemetryBridge 录 span（turn/message/tool/agent）
       每轮把产出的 Message append 进 SessionStore（持久化，崩溃安全）
       prepareRequest → SessionProjector(compactor).project → 超阈值则折叠 → 发给 llm.chat
  → 返回最终答案
  → CLI 打印 telemetry span 列表 + 答案（test 用 recorder 断言）
```

---

## 五、错误处理

- `SessionIoException`（session-mvp 已有）在召回/持久化路径被捕获 → 降级：记忆召回失败则跳过注入，持久化失败不影响本轮回答。
- telemetry span：`endSpan` 记 onException，异常也不吞掉崩溃（span 补一条 `error=true` 属性）。
- 压缩：`SummarizingCompactor` 若折叠失败 → 回退原始完整历史（`Optional.empty`），不崩循环。

---

## 六、测试（集成小测试框架 = JUnit + fixture 驱动）

| 文件 | 断言点 |
|---|---|
| `session/SummarizingCompactorTest` | 超阈值折叠出摘要 entry；低于阈值不折叠；原始日志条目数不变（绝不删） |
| `telemetry/TelemetryRecorderTest` | span 录制/时序/属性断言 |
| `memory/MemoryRecallIntegrationTest`（fixture） | **预置一段旧会话** → 跑 `MemoryRecallStrategy` 驱动的 AgentLoop → 断言 **a)** 旧会话摘要被注入历史（跨会话记忆生效）；**b)** telemetry span 时序（turn_start→message→tool→turn_end→agent_end + 记忆命中/压缩各一条）；**c)** 超阈值时压缩折叠发生 |

这三点正好锁住"能用工具 + 长期记忆 + 压缩 + 可观测 + 可测试"的可观测与可测试。

---

## 七、验收（AC）

1. `mvn test` 全绿，含 `MemoryRecallIntegrationTest` 三条断言 + 两个单测。
2. `MinimalAgent` CLI 用 `MockLlm` 跑通一个"调用工具 → 收尾"闭环，打印 telemetry span 列表 + 最终答案。
3. 跨会话验证：先跑一次（存下会话），再跑第二次 → 第二次历史里能看到第一次的压缩摘要被召回。
4. `LoopStrategy.prepareRequest` 改为返回 `List<Message>` 后，现有 `AgentLoopTest` 仍全绿（默认恒等）。

---

## 八、明确剪掉 / 延后（保持简单）

- **B2 按相关性/关键词 Top-K 召回**：v1 只做"最近会话摘要注入"，相关性打分列 v2。
- **A2 LLM 真摘要**：v1 压缩用占位摘要文本（`NoopSummaryProvider`），真摘要留 `SummaryProvider` seam 后续接。
- **真实工具**：工具模块由另一进程（`feature/tool-module-mvp`）负责，本最小 Agent 只用 `EchoTool`/`FailTool` 演示工具链路，不重复。
- **流式、declareToolChanges、retry、并发**：沿用 AgentLoop 重构已剪项，不引入。
- **真 provider**：`MockLlm` 足以"先跑起来看到结果"；`OpenAILlmProvider` 注入留作可选开关。

---

## 九、关联材料

- 复用事实源：session-mvp（`SessionStore`/`SessionProjector`/`ContextCompactor` seam）已并进 `feature/first-agent`。
- 参考：Pi `packages/telemetry/src/index.ts`（span/attributes/recorder）、`agent-loop.ts` runLoop（双 while + prepareRequest 返回请求消息）。
- 同仓既有 spec：`docs/superpowers/specs/2026-10-05-session-conversation-mvp-design.md`、`2026-10-05-pi-tool-module-mvp-design.md`。
