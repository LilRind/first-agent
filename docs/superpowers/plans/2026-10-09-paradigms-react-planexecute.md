# Plan — ReAct 完整闭环 + Plan-and-Execute（TDD 实现）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> Goal: 按已冻结 spec `paradigms-react-planexecute-design`，实现 ReAct 完整闭环 + Plan-and-Execute，跑通 JUnit + CLI demo。

**运行前置（本机已就绪）：**
```bash
# 本机路径示例（按实际安装位置替换）
export JAVA_HOME="$(cygpath -w /path/to/jdk-17)"
export PATH="/path/to/apache-maven-3.9.16/bin:$PATH"
```
`JAVA_HOME` / `PATH` 不跨 shell 持久化，每个含 `mvn` 的命令都要先 export 上面两行。
`mvn` 命令需在项目根目录下执行。

**已存在（直接复用，不重复创建）：** `Message`/`ToolCall`/`AssistantReply`/`LlmProvider`/`AgentTool`/`ToolExecutionException`/`ToolRegistry`/`SalvageParser`/`AgentLoop`/`AgentEvent`/`LoopStrategy`/`TurnDecision`/`MockLlm`/`EchoTool`/`FailTool`/`AppConfig`/`OpenAILlmProvider`。

---

### Task 1: CalculatorTool（演示计算工具）+ 测试

**Files:**
- Create: `src/main/java/dev/firstagent/tools/CalculatorTool.java`
- Test: `src/test/java/dev/firstagent/tools/CalculatorToolTest.java`

- [ ] **Step 1: 写失败测试**（合法表达式返回结果；非法表达式抛 ToolExecutionException）

`src/test/java/dev/firstagent/tools/CalculatorToolTest.java`
```java
package dev.firstagent.tools;

import dev.firstagent.ToolExecutionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CalculatorToolTest {

    @Test void nameAndDescriptionEnUs() {
        assertEquals("calculate", new CalculatorTool().name());
        assertFalse(new CalculatorTool().description().isBlank());
    }

    @Test void simpleExpression() {
        String r = new CalculatorTool().execute("{\"expression\":\"2+3*4\"}");
        assertEquals("14", r.trim());
    }

    @Test void parenthesesAndDiv() {
        String r = new CalculatorTool().execute("{\"expression\":\"(123+456)*789/12\"}");
        // (579)*789/12 = 456981/12 = 38081.75
        assertTrue(r.contains("38081"), "实际: " + r);
    }

    @Test void invalidJsonThrows() {
        assertThrows(ToolExecutionException.class, () -> new CalculatorTool().execute("not-json"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**
Run: `mvn -q -Dtest=CalculatorToolTest test`
Expected: FAIL —— `cannot find symbol: class CalculatorTool`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/tools/CalculatorTool.java`
```java
package dev.firstagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.firstagent.AgentTool;
import dev.firstagent.ToolExecutionException;

import java.math.BigDecimal;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

/** 离线确定性计算工具：接收 {"expression":"(123+456)*789/12"}，返回计算结果。 */
public class CalculatorTool implements AgentTool {
    private static final ObjectMapper M = new ObjectMapper();

    @Override public String name() { return "calculate"; }
    @Override public String description() {
        return "执行算术表达式并返回结果。参数 JSON: {\"expression\":\"<算术表达式，支持+ - * / 和括号>\"}。"
                + " 适用于需要精确计算的场景（避免 LLM 心算出错）。";
    }

    @Override public String execute(String argumentsJson) throws ToolExecutionException {
        try {
            JsonNode node = M.readTree(argumentsJson);
            String expr = node.path("expression").asText(null);
            if (expr == null || expr.isBlank()) throw new ToolExecutionException("缺少 expression 字段");
            Object v = ENGINE.eval(expr);
            if (v instanceof Double d) {
                // 若结果接近整数则整型显示，否则保留原样
                if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                    return Long.toString(Math.round(d));
                }
                return new BigDecimal(d.toString()).stripTrailingZeros().toPlainString();
            }
            return String.valueOf(v);
        } catch (ToolExecutionException e) {
            throw e;
        } catch (ScriptException | RuntimeException e) {
            throw new ToolExecutionException("表达式无法计算: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ToolExecutionException("参数不完整，请提供合法 JSON: " + e.getMessage(), e);
        }
    }

    private static final ScriptEngine ENGINE = createEngine();
    private static ScriptEngine createEngine() {
        ScriptEngineManager mgr = new ScriptEngineManager();
        ScriptEngine e = mgr.getEngineByName("JavaScript");
        if (e == null) throw new IllegalStateException("JDK 无 JavaScript 引擎（Nashorn 已移除于 JDK15+）");
        return e;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**
Run: `mvn -q -Dtest=CalculatorToolTest test`
Expected: PASS
- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/firstagent/tools/CalculatorTool.java src/test/java/dev/firstagent/tools/CalculatorToolTest.java
git commit -m "feat(tools): add CalculatorTool for deterministic math in ReAct demo"
```

