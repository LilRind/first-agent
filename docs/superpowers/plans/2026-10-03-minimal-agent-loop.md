# Minimal Agent Loop (first-agent) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 手写一个最小模型驱动 Agent Loop（决策循环），跑通 JUnit，满足已冻结 spec `add-agent-loop` 的 AC-1..AC-10。

**Architecture:** 七家生产 agent 融合骨架 —— `hermes` 主判（发不发工具）+ `maxIterations` 计数兜底 + `pi` 的 `FinishTurn` 可编程退出钩子（v0 默认"无工具即 end"）+ `dsh` 的 append-only 派生历史。`LlmProvider` 是唯一 seam，只引 LLM 提供商，不引 agent 框架。`StepContext`（codex 留口：本轮冻结工具清单）v0 刻意不实现 —— v0 工具固定、无中途注入，留二期。

**Tech Stack:** Java 17、Maven 3.9.16、Jackson-databind 2.17.2、JUnit 5.10.2、JDK 内置 `java.net.http.HttpClient`（OpenAI provider，零新增依赖）。

**运行前置（本机已就绪，重跑前仍生效）：**
```bash
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"
export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
```
`JAVA_HOME` / `PATH` 不跨 shell 持久化，每个含 `mvn` 的命令都要先 export 上面两行。

**已存在（本计划不重复创建，实现时直接复用签名）：**
`pom.xml`、`Message.java`、`ToolCall.java`、`AssistantReply.java`、`LlmProvider.java`、`AgentTool.java`、`ToolExecutionException.java`、`ToolRegistry.java`

---

### Task 1: 支撑类型 —— SalvageParser / MaxTurnsReached / FinishTurn

**Files:**
- Create: `src/main/java/dev/firstagent/SalvageParser.java`
- Create: `src/main/java/dev/firstagent/MaxTurnsReached.java`
- Create: `src/main/java/dev/firstagent/FinishTurn.java`
- Test: `src/test/java/dev/firstagent/SalvageParserTest.java`

- [ ] **Step 1: 写失败测试**（先测 SalvageParser：合法 JSON 通过、非法/截断抛 ToolExecutionException）

`src/test/java/dev/firstagent/SalvageParserTest.java`
```java
package dev.firstagent;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SalvageParserTest {

    @Test void validJsonParses() {
        JsonNode node = SalvageParser.parse("{\"query\":\"hello\"}");
        assertEquals("hello", node.path("query").asText());
    }

    @Test void illegalJsonThrowsToolExecutionException() {
        ToolExecutionException e = assertThrows(ToolExecutionException.class,
                () -> SalvageParser.parse("not-json"));
        assertEquals("参数不完整，请以完整合法 JSON 重新给参数", e.getMessage());
    }

    @Test void blankJsonThrows() {
        assertThrows(ToolExecutionException.class, () -> SalvageParser.parse("   "));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=SalvageParserTest test`
Expected: FAIL —— `cannot find symbol: class SalvageParser`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/SalvageParser.java`
```java
package dev.firstagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** D2 简化：校验 LLM 返回的参数是合法 JSON。非法/截断 → 抛 ToolExecutionException（回填 tool_use_error）。 */
public final class SalvageParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SalvageParser() {}

    public static JsonNode parse(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            throw new ToolExecutionException("参数不完整，请以完整合法 JSON 重新给参数");
        }
        try {
            return MAPPER.readTree(argumentsJson);
        } catch (Exception e) {
            throw new ToolExecutionException("参数不完整，请以完整合法 JSON 重新给参数", e);
        }
    }
}
```

`src/main/java/dev/firstagent/MaxTurnsReached.java`
```java
package dev.firstagent;

/** 连续多轮未收敛、达到 maxIterations 上限时抛出，防无限循环（hermes 计数兜底）。 */
public class MaxTurnsReached extends RuntimeException {
    public MaxTurnsReached(int maxIterations) {
        super("达到最大轮数 " + maxIterations + " 仍未收敛，已中止循环");
    }
}
```

`src/main/java/dev/firstagent/FinishTurn.java`
```java
package dev.firstagent;

import java.util.List;

/**
 * pi finishTurn 的可编程退出钩子（v0 留口，不实现任务完成检测）。
 * 无工具调用时由 AgentLoop 调用；true = 本轮结束并返回文本，false = 继续一轮。
 */
