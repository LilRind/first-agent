# Design — ReAct 完整闭环 + Plan-and-Execute（智能体经典范式）

> 日期：2026-10-09
> 状态：brainstorming 定稿（grill-me 三轮收敛）
> 目标：基于个人学习项目 `first-agent-minimal`，落地第四章《智能体经典范式构建》的 ReAct 与 Plan-and-Execute 两种范式。现成 `AgentLoop` 已被认定为 ReAct 引擎（原生 tool-calls 协议），本需求 = **补齐完整闭环**（用户提问 → 思考/行动/观察 → Finish 收尾），并**从零实现 Plan-and-Execute**（规划 → 逐步可调工具执行）。
> 落点：新分支 `feature/paradigms-react-planexecute`（基点 `a0c198e`，AppConfig/.env 已提交推送，工作区干净）

---

## 一、背景与范围决策

### 为什么"ReAct 不再另起炉灶"

第四章 4.2 的教学点是**文本协议**（Thought/Action/`Finish[...]` + 正则解析 + 提示词脆弱性）。但项目现有 `AgentLoop` 已经实现了**原生 function-calling 协议的 ReAct**：

- 引擎 emit 完整事件流（`AgentEvent`）：assistant 文本 + tool 调用 + tool 结果 + agent 结束（`AgentLoop.java:47`）。
- 工具失败回填 `tool_use_error` 让模型自纠正（`AgentLoop.java:121`），非法 JSON 由 `SalvageParser` 兜底。
- `LlmProvider` 是唯一 seam（`LlmProvider.java:9`），`OpenAILlmProvider` 直接读结构化 `tool_calls`。

且工业界主流（Pi、Claude Code、Codex、DeepSeek-Harness 的循环共识）均采用**原生结构化 tool-calls**，不做文本 `Search[...]` 正则解析。第四章自己也指出正则解析的脆弱性（习题 2）。因此本项目**忠于工业界的原生协议**，第四章的"思考/行动/观察"作为**可观测轨迹**（`ReActTrace`）重建，而不是新的协议。

### 两个范式的本质差异（对照）

| | ReAct | Plan-and-Execute |
|---|---|---|
| 决策时机 | 走一步看一步（动态） | 先全局规划、再线性执行 |
| 工具 | 任意时刻可调 | 执行阶段每步可调（受控子循环内） |
| 状态 | 单条 append-only 历史 | 计划 + 前 i-1 步结果累积文本 |
| 停止 | 模型无工具调用即 end | 走完计划即止；最后一步结果 = 答案 |
| 已有基础 | `AgentLoop` 现成 | 全新实现（Planner + PlanExecutor） |

### 范围决策（grill-me 三轮收敛）

1. **ReAct 用模板协议（非文本协议）** —— 复用现有 `Message`/`LlmProvider`/`ToolRegistry`/`AgentEvent`；**引擎零改动**；新增可观测轨迹 `ReActTrace`。
2. **Plan-and-Execute 忠于"执行阶段可调工具"** —— 每步 = 一次受控 `AgentLoop` 子循环；v1 线性执行，**不做动态重规划**。
3. **本轮自包含** —— 不接跨会话记忆/压缩/持久化（SessionStore 不碰）；事件流作为将来接入的正规口子，**不关死**。
4. **跟随项目惯例** —— 新分支 + TDD（MockLlm 脚本驱动，离线确定性）+ spec/plan 文档 + 可跑 CLI demo。
5. **真实闭环成立** —— 必须给 `OpenAILlmProvider` 补发 `tools` 声明（当前请求体只有 model+messages，真实模型无从产生工具调用）。这是"能不能调工具"的硬前提。

---

## 二、目标形态

一句话：**ReAct 补齐一个"提问 → 思考 → 行动 → 观察 → Finish"的完整闭环且真实模型可跑；Plan-and-Execute 从零实现"规划 → 每步可调工具逐步执行"，两者都可观测、可测试、可 CLI 运行。**

### ReAct 完整闭环