**注意（JDK 17 无 Nashorn）：** 若 `getEngineByName("JavaScript")` 返回 null，改用**自实现四则解析器**（`double` 栈式 shunting-yard），见 Append-A；测试幂等不变。

---

### Task 2: AgentEvent 扩展（Plan 事件）+ TelemetryBridge 分支

**Files:**
- Edit: `src/main/java/dev/firstagent/AgentEvent.java`
- Edit: `src/main/java/dev/firstagent/telemetry/AgentEventTelemetryBridge.java`
- Test: `src/test/java/dev/firstagent/telemetry/AgentEventTelemetryBridgeTest.java`

- [ ] **Step 1: 写失败测试**（PlanStarted/PlanStepStarted/PlanStepEnded → 对应 span）

`src/test/java/dev/firstagent/telemetry/AgentEventTelemetryBridgeTest.java`
```java
package dev.firstagent.telemetry;

import dev.firstagent.AgentEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventTelemetryBridgeTest {

    @Test void planEventsBecomeSpans() {
        TelemetryRecorder rec = new TelemetryRecorder();
        AgentEventTelemetryBridge b = new AgentEventTelemetryBridge(rec);
        b.accept(new AgentEvent.PlanStarted("q", List.of("步骤1", "步骤2")));
        b.accept(new AgentEvent.PlanStepStarted(0, "步骤1"));
        b.accept(new AgentEvent.PlanStepEnded(0, "步骤1", "结果1"));
        b.accept(new AgentEvent.PlanStepStarted(1, "步骤2"));
        b.accept(new AgentEvent.PlanStepEnded(1, "步骤2", "结果2"));

        assertEquals(List.of("plan.start", "plan.step.start", "plan.step.end", "plan.step.start", "plan.step.end"),
                rec.spanNames());
    }

    @Test void existingEventsStillMap() {
        TelemetryRecorder rec = new TelemetryRecorder();
        AgentEventTelemetryBridge b = new AgentEventTelemetryBridge(rec);
        b.accept(new AgentEvent.TurnStarted());
        assertEquals(List.of("turn.start"), rec.spanNames());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**
Run: `mvn -q -Dtest=AgentEventTelemetryBridgeTest test`
Expected: FAIL（编译不过，缺 PlanStarted 等 record / 缺映射分支）

- [ ] **Step 3: 实现**

`AgentEvent.java` 末尾，`AgentEnded` record 之后追加：
```java
    /** Plan-and-Execute：规划完成，附完整计划。 */
    record PlanStarted(String question, List<String> plan) implements AgentEvent {}

    /** Plan-and-Execute：开始执行第 index 步（0-based）。 */
    record PlanStepStarted(int index, String step) implements AgentEvent {}

    /** Plan-and-Execute：第 index 步执行完成，附该步结果。 */
    record PlanStepEnded(int index, String step, String result) implements AgentEvent {}
```

`AgentEventTelemetryBridge.java` 的 `accept` 尾部追加：
```java
        else if (e instanceof AgentEvent.PlanStarted p) rec.record("plan.start", Map.of("steps", String.valueOf(p.plan().size())));
        else if (e instanceof AgentEvent.PlanStepStarted s) rec.record("plan.step.start", Map.of());
        else if (e instanceof AgentEvent.PlanStepEnded) rec.record("plan.step.end", Map.of());
```
注意现有分支是 `if/else if` 链，`e instanceof` 增加不影响。但**必须保证最后两个 else-if 追加在链尾**，且把原来链式 if 前的判断保持。

⚠️ 现有 `accept` 是 `if (e instanceof TurnStarted) ... else if ...`，在末尾追加 else-if 即可。

- [ ] **Step 4: 跑测试确认通过**
Run: `mvn -q -Dtest=AgentEventTelemetryBridgeTest test`
Expected: PASS（新增 2 例 + 不回归）
- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/firstagent/AgentEvent.java src/main/java/dev/firstagent/telemetry/AgentEventTelemetryBridge.java src/test/java/dev/firstagent/telemetry/AgentEventTelemetryBridgeTest.java
git commit -m "feat(telemetry): add PlanStarted/PlanStep* events + bridge spans"
```

---

### Task 3: ReActTrace（事件流轨迹重建）+ 测试

