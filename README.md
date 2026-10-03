# first-agent (Agent Loop)

手写最小模型驱动 Agent Loop demo，亲手处理"框架帮你藏起来的那 5 个问题"：
1. 提示词/结构化输出 —— 工具契约自描述（`AgentTool`），喂给模型即可用
2. 解析 LLM 输出的脆弱性 —— `SalvageParser`（D2 简化：非法/截断 → 回填"参数不完整请重发"）
3. 工具调用失败 —— `executeTool` 错也回填 `tool_use_error`，模型自纠正
4. 无限循环 —— `maxIterations` 计数兜底，超限抛 `MaxTurnsReached`
5. 历史维护 —— append-only `List<Message>`，请求由历史派生（"model-visible means logged"）

骨架 = hermes 主判（发不发工具）+ pi `FinishTurn` 可编程退出钩子 + dsh 派生历史。
只引 LLM provider SDK，不引 agent 框架。

> **v0 边界声明**：真实 `OpenAILlmProvider` 已接通**聊天**闭环（读 `OPENAI_API_KEY`），但**工具定义未注入请求**（`tools` 字段），
> 故真实 provider 目前只做纯聊天，工具调用闭环仅在 `MockLlm` 驱动下被测试覆盖（见 `AgentLoopTest`）。工具注入 schema 留二期。

## 运行
```bash
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"
export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn test
```
集成测试需 `OPENAI_API_KEY`；无 key 自动跳过。

## 核心文件
- `AgentLoop.java` —— 手写决策循环（AC-3..AC-10 全落地）
- `FinishTurn.java` —— pi 可编程退出钩子（v0 默认"无工具即 end"）
- `SalvageParser.java` —— LLM 参数 JSON 校验/容错
- `llm/OpenAILlmProvider.java` —— 真实 OpenAI 兼容 provider（env key）
- `llm/MockLlm.java` —— 脚本化测试替身（离线、无 key）

## 验收对照
| AC | 验收标准 | 落地 |
|----|---------|------|
| AC-3 | 无工具 → 返回文本、一次调用 | `noToolCallReturnsTextWithSingleCall` |
| AC-4 | 缺工具/异常 → error 回填 → 重试成功 | `unknownToolFeedsBackError...` / `throwingToolFeedsBackError...` |
| AC-5 | 非法/截断参数 → 不崩、回填提示、循环继续 | `illegalJsonParamsFeedsBackErrorWithoutCrash` |
| AC-6 | maxTurns 防死循环 → `MaxTurnsReached` | `nonConvergingToolCallsThrowMaxTurnsReached` |
| AC-7 | 历史 append-only、请求派生 | `historyIsAppendOnlyAndDerived` |
| AC-8 | 真实 OpenAI provider，无 key 跳过 | `OpenAILlmProviderTest` |
| AC-10 | finishTurn 可插拔退出点 | `finishTurnContinueRunsExtraRoundThenEnd` |
