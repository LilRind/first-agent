# first-agent (Agent Loop)

A simplified Coding Agent inspired by the implementation of Pi

手写最小 Agent Loop：模型驱动 ReAct 循环，聚焦"框架藏起的那 5 个问题"。以 pi / claude-code / codex / deepseek-harness 的循环共识为蓝本，不引任何 agent 框架。

## 范式（智能体经典范式）

### ReAct 完整闭环

`AgentLoop` 本身即原生 tool-calls 协议下的 ReAct 引擎。`ReActTrace` 订阅事件流，重建 `Thought → Action → Observation → Finish` 顺序轨迹（可观测，不干预引擎）。

```bash
mvn -o -q compile
# 跑 demo（无 key 时 MockLlm 兜底；配 .env 的 OPENAI_API_KEY 则走真实模型）
java -cp "target/classes:$(cat cp.txt)" dev.firstagent.app.ReActDemo
```

### Plan-and-Execute

`Planner` 一次性生成行动计划（JSON 数组，lenient 解析 + 重试）；`PlanExecutor` 每步作为一个受控 `AgentLoop` 子循环逐步执行，历史只累积结果文本，最后一步结果 = 最终答案。

```bash
java -cp "target/classes:$(cat cp.txt)" dev.firstagent.app.PlanSolveDemo
```

## 测试

```bash
mvn -o test
```
