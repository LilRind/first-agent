# Design — Minimal Agent Loop

> 结构对齐 claude-code Tool 契约 + pi agent-loop + dsh "model-visible means logged" 共识。package `dev.firstagent`。

## 循环（AgentLoop.execute）——按七家精读融合
> 七家 agent loop"如何停"收敛成三类；第一版取 **hermes 主判（发不发工具）+ maxIterations 兜底**，
> 扩展点取 **pi 的 finishTurn（可编程退出钩子，v0 为空、无工具时返回 end）**。骨架干净不过度设计。

```
messages = [system, user(input)]
turn = 0
while (首轮 || 本轮有工具调用 || finishTurn()==continue) {
    turn++
    snapshot = StepContext(tools)              // codex 留口：冻结本轮工具清单（v0 固定工具，仅留接口）
    reply = llm.chat(session.deriveMessages()) // dsh：模型可见者即为所记，请求从日志派生（纯函数只读）
    session.append(assistant(reply.text, reply.toolCalls))   // dsh：先落日志
    if (reply.hasToolCalls()) {
        for call in reply.toolCalls:
            result = executeTool(call)          // 错也回填
            session.append(toolResult(call.id, result.text, result.isError))
    } else {
        if (finishTurn(session.snapshot(), reply).end)      // pi 可编程退出点（v0 默认 end）
            return reply.text                  // 结束：最终回答
    }
    if (turn >= maxIterations) break;          // hermes 计数兜底，防死循环
}
throw new MaxTurnsReached(...)
```
> pending 中间注入（nanobot 原子快照 / pi steering）v0 **砍掉**（单线程无中途用户消息）；留二期。

## executeTool（单工具，失败闭环）
```
tool = registry.get(call.name)
if tool == null                       → error "unknown tool: <name>"
try:
    args   = SalvageParser.parse(call.argumentsJson)   // D2 简化：失败=解析失败
    result = tool.execute(args)                        // 成功后截断阈值
    return ok(result)
catch ToolExecutionException / any:
    return error("tool_use_error: <msg>")              // D3 合成 error 回填，模型自纠正
```

## finishTurn（pi 留口，v0 空钩子）
函数式钩子 `FinishTurn`：根据 `sessionSnapshot + lastReply` 返回 `{end|continue}`。v0 默认在"无工具调用"时返回
end（不额外判断）；为二期（任务完成检测 / 预算拦截 / 手动强制停）保留接缝。**不在 v0 实现检测逻辑。**

## Salvage (D2 简化)
参数不是合法 JSON / 被 token 截断 → 不崩，回填 `tool_use_error: 参数不完整，请以完整合法 JSON 重新给参数`。

## Message 模型
`Message{ role: SYSTEM|USER|ASSISTANT|TOOL; text; toolCalls[]; toolCallId; isError }`
一切归一成消息；历史 = `List<Message>` append-only。

## Tool 契约
`AgentTool{ name(); description(); execute(argsJson)|throws ToolExecutionException; isConcurrencySafe() }`

## Provider seam
`LlmProvider.chat(List<Message>) -> AssistantReply(text, toolCalls, stopReason)`
- `MockLlm`：脚本化返回（测试，离线无 key）
- `OpenAILlmProvider`：真实调用 OpenAI 兼容 `chat/completions`，key 从 env `OPENAI_API_KEY` 读（E2）

## 文件
```
pom.xml
src/main/java/dev/firstagent/
  Message / ToolCall / AssistantReply / LlmProvider / AgentTool / ToolExecutionException / ToolRegistry
  AgentLoop               // 核心，手写
  SalvageParser           // D2 简化
  MaxTurnsReached
  llm/MockLlm, llm/OpenAILlmProvider
  tools/EchoTool, tools/FailTool        // demo 工具
src/test/java/dev/firstagent/AgentLoopTest.java
```