```
用户提问
  → AgentLoop（ReAct 引擎，已有，零改动）
  → 每轮 agent emits 事件流
  → ReActTrace 订阅事件流，重建顺序轨迹：
       Thought(assistant 文本，空则如实显示) → Action(工具名[参数]) → Observation(工具结果) → Finish(最终答案)
  → ReActDemo CLI 打印轨迹 + 最终答案
  → OpenAILlmProvider 请求体带 tools → 真实模型可发起工具调用
```

### Plan-and-Execute

```
用户提问
  → Planner：LLM 生成计划（JSON 数组，lenient 解析 + 重试 N 次）
  → PlanExecutor 顺序执行每个 step：
       每步 = 新 `AgentLoop` 子循环（步级 system[角色+完整计划+前 i-1 步结果] + 当前步 user 目标）
       maxIterations 受限；子循环最终文本 = 该步结果
       步结果累积成文本流入下一步（状态管理）
  → 最后一步结果 = 最终答案
  → PlanSolveDemo CLI + 计划/步进事件（PlanStarted / PlanStepStarted / PlanStepEnded → telemetry span）
```

---

## 三、组件与文件

### 3.1 ReAct 闭环

| 模块 | 文件 | 职责 / 关键点 |
|---|---|---|
| 轨迹 | `ReActTrace.java` | 订阅 `AgentEvent` 流重建 Thought/Action/Observation/Finish 顺序轨迹；`ReActTrace.Step(thought, action, observation)` + `finish(answer)`；真实文本可能为空（模型静默发工具调），如实显示 `(无思考文本)` 不编造 |
| 工具 | `tools/CalculatorTool.java` | 离线确定性计算工具（第四章节习/习题 3 的点）：接收表达式或含 a/b 两数的 JSON，返回计算结果；演示 ReAct"知识不足→调工具→观察→总结" |
| provider | `llm/OpenAILlmProvider.java`（**改**） | 请求体补 `tools` 数组：`AgentTool.name + description`（功能声明），`parameters` 用宽松 object schema（完整 input_schema 列改善项）；与现有 `toOpenAIMessages` 并列加 `tools` 字段 |
| app | `app/ReActDemo.java` | 装配 CalculatorTool + EchoTool，触发完整闭环，打印 `ReActTrace` + 最终答案；无 key 时 MockLlm 兜底 |
| 测试 | `test/.../ReActTraceTest.java`、`ReActDemoTest.java` | 断言"提问 → ≥1 次工具 → Finish"完整闭环 + 轨迹顺序 |

### 3.2 Plan-and-Execute

| 模块 | 文件 | 职责 / 关键点 |
|---|---|---|
| 规划 | `plan/Planner.java` | `plan(question) → List<String>`：强制输出 JSON 数组，lenient 解析（提取 `[`..`]` 区间）失败重试（最多 2 次），仍失败返回空列表 |
| 执行 | `plan/PlanExecutor.java` | `execute(question, plan) → String`：每步 `new AgentLoop(stepSystem, llm, tools, STEP_MAX_TURNS, endOnNoToolCall)`，子循环最终文本 = 该步结果；累积「步骤 i: {step}\n结果: {text}」流入下一步；返回最后一步结果 |
| 事件 | `AgentEvent.java`（**改**） | sealed 接口内新增 `PlanStarted(question, plan)` / `PlanStepStarted(index, step)` / `PlanStepEnded(index, step, result)`；引擎纯 emit 不打印 |
| 观测 | `AgentEventTelemetryBridge.java`（**改**） | 新增三分支 → 对应 span |
| app | `app/PlanSolveDemo.java` | 装配 CalculatorTool，跑"规划 → 执行"两阶段，打印计划 + 步进结果 + 最终答案 |
| 测试 | `test/.../PlannerTest.java`、`PlanExecutorTest.java`、`PlanSolveDemoTest.java` | 计划解析/重试/失败回退；逐步状态累积；事件时序 |

### 3.3 通用