@FunctionalInterface
public interface FinishTurn {
    boolean isDone(List<Message> history, AssistantReply lastReply);

    /** v0 默认：无工具调用即结束（不额外检测任务是否完成）。 */
    static FinishTurn endOnNoToolCall() { return (h, r) -> true; }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=SalvageParserTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/firstagent/SalvageParser.java src/main/java/dev/firstagent/MaxTurnsReached.java src/main/java/dev/firstagent/FinishTurn.java src/test/java/dev/firstagent/SalvageParserTest.java
git commit -m "feat(loop): add SalvageParser, MaxTurnsReached, FinishTurn support types"
```

---

### Task 2: 测试替身与 demo 工具 —— MockLlm / EchoTool / FailTool

**Files:**
- Create: `src/main/java/dev/firstagent/llm/MockLlm.java`
- Create: `src/main/java/dev/firstagent/tools/EchoTool.java`
- Create: `src/main/java/dev/firstagent/tools/FailTool.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/llm/MockLlmTest.java`
```java
package dev.firstagent.llm;

import dev.firstagent.AssistantReply;
import dev.firstagent.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockLlmTest {

    @Test void returnsScriptedRepliesInOrder() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("第一", List.of(), "end_turn"),
                new AssistantReply("第二", List.of(), "end_turn"));
        assertEquals("第一", llm.chat(List.of(Message.user("x"))).text());
        assertEquals("第二", llm.chat(List.of(Message.user("x"))).text());
        assertEquals(2, llm.calls());
    }

    @Test void exhaustedScriptThrows() {
        MockLlm llm = MockLlm.scripted(new AssistantReply("只此一次", List.of(), "end_turn"));
        llm.chat(List.of(Message.user("x")));
        assertThrows(IllegalStateException.class, () -> llm.chat(List.of(Message.user("x"))));
    }
}
```

`src/test/java/dev/firstagent/tools/DemoToolsTest.java`
```java
package dev.firstagent.tools;