**Files:**
- Create: `src/main/java/dev/firstagent/ReActTrace.java`
- Test: `src/test/java/dev/firstagent/ReActTraceTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/ReActTraceTest.java`
```java
package dev.firstagent;

import dev.firstagent.llm.MockLlm;
import dev.firstagent.tools.EchoTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReActTraceTest {

    // 完整闭环：提问 → 工具调用 → 观察 → Finish
    @Test void fullLoopBuildsThoughtActionObservationFinish() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("我需要先查一下。", List.of(new ToolCall("call_1", "echo", "{\"msg\":\"hi\"}")), "tool_use"),
                new AssistantReply("最终答案: 收到了 hi", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop("sys", llm, new ToolRegistry().register(new EchoTool()));
        ReActTrace trace = new ReActTrace();

        String answer = loop.execute("测试一下", trace);

        assertEquals("最终答案: 收到了 hi", answer);
        assertEquals(1, trace.steps().size());
        assertEquals("我需要先查一下。", trace.steps().get(0).thought());
        assertEquals("echo[{\"msg\":\"hi\"}]", trace.steps().get(0).action());
        assertTrue(trace.steps().get(0).observation().contains("echo:"));
        assertEquals("最终答案: 收到了 hi", trace.finish());
    }

    // 无工具直接回答：只有一条 Finish，无 step
    @Test void directAnswerHasNoSteps() {
        MockLlm llm = MockLlm.scripted(new AssistantReply("你好", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop("sys", llm, new ToolRegistry());
        ReActTrace trace = new ReActTrace();

        assertEquals("你好", loop.execute("hi", trace));
        assertTrue(trace.steps().isEmpty());
        assertEquals("你好", trace.finish());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**
Run: `mvn -q -Dtest=ReActTraceTest test`
Expected: FAIL —— `cannot find symbol: class ReActTrace`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/ReActTrace.java`
```java
package dev.firstagent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ReAct 轨迹重建 —— 订阅 AgentLoop 的 AgentEvent 流，把「思考/行动/观察/Finish」按序还原。
 * 引擎零改动：AgentLoop.execute(input, trace) 复用现有 Consumer&lt;AgentEvent&gt; 出口。
 *
 * 映射：assistant(有 toolCalls 或无) → Step.thought；ToolCall → Step.action("name[json]")；
 *       同轮后随的 TOOL 结果 → 回填 Step.observation；execute 返回值 → finish。
 * 诚实边界：模型可静默发工具调（text 为空）→ thought 如实为 null，渲染成"(无思考文本)"，不编造。
 */
public final class ReActTrace implements Consumer<AgentEvent> {

    /** 一步的思考/行动/观察。 */
    public record Step(String thought, String action, String observation) {}

    private final List<Step> steps = new ArrayList<>();
    private Step current;         // 正在累积的一步(等 observation 回填)
    private String finish;

    @Override
    public void accept(AgentEvent e) {
        if (e instanceof AgentEvent.MessageEnded m) {
            if (m.message().role() == Message.Role.ASSISTANT) {
                List<ToolCall> calls = m.message().toolCalls();
                if (calls.isEmpty()) {
                    // 纯回答：若无后续观察，视为 finish 候选(最终由 execute 返回值定)
                } else {
                    for (ToolCall tc : calls) {
                        Step s = new Step(m.message().text(), tc.name() + "[" + tc.argumentsJson() + "]", null);
                        steps.add(s);
                        current = s;
                    }
                }
            } else if (m.message().role() == Message.Role.TOOL) {
                if (current != null) {
                    steps.set(steps.size() - 1,
                            new Step(current.thought(), current.action(), m.message().text()));
                    current = null;
                }
            }
        }
    }

    public List<Step> steps() { return List.copyOf(steps); }

    /** 外壳(调用方)在 execute 返回后调用，供测试/CLI 取得最终答案。 */
    public void finish(String answer) { this.finish = answer; }
    public String finish() { return finish; }
}
```

- [ ] **Step 4: 跑测试确认通过**
Run: `mvn -q -Dtest=ReActTraceTest test`
Expected: PASS
- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/firstagent/ReActTrace.java src/test/java/dev/firstagent/ReActTraceTest.java
git commit -m "feat(react): ReActTrace rebuilds thought/action/observation/finish from event stream"
```

---

### Task 4: OpenAILlmProvider 补 tools（真实闭环硬前提）

**Files:**
- Edit: `src/main/java/dev/firstagent/llm/OpenAILlmProvider.java`
- Create: `src/main/java/dev/firstagent/llm/ToolSpec.java`
- Test: `src/test/java/dev/firstagent/llm/OpenAILlmProviderTest.java`（增补单测：body 含 tools）

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/llm/ToolSpecTest.java`（新文件）
```java
package dev.firstagent.llm;

import dev.firstagent.tools.CalculatorTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolSpecTest {
    @Test void fromAgentToolKeepsNameAndDescription() {
        ToolSpec s = ToolSpec.from(new CalculatorTool());
        assertEquals("calculate", s.name());
        assertFalse(s.description().isBlank());
    }

    @Test void fromRegistryKeepsOrder() {
        // ToolRegistry 没有 public 导出迭代器；用构造传入示例
        List<ToolSpec> specs = List.of(ToolSpec.from(new CalculatorTool()));
        assertEquals(1, specs.size());
    }
}
```

