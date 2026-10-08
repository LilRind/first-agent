# first-agent-minimal

个人练手的 AI Coding Agent（+ 长期记忆 / 压缩 / telemetry / 可测试），以 Pi 为蓝本简化实现。

## 项目速览

- Maven + Java 17，包 `dev.firstagent`
- 引擎/策略/工具/LLM/会话/记忆/遥测分层（`AgentLoop` + `LoopStrategy` + `ToolRegistry` + `LlmProvider` + `SessionStore` + `MemoryRecallStrategy` + `TelemetryRecorder`）
- 真模型走 `.env` 配置（`OPENAI_API_KEY` / `OPENAI_BASE_URL` / `OPENAI_MODEL`），未配 key 自动退回 `MockLlm` 离线演示
- 测试：JUnit 5，`mvn test` 全绿（含集成测试 `MemoryRecallIntegrationTest`）

## 常用命令

- 跑全部测试：`mvn test`
- 跑 CLI（真模型/mock 自动按 `.env` 决定）：`mvn exec:java -Dexec.mainClass="dev.firstagent.app.MinimalAgent"`

## 开发习惯

- 改完跑 `mvn test` 再提交
- 提交身份：本仓库 local 覆盖为 `LilRind <1337476737@qq.com>`（不要在命令里写全局 gitconfig 的引号身份）
- 推送前 grep 隐私：禁止本地绝对路径 / API key / `sk-`
- 先读 `docs/superpowers/specs/` 与 `docs/superpowers/plans/` 再动大改

## Agent skills

### Issue tracker

Issues live as local markdown files under `.scratch/<feature>/` (one file per ticket). See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` + `docs/adr/` at repo root (created lazily by `/domain-modeling`). See `docs/agents/domain.md`.