import dev.firstagent.ToolExecutionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DemoToolsTest {

    @Test void echoReturnsInput() {
        assertEquals("echo: {\"x\":1}", new EchoTool().execute("{\"x\":1}"));
    }

    @Test void failAlwaysThrows() {
        assertThrows(ToolExecutionException.class, () -> new FailTool().execute("{}"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=MockLlmTest,DemoToolsTest test`
Expected: FAIL —— `cannot find symbol: class MockLlm` / `EchoTool` / `FailTool`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/llm/MockLlm.java`
```java
package dev.firstagent.llm;

import dev.firstagent.AssistantReply;
import dev.firstagent.LlmProvider;
import dev.firstagent.Message;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** 脚本化的假 provider：按 Queue 依次弹出预设回复，测试离线运行、不依赖网络/密钥。 */
public class MockLlm implements LlmProvider {
    private final Deque<AssistantReply> script = new ArrayDeque<>();
    private int calls = 0;
    private List<Message> lastHistory;

    public static MockLlm scripted(AssistantReply... replies) {
        MockLlm m = new MockLlm();
        for (AssistantReply r : replies) m.script.addLast(r);
        return m;
    }

    @Override
    public AssistantReply chat(List<Message> history) {
        calls++;
        lastHistory = history;                       // 记录这次看到的历史，供 AC-7 断言"请求由历史派生"
        AssistantReply r = script.pollFirst();
        if (r == null) throw new IllegalStateException("MockLlm 脚本已耗尽：第 " + calls + " 次调用无预设回复");
        return r;
    }

    public int calls() { return calls; }
    public List<Message> lastHistory() { return lastHistory; }
}
```

`src/main/java/dev/firstagent/tools/EchoTool.java`
```java
package dev.firstagent.tools;

import dev.firstagent.AgentTool;

/** Demo 工具：把收到的参数原样返回，演示工具调用成功。 */
public class EchoTool implements AgentTool {
    @Override public String name() { return "echo"; }
    @Override public String description() { return "回显给定的 JSON 参数，用于演示工具调用成功。"; }
    @Override public String execute(String argumentsJson) { return "echo: " + argumentsJson; }
}
```

`src/main/java/dev/firstagent/tools/FailTool.java`
```java
package dev.firstagent.tools;

import dev.firstagent.AgentTool;
import dev.firstagent.ToolExecutionException;

/** Demo 工具：每次执行必抛异常，演示工具失败闭环。 */
public class FailTool implements AgentTool {
    @Override public String name() { return "fail"; }
    @Override public String description() { return "总是失败，用于演示工具异常回填。"; }
    @Override public String execute(String argumentsJson) { throw new ToolExecutionException("always fails"); }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=MockLlmTest,DemoToolsTest test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/firstagent/llm/MockLlm.java src/main/java/dev/firstagent/tools/EchoTool.java src/main/java/dev/firstagent/tools/FailTool.java src/test/java/dev/firstagent/llm/MockLlmTest.java src/test/java/dev/firstagent/tools/DemoToolsTest.java
git commit -m "feat(loop): add MockLlm scripted provider and demo tools"
```

---

### Task 3: AgentLoop 核心循环（AC-3/4/5/6/7/10）

**Files:**
- Create: `src/main/java/dev/firstagent/AgentLoop.java`
- Test: `src/test/java/dev/firstagent/AgentLoopTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/AgentLoopTest.java`
```java
package dev.firstagent;

import dev.firstagent.llm.MockLlm;
import dev.firstagent.tools.EchoTool;
import dev.firstagent.tools.FailTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {

    private static final String SYSTEM = "你是一个演示助手。";

    // AC-3 无工具调用 ⇒ 直接返回文本，只一次模型调用
    @Test void noToolCallReturnsTextWithSingleCall() {
        MockLlm llm = MockLlm.scripted(new AssistantReply("你好", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry());
        assertEquals("你好", loop.execute("hi"));
        assertEquals(1, llm.calls());
    }

    // AC-4 模型调用不存在工具 ⇒ 回填 tool_use_error 而非崩溃；模型重试后成功
    @Test void unknownToolFeedsBackErrorThenRetrySucceeds() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("call_1", "ghost", "{}")), "tool_use"),
                new AssistantReply("重试成功", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry().register(new EchoTool()));

        assertEquals("重试成功", loop.execute("调一个不存在的工具"));
        assertEquals(2, llm.calls());
    }

    // AC-4 工具 execute 抛异常 ⇒ 回填 error；模型重试成功后返回正确文本
    @Test void throwingToolFeedsBackErrorThenRetrySucceeds() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("call_1", "fail", "{}")), "tool_use"),
                new AssistantReply("最终答案", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry().register(new FailTool()));

        assertEquals("最终答案", loop.execute("触发失败"));
        assertEquals(2, llm.calls());
    }

    // AC-5 非法/截断 JSON 参数 ⇒ 回填 tool_use_error 不崩，循环继续
    @Test void illegalJsonParamsFeedsBackErrorWithoutCrash() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("call_1", "echo", "not-json")), "tool_use"),
                new AssistantReply("纠正后成功", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry().register(new EchoTool()));

        assertEquals("纠正后成功", loop.execute("给坏参数"));
        assertEquals(2, llm.calls());
    }

    // AC-6 连续只发工具不收敛 ⇒ 抛 MaxTurnsReached 不死循环（cap 用 3，机制等同默认 10）
    @Test void nonConvergingToolCallsThrowMaxTurnsReached() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("c1", "echo", "{}")), "tool_use"),
                new AssistantReply("", List.of(new ToolCall("c2", "echo", "{}")), "tool_use"),
                new AssistantReply("", List.of(new ToolCall("c3", "echo", "{}")), "tool_use"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm,
                new ToolRegistry().register(new EchoTool()), 3, FinishTurn.endOnNoToolCall());

        assertThrows(MaxTurnsReached.class, () -> loop.execute("一直发工具"));
    }

    // AC-10 finishTurn：返回 continue 让 loop 继续一轮；返回 end 则本轮结束
    @Test void finishTurnContinueRunsExtraRoundThenEnd() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("第一轮", List.of(), "end_turn"),
                new AssistantReply("第二轮", List.of(), "end_turn"));
        FinishTurn continueThenEnd = new FinishTurn() {
            private boolean first = true;
            @Override public boolean isDone(List<Message> h, AssistantReply r) {
                if (first) { first = false; return false; }  // continue 一轮
                return true;                                  // 再一轮 end
            }
        };
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry(), 10, continueThenEnd);

        assertEquals("第二轮", loop.execute("多走一轮"));
        assertEquals(2, llm.calls());
    }

    // AC-7 历史 append-only & 派生：重试请求看到的历史顺序为 system→user→assistant(tool_call)→tool_result
    @Test void historyIsAppendOnlyAndDerived() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("call_1", "echo", "{\"x\":1}")), "tool_use"),
                new AssistantReply("收尾", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry().register(new EchoTool()));
        loop.execute("验证历史");

        List<Message> h = llm.lastHistory();                  // 第二次(重试)调用看到的历史
        assertEquals(4, h.size());
        assertEquals(Message.Role.SYSTEM, h.get(0).role());
        assertEquals(Message.Role.USER, h.get(1).role());
        assertEquals(Message.Role.ASSISTANT, h.get(2).role());
        assertFalse(h.get(2).toolCalls().isEmpty());
        assertEquals(Message.Role.TOOL, h.get(3).role());
        assertEquals("call_1", h.get(3).toolCallId());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=AgentLoopTest test`
Expected: FAIL —— `cannot find symbol: class AgentLoop`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/AgentLoop.java`
```java
package dev.firstagent;

import java.util.ArrayList;
import java.util.List;

/**
 * 手写最小 Agent Loop —— 模型驱动 ReAct 决策循环。
 * 骨架 = hermes 主判（发不发工具 + maxIterations 计数兜底）+ pi finishTurn 可编程退出钩子
 *        + dsh append-only 派生历史（请求由日志派生、先落日志）。
 * StepContext（codex 留口：本轮冻结工具清单）v0 刻意不实现 —— 工具固定、无中途注入。
 */
public class AgentLoop {
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    private final String systemPrompt;
    private final LlmProvider llm;
    private final ToolRegistry tools;
    private final int maxIterations;
    private final FinishTurn finishTurn;

    public AgentLoop(String systemPrompt, LlmProvider llm, ToolRegistry tools) {
        this(systemPrompt, llm, tools, DEFAULT_MAX_ITERATIONS, FinishTurn.endOnNoToolCall());
    }

    public AgentLoop(String systemPrompt, LlmProvider llm, ToolRegistry tools,
                     int maxIterations, FinishTurn finishTurn) {
        this.systemPrompt = systemPrompt;
        this.llm = llm;
        this.tools = tools;
        this.maxIterations = maxIterations;
        this.finishTurn = finishTurn;
    }

    /** 跑完整循环，返回最终回答。 */
    public String execute(String userInput) {
        List<Message> history = new ArrayList<>();
        history.add(Message.system(systemPrompt));
        history.add(Message.user(userInput));

        for (int turn = 1; turn <= maxIterations; turn++) {
            AssistantReply reply = llm.chat(history);                     // dsh：请求由日志派生（只读历史）
            history.add(Message.assistant(reply.text(), reply.toolCalls())); // dsh：先落日志

            if (reply.hasToolCalls()) {
                for (ToolCall call : reply.toolCalls()) {                 // v0 顺序执行，未做并行
                    history.add(executeTool(call));                       // 错也回填，模型自纠正
                }
            } else {
                if (finishTurn.isDone(history, reply)) {                  // pi 可编程退出点
                    return reply.text();
                }
                // finishTurn 返回 false(continue) → 继续一轮，但不发工具
            }
        }
        throw new MaxTurnsReached(maxIterations);                         // hermes 计数兜底，防死循环
    }

    /** 执行单个工具；无论成败都回填 tool_result。 */
    private Message executeTool(ToolCall call) {
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return Message.toolResult(call.id(), "tool_use_error: unknown tool: " + call.name(), true);
        }
        try {
            SalvageParser.parse(call.argumentsJson());                    // D2 校验参数，非法→抛
            String resultText = tool.execute(call.argumentsJson());
            return Message.toolResult(call.id(), resultText, false);
        } catch (ToolExecutionException e) {
            return Message.toolResult(call.id(), "tool_use_error: " + e.getMessage(), true);
        } catch (Exception e) {                                           // 工具自身任意异常
            return Message.toolResult(call.id(), "tool_use_error: " + e.getMessage(), true);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=AgentLoopTest test`
Expected: PASS（6 个用例全绿，覆盖 AC-3/4/5/6/7/10）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/firstagent/AgentLoop.java src/test/java/dev/firstagent/AgentLoopTest.java
git commit -m "feat(loop): hand-write AgentLoop core (hermes+pi+fetch fusion)"
```

---

### Task 4: 真实 OpenAI 兼容 provider（AC-8，无 key 自动跳过）

**Files:**
- Create: `src/main/java/dev/firstagent/llm/OpenAILlmProvider.java`
- Test: `src/test/java/dev/firstagent/llm/OpenAILlmProviderTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/llm/OpenAILlmProviderTest.java`
```java
package dev.firstagent.llm;

import dev.firstagent.AssistantReply;
import dev.firstagent.Message;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

class OpenAILlmProviderTest {

    @Test void realCallRequiresKey() {
        Assumptions.assumeTrue(System.getenv("OPENAI_API_KEY") != null,
                "未设置 OPENAI_API_KEY，跳过集成测试（不影响 mvn test）");
        OpenAILlmProvider p = new OpenAILlmProvider();
        AssistantReply r = p.chat(List.of(
                Message.system("你是一个演示助手。"),
                Message.user("回复一个字：好")));
        System.out.println("OpenAI reply: " + r.text());  // 不崩、有内容即通过
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=OpenAILlmProviderTest test`
Expected: FAIL（无 key 跳过）或 FAIL —— `cannot find symbol: class OpenAILlmProvider`（实现不存在）

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/llm/OpenAILlmProvider.java`
```java
package dev.firstagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.firstagent.AssistantReply;
import dev.firstagent.LlmProvider;
import dev.firstagent.Message;
import dev.firstagent.ToolCall;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** 真实 OpenAI 兼容 provider：读 env OPENAI_API_KEY，调 /chat/completions。无 key 时集成测试自动跳过。 */
public class OpenAILlmProvider implements LlmProvider {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;
    private final String endpoint;
    private final String model;

    public OpenAILlmProvider() {
        this(System.getenv("OPENAI_API_KEY"), DEFAULT_ENDPOINT, "gpt-4o-mini");
    }

    public OpenAILlmProvider(String apiKey, String endpoint, String model) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.model = model;
    }

    @Override
    public AssistantReply chat(List<Message> history) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未设置 OPENAI_API_KEY");
        }
        try {
            ObjectNode body = M.createObjectNode();
            body.put("model", model);
            body.set("messages", toOpenAIMessages(history));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode msg = M.readTree(resp.body()).path("choices").get(0).path("message");

            String text = msg.path("content").isValueNode() ? msg.path("content").asText() : null;
            List<ToolCall> calls = new ArrayList<>();
            JsonNode tcs = msg.path("tool_calls");
            if (tcs.isArray()) {
                for (JsonNode tc : tcs) {
                    calls.add(new ToolCall(
                            tc.path("id").asText(),
                            tc.path("function").path("name").asText(),
                            tc.path("function").path("arguments").asText()));
                }
            }
            return new AssistantReply(text, calls, msg.path("stop_reason").asText(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI 调用被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI 调用失败: " + e.getMessage(), e);
        }
    }

    /** 把 append-only 历史映射成 OpenAI 消息格式。 */
    private ArrayNode toOpenAIMessages(List<Message> history) {
        ArrayNode arr = M.createArrayNode();
        for (Message m : history) {
            ObjectNode o = arr.addObject();
            switch (m.role()) {
                case SYSTEM, USER -> {
                    o.put("role", m.role() == Message.Role.SYSTEM ? "system" : "user");
                    o.put("content", m.text());
                }
                case ASSISTANT -> {
                    if (m.toolCalls().isEmpty()) {
                        o.put("role", "assistant");
                        o.put("content", m.text());
                    } else {
                        o.put("role", "assistant");
                        o.putNull("content");                          // OpenAI 要求含 tool_calls 时 content 可空
                        ArrayNode tcs = o.putArray("tool_calls");
                        for (ToolCall tc : m.toolCalls()) {
                            ObjectNode t = tcs.addObject();
                            t.put("id", tc.id());
                            t.put("type", "function");
                            ObjectNode fn = t.putObject("function");
                            fn.put("name", tc.name());
                            fn.put("arguments", tc.argumentsJson());
                        }
                    }
                }
                case TOOL -> {
                    o.put("role", "tool");
                    o.put("tool_call_id", m.toolCallId());
                    o.put("content", m.isError() ? "[tool_use_error] " + m.text() : m.text());
                }
            }
        }
        return arr;
    }
}
```

- [ ] **Step 4: 跑测试确认通过（跳过或通过均符合 AC-8）**

Run: `mvn -q -Dtest=OpenAILlmProviderTest test`
Expected: PASS（无 key 时 Assumption 跳过；有 key 时真实调用成功）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/firstagent/llm/OpenAILlmProvider.java src/test/java/dev/firstagent/llm/OpenAILlmProviderTest.java
git commit -m "feat(provider): add real OpenAI-compatible LlmProvider (env key)"
```

---

### Task 5: 全量校验 + README（AC-9）

**Files:**
- Create: `README.md`

- [ ] **Step 1: 写 README**

`README.md`
```markdown
# first-agent (Agent Loop)

手写最小模型驱动 Agent Loop demo，亲手处理"框架帮你藏起来的那 5 个问题"：
1. 提示词/结构化输出 —— 见 spec（prompt 设计不在 v0 手写范围，工具契约自描述）
2. 解析 LLM 输出的脆弱性 —— `SalvageParser`（D2 简化：非法/截断 → 回填"参数不完整请重发"）
3. 工具调用失败 —— `executeTool` 错也回填 `tool_use_error`，模型自纠正
4. 无限循环 —— `maxIterations` 计数兜底，超限抛 `MaxTurnsReached`
5. 历史维护 —— append-only `List<Message>`，请求由历史派生（"model-visible means logged"）

骨架 = hermes 主判 + pi `FinishTurn` 可编程退出钩子 + dsh 派生历史。只引 LLM provider SDK，不引 agent 框架。

## 运行
```bash
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"
export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn test
```
集成测试需 `OPENAI_API_KEY`；无 key 自动跳过。
```

- [ ] **Step 2: 全量跑测试**

Run: `mvn test`
Expected: 全部 PASS（`SalvageParserTest`、`MockLlmTest`、`DemoToolsTest`、`AgentLoopTest`；`OpenAILlmProviderTest` 无 key 时跳过）

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: add README for minimal agent loop"
```

---

## Self-Review

- **AC-1** 契约：已存在（Task 0，前序已建）✓
- **AC-2** Provider seam + MockLlm：Task 2 `MockLlm`，`LlmProvider.chat(List<Message>)` 接口已存在 ✓
- **AC-3** 无工具返回文本、一次调用：Task 3 `noToolCallReturnsTextWithSingleCall` ✓
- **AC-4** 工具失败闭环：Task 3 `unknownToolFeedsBackError...` + `throwingToolFeedsBackError...`（缺工具/异常→error 回填→重试成功）✓
- **AC-5** JSON salvage：Task 1 `SalvageParserTest` + Task 3 `illegalJsonParamsFeedsBackErrorWithoutCrash` ✓
- **AC-6** maxTurns：Task 3 `nonConvergingToolCallsThrowMaxTurnsReached`（cap=3，默认 10）✓
- **AC-7** 历史 append-only/派生：Task 3 `historyIsAppendOnlyAndDerived`（MockLlm.lastHistory 断言顺序）✓
- **AC-8** 真实 OpenAI provider：Task 4（无 key Assumption 跳过）✓
- **AC-9** `mvn test` 全绿：Task 5 ✓
- **AC-10** finishTurn 可插拔退出点：Task 3 `finishTurnContinueRunsExtraRoundThenEnd`（continue 多一轮、end 结束）+ v0 默认 `endOnNoToolCall` ✓

**类型一致性抽查：** `Message.system/user/assistant/toolResult`、`AssistantReply(text, toolCalls, stopReason)`、`ToolCall(id,name,argumentsJson)`、`AgentTool.execute(String)→String`、`FinishTurn.isDone(List<Message>, AssistantReply)→boolean` —— 与已存在契约文件逐一对齐，无漂移。