`OpenAILlmProviderTest` 增补（用本地 fake 不做网络；注：无 key 时仍跳过真实调用，这里单测构造与 body 映射）——**改成构造注入、用一个 TestTool，断言 toOpenAIRequestJson 含 tools**。但 Original `toOpenAIMessages` 是 private；需要暴露一个 package-private 的 `buildBody(List<ToolSpec>, List<Message>).toByte`。为最小改动，改为：给 `OpenAILlmProviderTest` 加阻塞性断言——**测试构造含 tools 的实例，启动一个本地 HttpServer 捕获请求体，断言 body 含 `"tools"` 与 `"calculate"`**。无 key 时走 fake provider 的内置 key。

```java
@Test void requestBodyContainsToolsDeclarations() throws Exception {
    com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
    server.createContext("/", ex -> {
        body.set(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        String resp = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, resp.getBytes().length);
        ex.getResponseBody().write(resp.getBytes());
        ex.close();
    });
    server.start();
    try {
        String addr = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        OpenAILlmProvider p = new OpenAILlmProvider("test-key", addr, "test-model",
                List.of(new ToolSpec("calculate", "do math")));
        p.chat(List.of(dev.firstagent.Message.user("1+1")));
        assertTrue(body.get().contains("\"tools\""), "body 应含 tools: " + body.get());
        assertTrue(body.get().contains("\"calculate\""), "body 应含工具名: " + body.get());
    } finally {
        server.stop(0);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**
Run: `mvn -q -Dtest=ToolSpecTest,OpenAILlmProviderTest test`
Expected: FAIL——缺 `ToolSpec`、`OpenAILlmProvider(String,String,String,List<ToolSpec>)` 构造不存在

- [ ] **Step 3: 实现**

`src/main/java/dev/firstagent/llm/ToolSpec.java`
```java
package dev.firstagent.llm;

import dev.firstagent.AgentTool;

/** provider 可见的工具声明（轻量 DTO，解耦 AgentTool 接口）。 */
public record ToolSpec(String name, String description) {
    public static ToolSpec from(AgentTool t) { return new ToolSpec(t.name(), t.description()); }
}
```

`OpenAILlmProvider.java` 改动（**最小侵入，不动既有无参/AppConfig 构造行为**）：
```java
public class OpenAILlmProvider implements LlmProvider {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final ObjectNode LOOSE_PARAMS = M.createObjectNode()   // 宽松 object 参数 schema
            .put("type", "object")
            .set("properties", M.createObjectNode());

    private final HttpClient http = ...;
    private final String apiKey, endpoint, model;
    private final List<ToolSpec> tools;            // 可为空(原行为)

    // 既有构造（无 tools，行为不变）
    public OpenAILlmProvider() { this(new AppConfig(), List.of()); }
    public OpenAILlmProvider(AppConfig cfg) { this(cfg, List.of()); }
    public OpenAILlmProvider(String apiKey, String endpoint, String model) {
        this(apiKey, endpoint, model, List.of());
    }