| 文件 | 职责 |
|---|---|
| `docs/superpowers/specs/2026-10-09-paradigms-react-planexecute-design.md` | 本文件 |
| `docs/superpowers/plans/2026-10-09-paradigms-react-planexecute.md` | 分任务 TDD 计划 |
| `README.md`（**改**，可选） | 补两个范式说明 + demo 运行方式 |

---

## 四、关键设计细节

### 4.1 OpenAILlmProvider 补 tools（真实闭环的硬前提）

现状：`OpenAILlmProvider.java:58-60` 请求体只 `model` + `messages`，模型无从产生工具调用。

改动：
```java
ArrayNode tools = M.createArrayNode();
for (AgentTool t : registered){                       // registered: 由构造注入
    ObjectNode fn = tools.addObject().putObject("function");
    fn.put("name", t.name());
    fn.put("description", t.description());
    fn.set("parameters", LOOSE_PARAMS);               // {"type":"object","properties":{}},
}
body.set("tools", tools);
```

- 需要把工具注册表传给 provider：`OpenAILlmProvider(AppConfig, List<ToolSpec>)`，其中 `ToolSpec(name, description)` 由 `ToolRegistry` 导出（`stream().map(AgentTool → ToolSpec)`）。不把 `AgentTool` 强塞给 provider 层（保持 seam 干净）。
- 兼容性：无 tools 时行为不变（现有 `MinimalAgent` 等仍工作）；`LOOSE_PARAMS` 让模型能发起调用，参数形状由 `description` 引导（完整 `input_schema` 为改善项）。
- `AssistantReply` 原有 `toolCalls` 解析已支持（`OpenAILlmProvider.java:82`），补 tools 后真实模型返回的 `tool_calls` 走同一路径。

### 4.2 ReActTrace 的轨迹重建

```java
public final class ReActTrace implements Consumer<AgentEvent> {
    List<Step> steps;          // Step(thought, action, observation)；action 为 "工具名[参数]"
    String finish;             // 最终答案（AgentEnded 后由外壳回填 execute 返回值）
    // subscribe: 喂 AgentLoop.execute(input, this)
    // 遍历 MessageEnded(ASSISTANT) → thought/action；MessageEnded(TOOL result) → 回填上一步 observation
}
```

- 引擎零改动：`AgentLoop.execute(input, ReActTrace)` 复用现有 `Consumer<AgentEvent>` 出口（`AgentLoop.java:47`）。
- 诚实边界：原生协议下 assistant 的 text **可能为空**（模型静默发工具调）；轨迹显示 `(无思考文本)`。
- ReAct "Finish" 即最后一条无工具 assistant 文本（`execute` 的返回值），与第四章 `Finish[...]` 语义等价。

### 4.3 Planner 的计划格式与解析

- 提示词：角色 + "只输出 JSON 数组，每个元素是需要执行的一个步骤描述"。
- lenient 解析：`SalvageParser` 风格，截取首个 `[` 到末个 `]`，Jackson `readTree` → `List<String>`；失败重试（最多 2 次）、仍失败返回 `List.of()`（Agent 打印"计划生成失败"终止）。
- 不采用第四章的 `"```python\n[...]```"` 栅栏格式（习题 2 已指出其脆弱性）。

### 4.4 PlanExecutor 的执行子循环

```java
String execute(question, plan) {
    StringBuilder history = new StringBuilder();
    String result = "";
    for (int i = 0; i < plan.size(); i++) {
        emit PlanStepStarted(i, plan.get(i));
        String stepSystem = """
            你是一个执行专家。严格按照计划逐步解决问题。
            原始问题: %s
            完整计划: %s
            已完成步骤与结果:
            %s
            当前步骤: %s
            如需工具请直接调用；输出仅针对当前步骤的结果文本。""".formatted(...);
        AgentLoop stepLoop = new AgentLoop(stepSystem, llm, tools, STEP_MAX_TURNS(5), LoopStrategy.endOnNoToolCall());
        result = stepLoop.execute(plan.get(i));
        history.append("步骤 ").append(i+1).append(": ").append(plan.get(i))
               .append("\n结果: ").append(result).append("\n\n");
        emit PlanStepEnded(i, plan.get(i), result);
    }
    return result;   // 最后一步结果 = 最终答案
}
```

