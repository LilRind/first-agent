# Proposal — Add minimal Agent Loop (first-agent)

## Status: Draft (awaiting user freeze)

## Why
以 pi / claude-code / codex / deepseek-harness 的循环共识为蓝本，手写一个**最小 Agent Loop**。
目的是亲手处理"框架帮你藏起来的问题"，打通对 agent 运行态的理解（决策循环、状态、工具、失败处理、防无限循环、历史维护）。

## Problem
不引任何 agent 框架（四生产项目皆手写循环，仅引 LLM 提供商 SDK）。
聚焦 **Agent Loop 层**，先不碰 CLI / MCP / 记忆 / 权限。

## Non-goals (out of scope v0)
- Step/turn 分解（v0 用单层 loop）
- JSONL 跨重启持久化、checkpoint（二期）
- 多 provider、多 agent、并行工具调度、CLI/UI、MCP、权限系统
- 面试/业务逻辑

## Scope (in scope v0)
- 模型驱动 ReAct 循环（AgentLoop）
- 消息模型（system/user/assistant/tool）+ append-only 内存历史
- Tool 契约 + 注册表 + 执行失败闭环
- LLM adapter seam：Mock（测试）+ 真实 OpenAI 兼容 provider（E2）
- JSON salvage 简化（截断/非法 → 回填"参数不完整请重发"，不崩）
- maxTurns 防无限循环
- JUnit 测试驱动，`mvn test` 跑通

## Language / toolchain
Java 17（目标），Maven。本机当前 JDK8 无 mvn，需先装 JDK17 + Maven 才能 `mvn test`。