    // 新增：带工具注册表。既有无参/AppConfig 委托到这里
    public OpenAILlmProvider(AppConfig cfg, List<ToolSpec> tools) {
        this(cfg.get("OPENAI_API_KEY", null),
             cfg.get("OPENAI_BASE_URL", DEFAULT_ENDPOINT),
             cfg.get("OPENAI_MODEL", DEFAULT_MODEL), tools);
    }
    public OpenAILlmProvider(String apiKey, String endpoint, String model, List<ToolSpec> tools) {
        this.apiKey = apiKey; this.endpoint = endpoint; this.model = model;
        this.tools = tools == null ? List.of() : tools;
    }
```
`chat(...)` 里 `body.set("messages", toOpenAIMessages(history));` 之后追加：
```java
            if (!tools.isEmpty()) {
                ArrayNode arr = M.createArrayNode();
                for (ToolSpec t : tools) {
                    ObjectNode fn = arr.addObject().putObject("function");
                    fn.put("name", t.name());
                    fn.put("description", t.description());
                    fn.set("parameters", LOOSE_PARAMS.deepCopy());
                }
                body.set("tools", arr);
            }
```

⚠️ 原 `OpenAILlmProvider(String,String,String)` 现在 delegate 到新 4 参构造；用一个字段 `tools`。现有 `toOpenAIMessages` 不动。

- [ ] **Step 4: 跑测试确认通过**
Run: `mvn -q -Dtest=ToolSpecTest,OpenAILlmProviderTest test`
Expected: PASS
- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/firstagent/llm/ToolSpec.java src/main/java/dev/firstagent/llm/OpenAILlmProvider.java src/test/java/dev/firstagent/llm/ToolSpecTest.java src/test/java/dev/firstagent/llm/OpenAILlmProviderTest.java
git commit -m "feat(provider): OpenAILlmProvider sends tools declarations for real tool-call loop"
```

---

### Task 5: Planner（JSON 计划 + lenient 解析 + 重试）+ 测试

**Files:**
- Create: `src/main/java/dev/firstagent/plan/Planner.java`
- Test: `src/test/java/dev/firstagent/plan/PlannerTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/plan/PlannerTest.java`
```java
package dev.firstagent.plan;

import dev.firstagent.AssistantReply;
import dev.firstagent.llm.MockLlm;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlannerTest {

    private static final String QUESTION = "一个水果店周一卖出15个苹果，周二卖的是周一的2倍，共多少？";

    @Test void parsesPlainJsonArray() {
        MockLlm llm = MockLlm.scripted(new AssistantReply("[\"计算周一\",\"计算周二\",\"求和\"]", List.of(), "end_turn"));
        List<String> plan = new Planner(llm).plan(QUESTION);
        assertEquals(List.of("计算周一", "计算周二", "求和"), plan);
    }

    @Test void parsesJsonArrayWrappedInText() {
        MockLlm llm = MockLlm.scripted(new AssistantReply("好的，计划如下: [\"a\",\"b\"] 完成", List.of(), "end_turn"));
        List<String> plan = new Planner(llm).plan(QUESTION);
        assertEquals(List.of("a", "b"), plan);
    }

    @Test void retriesThenReturnsEmptyOnPersistentBadOutput() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("不是JSON", List.of(), "end_turn"),
                new AssistantReply("[\"x\"]", List.of(), "end_turn"));
        assertEquals(List.of("x"), new Planner(llm, 2).plan(QUESTION));  // 第2次成功
    }

    @Test void returnsEmptyAfterMaxRetries() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("垃圾", List.of(), "end_turn"),
                new AssistantReply("垃圾", List.of(), "end_turn"),
                new AssistantReply("垃圾", List.of(), "end_turn"));
        assertTrue(new Planner(llm, 2).plan(QUESTION).isEmpty()); // 2 次重试仍失败
    }
}
```

- [ ] **Step 2: 跑测试确认失败**
Run: `mvn -q -Dtest=PlannerTest test`
Expected: FAIL——缺 Planner

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/plan/Planner.java`
```java
package dev.firstagent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.firstagent.AssistantReply;
import dev.firstagent.LlmProvider;
import dev.firstagent.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 规划器 —— 一次性生成行动计划（JSON 数组），lenient 解析 + 重试。
 * v1：静态计划，不做动态重规划（改善项）。
 */
public final class Planner {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String PROMPT = """
            你是一个顶级的AI规划专家。请将用户问题分解成一个由多个简单步骤组成的行动计划。
            每个步骤是一个独立的、可执行的子任务，按逻辑顺序排列。
            输出必须是一个 JSON 数组，例如：["步骤1", "步骤2", "步骤3"]
            只输出 JSON 数组本身，不要任何解释。"%n
            问题: %s""";

    private final LlmProvider llm;
    private final int maxRetries;

    public Planner(LlmProvider llm) { this(llm, 2); }
    public Planner(LlmProvider llm, int maxRetries) { this.llm = llm; this.maxRetries = maxRetries; }

    public List<String> plan(String question) {
        for (int i = 0; i <= maxRetries; i++) {
            AssistantReply r = llm.chat(List.of(Message.user(PROMPT.formatted(question))));
            String text = r.text();
            if (text == null) continue;
            List<String> plan = parseList(text);
            if (!plan.isEmpty()) return plan;
        }
        return List.of();
    }