- 每步子循环天然获得"可调工具"（子循环内模型可自由发工具调用），历史只累积**结果文本**（省上下文）。
- v1 不做动态重规划/修复（习题 4 的"重规划机制"为改善项）。
- 最终答案 = 最后一步结果（`PlanExec` 的 `Executor` 语义）；独立汇总调用为 v1 后改善项。

### 4.5 事件扩展后的 sealed 接口

```java
public sealed interface AgentEvent {
    ... existing ...
    record PlanStarted(String question, List<String> plan) implements AgentEvent {}
    record PlanStepStarted(int index, String step) implements AgentEvent {}
    record PlanStepEnded(int index, String step, String result) implements AgentEvent {}
}
```
`AgentEventTelemetryBridge` 对应补三分支（`plan.start` / `plan.step.start` / `plan.step.end`）。shell switch 全事件覆盖随之补全。

---

## 五、验收（AC）

1. `mvn test` 全绿（新增 6 个测试类 + 现有全部不回归）。
2. **ReAct 完整闭环**：`ReActTraceTest` 断言"提问 → 至少一次工具调用 → Finish"轨迹顺序正确；无工具的直接回答只有一条 Finish。
3. **真实闭环**：`OpenAILlmProvider` 请求体含 `tools`（fake HTTP / 单测断言 body JSON 含 function 声明）；无 key 时集成测试自动跳过（沿用 Assumption）。
4. **Plan-and-Execute**：`PlannerTest` 覆盖正常解析 / 坏 JSON 重试 / 重试耗尽返回空；`PlanExecutorTest` 断言每步子循环执行、结果累积、末步结果即答案。
5. **事件可观测**：事件时序测试断言 `PlanStarted → PlanStepStarted/Ended×n → ...`，桥映射成 span。
6. **CLI demo**：`ReActDemo` / `PlanSolveDemo` 均可运行，输出完整闭环/计划+步进结果（无 key 时 MockLlm 兜底）。
7. **隔离**：未触碰 AppConfig 批文件；新代码全部新增/只在 `OpenAILlmProvider`/`AgentEvent`/`AgentEventTelemetryBridge` 扩点。

## 六、明确剪掉 / 延后（改善项清单）

- **最终答案独立汇总调用**：v1 用"末步结果"，汇总合并多步为更稳最终答案 → v2。
- **动态重规划 / 修复**：执行中发现步骤不可行时调整计划 → v2。
- **接跨会话记忆 / 压缩 / 可观测持久化**：本轮自包含；事件流已留口，后续 `MemoryRecallStrategy` 类策略管线接入 → v2。
- **完整 `input_schema`**：当前 `parameters` 宽松 object，模型靠 description 猜参数 → 工具契约加 schema（`AgentTool` 接口扩展） → v2。
- **合并 tool-module-mvp 的 Bash/Read/Grep 工具**：跨分支合流，预留方向。
- **Executor 节点化省 token**：每步两次调用（子循环一次 + 结果单次生成）→ 后续按需。
- **流式 / declareToolChanges / retry / 并发**：沿用 AgentLoop 重构已剪项，不引入。

## 七、参考（工业界对照，以项目既有研读为准）

- Pi `agent-loop.ts` runLoop（双 while + prepareRequest 返回请求消息）—— 本项目 `AgentLoop` 已对齐。
- pi `packages/telemetry`（span/recorder）—— 本项目 `TelemetryRecorder` 已对齐。
- Claude Code / Codex / DeepSeek-Harness：原生 structured tool-calls 协议 + append-only 会话日志（`AgentLoop.java` 注释与既有研读一致）。
- 第四章 4.2/4.3 伪代码：作为"范式语义"参考，**协议不照搬**（文本解析改用原生 tool-calls / JSON）。
- 本章习题 2/3/4 作为后续改善项的直接来源。