    /** lenient：截取首个 '[' 到 末个 ']' 区间解析为 JSON 字符串数组。 */
    static List<String> parseList(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        try {
            var arr = M.readTree(text.substring(start, end + 1));
            if (!arr.isArray()) return List.of();
            List<String> out = new ArrayList<>();
            for (var node : arr) {
                if (node.isTextual() && !node.asText().isBlank()) out.add(node.asText().trim());
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**
Run: `mvn -q -Dtest=PlannerTest test`
Expected: PASS
- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/firstagent/plan/Planner.java src/test/java/dev/firstagent/plan/PlannerTest.java
git commit -m "feat(plan): Planner generates JSON-array plan with lenient parse + retry"
```

---

### Task 6: PlanExecutor（每步受控子循环 → 末步结果即答案）+ 测试

**Files:**
- Create: `src/main/java/dev/firstagent/plan/PlanExecutor.java`
- Test: `src/test/java/dev/firstagent/plan/PlanExecutorTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/plan/PlanExecutorTest.java`
```java
package dev.firstagent.plan;

import dev.firstagent.*;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.telemetry.AgentEventTelemetryBridge;
import dev.firstagent.telemetry.TelemetryRecorder;
import dev.firstagent.tools.CalculatorTool;
import dev.firstagent.tools.EchoTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanExecutorTest {

    @Test void executesEachStepAndReturnsLastResult() {
        // 计划 2 步；每步子循环：第1步无工具返回"a"，第2步调用 calculate 后返回结果
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("步骤1结果", List.of(), "end_turn"),              // 步1
                new AssistantReply("", List.of(new ToolCall("c1", "echo", "{\"m\":1}")), "tool_use"), // 步2 发起工具
                new AssistantReply("2+2=4", List.of(), "end_turn"));                  // 步2 收尾
        PlanExecutor ex = new PlanExecutor(llm, new ToolRegistry().register(new EchoTool()).register(new CalculatorTool()), 5, e -> { });

        String answer = ex.execute("问题", List.of("第一步", "第二步"));

        assertEquals("2+2=4", answer);
        assertEquals(3, llm.calls());       // 步1 ×1 + 步2 ×2
    }

    @Test void lastStepNoToolsReturnsFirstStepText() {
        MockLlm llm = MockLlm.scripted(new AssistantReply("单步答案", List.of(), "end_turn"));
        PlanExecutor ex = new PlanExecutor(llm, new ToolRegistry(), 5, e -> { });
        assertEquals("单步答案", ex.execute("q", List.of("only")));
    }

    @Test void emitsStepEventsInOrder() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("r1", List.of(), "end_turn"),
                new AssistantReply("r2", List.of(), "end_turn"));
        TelemetryRecorder rec = new TelemetryRecorder();
        AgentEventTelemetryBridge bridge = new AgentEventTelemetryBridge(rec);
        PlanExecutor ex = new PlanExecutor(llm, new ToolRegistry(), 5, bridge);

        ex.execute("q", List.of("s1", "s2"));

        assertTrue(rec.spanNames().contains("plan.start"));
        assertTrue(rec.spanNames().contains("plan.step.start"));
        assertTrue(rec.spanNames().contains("plan.step.end"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**
Run: `mvn -q -Dtest=PlanExecutorTest test`
Expected: FAIL——缺 PlanExecutor

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/plan/PlanExecutor.java`
```java
package dev.firstagent.plan;

import dev.firstagent.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 执行器 —— 严格按计划逐步骤执行。每步 = 一次受控 AgentLoop 子循环（步级 system 携带完整上下文），
 * 子循环最终文本 = 该步结果；结果累积成文本流入下一步。最后一步结果 = 最终答案。
 * v1 线性执行，不做动态重规划（改善项）。
 */
public final class PlanExecutor {
    private final LlmProvider llm;
    private final ToolRegistry tools;
    private final int stepMaxTurns;
    private final Consumer<AgentEvent> emit;

    public PlanExecutor(LlmProvider llm, ToolRegistry tools, int stepMaxTurns, Consumer<AgentEvent> emit) {
        this.llm = llm; this.tools = tools; this.stepMaxTurns = stepMaxTurns; this.emit = emit;
    }

    public String execute(String question, List<String> plan) {
        emit.accept(new AgentEvent.PlanStarted(question, plan));
        StringBuilder history = new StringBuilder("无");
        String result = "";
        for (int i = 0; i < plan.size(); i++) {
            String step = plan.get(i);
            emit.accept(new AgentEvent.PlanStepStarted(i, step));
            String stepSystem = """
                    你是一个执行专家。严格按照给定的计划逐步解决问题。
                    原始问题: %s
                    完整计划: %s
                    已完成步骤与结果:
                    %s
                    当前步骤: %s
                    如需工具请直接调用；输出仅针对当前步骤的结果文本。""".formatted(
                            question, plan, history, step);
            AgentLoop stepLoop = new AgentLoop(stepSystem, llm, tools, stepMaxTurns, LoopStrategy.endOnNoToolCall());
            result = stepLoop.execute(step, emit);
            history.append("\n步骤 ").append(i + 1).append(": ").append(step)
                   .append("\n结果: ").append(result);
            emit.accept(new AgentEvent.PlanStepEnded(i, step, result));
        }
        return result;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**
Run: `mvn -q -Dtest=PlanExecutorTest test`
Expected: PASS
- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/firstagent/plan/PlanExecutor.java src/test/java/dev/firstagent/plan/PlanExecutorTest.java
git commit -m "feat(plan): PlanExecutor runs each plan step as a bounded AgentLoop sub-loop"
```

---

### Task 7: CLI Demo（ReActDemo + PlanSolveDemo + 集成测试）

**Files:**
- Create: `src/main/java/dev/firstagent/app/ReActDemo.java`
- Create: `src/main/java/dev/firstagent/app/PlanSolveDemo.java`
- Test: `src/test/java/dev/firstagent/app/ParadigmsDemoTest.java`

- [ ] **Step 1: 写失败测试**（demo 用 MockLlm 脚本跑闭合并断言输出）

`src/test/java/dev/firstagent/app/ParadigmsDemoTest.java`
```java
package dev.firstagent.app;

import dev.firstagent.*;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.tools.CalculatorTool;
import dev.firstagent.tools.EchoTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParadigmsDemoTest {

    @Test void reactDemoPrintsFullLoop() {
        String out = new ReActDemo().runWith(MockLlm.scripted(
                new AssistantReply("我要先算一下。", List.of(new ToolCall("c1", "calculate", "{\"expression\":\"2+2\"}")), "tool_use"),
                new AssistantReply("答案: 4", List.of(), "end_turn")),
                new ToolRegistry().register(new CalculatorTool()));
        assertTrue(out.contains("Thought"), "应含 Thought: " + out);
        assertTrue(out.contains("calculate"), "应含工具名: " + out);
        assertTrue(out.contains("答案: 4"), "应含答案: " + out);
    }

    @Test void planSolveDemoRuns() {
        String out = new PlanSolveDemo().runWith(MockLlm.scripted(
                new AssistantReply("[\"计算2+2\"]", List.of(), "end_turn"),   // planner
                new AssistantReply("4", List.of(), "end_turn")),               // executor 第1步
                new ToolRegistry().register(new CalculatorTool()));
        assertTrue(out.contains("计划"), "应含计划: " + out);
        assertTrue(out.contains("4"), "应含结果: " + out);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**
Run: `mvn -q -Dtest=ParadigmsDemoTest test`
Expected: FAIL——缺 demo 类

- [ ] **Step 3: 写实现**（两个类，参考 `MinimalAgent` 结构：真实模型 vs Mock 兜底）

`src/main/java/dev/firstagent/app/ReActDemo.java`
```java
package dev.firstagent.app;

import dev.firstagent.*;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.llm.OpenAILlmProvider;
import dev.firstagent.llm.ToolSpec;
import dev.firstagent.tools.CalculatorTool;
import dev.firstagent.tools.EchoTool;

import java.util.List;

/** ReAct 完整闭环 demo：提问 → 思考 → 行动 → 观察 → Finish。真实模型(配 key)或 MockLlm 兜底。 */
public final class ReActDemo {
    private static final String SYSTEM = "你是一个演示助手。可以调用工具收集信息后准确回答。";

    public static void main(String[] args) {
        ReActDemo demo = new ReActDemo();
        ToolRegistry tools = new ToolRegistry().register(new CalculatorTool()).register(new EchoTool());
        AppConfig cfg = new AppConfig();
        LlmProvider llm = cfg.has("OPENAI_API_KEY")
                ? new OpenAILlmProvider(cfg, List.of(ToolSpec.from(new CalculatorTool()), ToolSpec.from(new EchoTool())))
                : MockLlm.scripted(
                        new AssistantReply("我使用计算工具先算准确。", List.of(new ToolCall("c1", "calculate", "{\"expression\":\"(123+456)*789/12\"}")), "tool_use"),
                        new AssistantReply("根据计算，结果为 38081.75。", List.of(), "end_turn"));
        demo.run(llm, tools);
    }

    public void run(LlmProvider llm, ToolRegistry tools) {
        String input = "(123+456)*789/12 的结果是多少？";
        ReActTrace trace = new ReActTrace();
        AgentLoop loop = new AgentLoop(SYSTEM, llm, tools, 8);
        String answer = loop.execute(input, trace);
        trace.finish(answer);

        System.out.println("\n==== ReAct 轨迹 ====");
        for (ReActTrace.Step s : trace.steps()) {
            System.out.println("Thought: " + (s.thought() == null ? "(无思考文本)" : s.thought()));
            System.out.println("Action: " + s.action());
            System.out.println("Observation: " + s.observation());
        }
        System.out.println("Finish: " + trace.finish());
        System.out.println("\n==== 最终答案 ====");
        System.out.println(answer);
    }

    /** 测试入口：注入脚本 LLM。 */
    public String runWith(LlmProvider llm, ToolRegistry tools) {
        ReActTrace trace = new ReActTrace();
        AgentLoop loop = new AgentLoop(SYSTEM, llm, tools, 8);
        String answer = loop.execute("(123+456)*789/12 的结果是多少？", trace);
        trace.finish(answer);
        StringBuilder sb = new StringBuilder();
        for (ReActTrace.Step s : trace.steps()) {
            sb.append("Thought: ").append(s.thought()).append('\n');
            sb.append("Action: ").append(s.action()).append('\n');
            sb.append("Observation: ").append(s.observation()).append('\n');
        }
        sb.append("Finish: ").append(trace.finish());
        return sb.toString();
    }
}
```

`src/main/java/dev/firstagent/app/PlanSolveDemo.java`
```java
package dev.firstagent.app;

import dev.firstagent.*;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.llm.OpenAILlmProvider;
import dev.firstagent.llm.ToolSpec;
import dev.firstagent.plan.PlanExecutor;
import dev.firstagent.plan.Planner;
import dev.firstagent.telemetry.AgentEventTelemetryBridge;
import dev.firstagent.telemetry.TelemetryRecorder;
import dev.firstagent.tools.CalculatorTool;

import java.util.List;

/** Plan-and-Execute demo：先规划 → 每步可调工具逐步执行。 */
public final class PlanSolveDemo {

    public static void main(String[] args) {
        PlanSolveDemo demo = new PlanSolveDemo();
        ToolRegistry tools = new ToolRegistry().register(new CalculatorTool());
        AppConfig cfg = new AppConfig();
        LlmProvider llm = cfg.has("OPENAI_API_KEY")
                ? new OpenAILlmProvider(cfg, List.of(ToolSpec.from(new CalculatorTool())))
                : MockLlm.scripted(
                        new AssistantReply("[\"计算 (123+456)*789 的值\",\"再除以 12 得出结果\"]", List.of(), "end_turn"),
                        new AssistantReply("", List.of(new ToolCall("c1", "calculate", "{\"expression\":\"(123+456)*789\"}")), "tool_use"),
                        new AssistantReply("456981", List.of(), "end_turn"),
                        new AssistantReply("", List.of(new ToolCall("c2", "calculate", "{\"expression\":\"456981/12\"}")), "tool_use"),
                        new AssistantReply("38081.75", List.of(), "end_turn"));
        demo.run(llm, tools);
    }

    public void run(LlmProvider llm, ToolRegistry tools) {
        String question = "计算 (123+456)*789/12 的结果是多少？请分步骤计算。";
        System.out.println("\n==== 问题 ====\n" + question);

        TelemetryRecorder telemetry = new TelemetryRecorder();
        AgentEventTelemetryBridge bridge = new AgentEventTelemetryBridge(telemetry);

        Planner planner = new Planner(llm);
        List<String> plan = planner.plan(question);
        System.out.println("\n==== 计划 ====");
        System.out.println(plan);
        if (plan.isEmpty()) { System.out.println("计划生成失败，终止。"); return; }

        PlanExecutor executor = new PlanExecutor(llm, tools, 5, bridge);
        String answer = executor.execute(question, plan);

        System.out.println("\n==== telemetry spans ====");
        telemetry.prettyPrint();
        System.out.println("\n==== 最终答案 ====");
        System.out.println(answer);
    }

    /** 测试入口。 */
    public String runWith(LlmProvider llm, ToolRegistry tools) {
        String question = "计算 (123+456)*789/12 的结果是多少？请分步骤计算。";
        List<String> plan = new Planner(llm).plan(question);
        String answer = new PlanExecutor(llm, tools, 5, e -> { }).execute(question, plan);
        return "计划: " + plan + "\n结果: " + answer;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**
Run: `mvn -q -Dtest=ParadigmsDemoTest test`
Expected: PASS
- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/firstagent/app/ReActDemo.java src/main/java/dev/firstagent/app/PlanSolveDemo.java src/test/java/dev/firstagent/app/ParadigmsDemoTest.java
git commit -m "feat(app): ReActDemo + PlanSolveDemo CLI for complete paradigm loops"
```

---

### Task 8: 全量校验 + README

**Files:**
- Edit: `README.md`

- [ ] **Step 1: 全量跑测试**
Run: `mvn test`
Expected: 全部 PASS（新增 8 个测试类 + 现有全不回归）

- [ ] **Step 2: 跑 CLI demo（Mock 兜底离线）**
Run: `mvn -q compile exec:java -Dexec.mainClass=dev.firstagent.app.ReActDemo` —— 或直接 javac 运行；预期输出轨迹 + 答案。
对应 `PlanSolveDemo`。

- [ ] **Step 3: 更新 README**（补两范式说明 + demo 运行命令）
```markdown
## 智能体范式 Demo
- ReAct：`mvn -q compile exec:java -Dexec.mainClass=dev.firstagent.app.ReActDemo`（真实模型配 key；否则 MockLlm 兜底打印完整闭环轨迹）
- Plan-and-Execute：同上换 `PlanSolveDemo`
- CalculatorTool：离线确定性计算工具，演示"知识不足 → 调工具 → 观察 → 总结"
```

- [ ] **Step 4: Commit**
```bash
git add README.md
git commit -m "docs: add paradigm demos + README"
```

---

## Self-Review（对 spec AC）

- **AC-1 mvn test 全绿**：Task 8-1 ✓
- **AC-2 ReAct 完整闭环可断言**：Task 3 `ReActTraceTest`（提问→≥1工具→Finish）✓
- **AC-3 真实闭环（provider 发 tools）**：Task 4 `OpenAILlmProviderTest` 本地 HttpServer 断言 body 含 tools ✓；无 key 集成测试沿用 Assumption（既有）
- **AC-4 Plan-and-Execute**：Task 5 Planner（parse/retry/空）、Task 6 PlanExecutor（子循环、累积、末步即答案）✓
- **AC-5 事件可观测**：Task 2 AgentEvent 扩展 + 桥映射 span；Task 6 测 span 时序 ✓
- **AC-6 CLI demo**：Task 7 ReActDemo/PlanSolveDemo（runWith 可测 + main 可跑）✓
- **AC-7 隔离**：只新增；改动仅 AgentEvent/AgentEventTelemetryBridge/OpenAILlmProvider 扩点；AppConfig 批不碰 ✓

**改善项（spec §六，本期不实现）：** 汇总调用、动态重规划、接记忆/压缩、完整 input_schema、tool-module-mvp 合流、Executor 节点化省 token、流式/重试/并发。

**风险：** JDK17 无 Nashorn → CalculatorTool 可能需要自实现四则解析（Append-A）；`ObjectNode.deepCopy()` 保证 LOOSE_PARAMS 不共享可变节点。