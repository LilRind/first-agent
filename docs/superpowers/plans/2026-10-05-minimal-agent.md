# 最小 Agent（工具 + 长期记忆 + 压缩 + telemetry + 集成测试）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于已合并的 session-mvp，在 feature/minimal-agent 隔离 worktree 里做出一个能「用工具 + 跨会话召回记忆 + 上下文压缩 + span 级 telemetry + 集成测试」的最小 Agent，`mvn test` 全绿，CLI 用 MockLlm 跑通闭环。

**Architecture:** 复用 `SessionStore`(JSONL 持久化) / `ContextCompactor` seam / `AgentLoop`(双 while) / `AgentEvent` / `LoopStrategy` 钩子。把 `LoopStrategy.prepareRequest` 从 void 改为返回 `List<Message>`（默认恒等），让"记忆注入 + 压缩折叠"真正决定 `llm.chat` 的输入。记忆 = `MemoryRecallStrategy`(一个 LoopStrategy)：首轮 `findMostRecent(cwd)` 召回旧会话摘要前置 + 每轮把新消息 append 进 SessionStore 持久化 + `prepareRequest` 返回压缩后的列表。telemetry = span 录制器 + 事件桥（把 AgentEvent 映射成 span）。

**Tech Stack:** Java 17、Maven、Jackson、JUnit 5。复用 `dev.firstagent` 既有类，不引新依赖。

---

## 文件结构（本次新增/改动）

| 文件 | 类型 | 职责 |
|---|---|---|
| `src/main/java/dev/firstagent/LoopStrategy.java` | MODIFY | `prepareRequest` 改为返回 `List<Message>`（默认恒等） |
| `src/main/java/dev/firstagent/AgentLoop.java` | MODIFY | 用 `prepareRequest` 返回值当 `llm.chat` 输入 |
| `src/main/java/dev/firstagent/telemetry/TelemetrySpan.java` | CREATE | span record（name/attributes/start/end/parentId） |
| `src/main/java/dev/firstagent/telemetry/TelemetryRecorder.java` | CREATE | 内存录制 span，可断言、可选打印 |
| `src/main/java/dev/firstagent/telemetry/AgentEventTelemetryBridge.java` | CREATE | `Consumer<AgentEvent>`：事件 → span |
| `src/main/java/dev/firstagent/session/SummarizingCompactor.java` | CREATE | 实现 `ContextCompactor`：超阈值折叠旧消息成摘要；另暴露 `compact(List<Message>)` |
| `src/test/java/dev/firstagent/telemetry/TelemetryRecorderTest.java` | CREATE | span 录制/时序/属性断言 |
| `src/test/java/dev/firstagent/session/SummarizingCompactorTest.java` | CREATE | 折叠阈值 / 摘要 entry / 不折叠 |
| `src/main/java/dev/firstagent/memory/MemoryRecallStrategy.java` | CREATE | 编排：召回+持久化+压缩（一个 LoopStrategy） |
| `src/test/java/dev/firstagent/memory/MemoryRecallIntegrationTest.java` | CREATE | fixture：预置旧会话 → 断言记忆注入 + span 时序 + 压缩折叠 |
| `src/main/java/dev/firstagent/app/MinimalAgent.java` | CREATE | 装配 + CLI main（MockLlm） |

**不改**：`SessionStore`/`SessionProjector`/`Session`/`MessageEntry`/`ContextCompactor`(接口)/`Message`/`AgentEvent`/`MockLlm`/`ToolRegistry`/`EchoTool`/`FailTool`。

---

### Task 1: `LoopStrategy.prepareRequest` 改为返回 `List<Message>`（对齐 pi，让压缩/记忆真实生效）

**Files:**
- Modify: `src/main/java/dev/firstagent/LoopStrategy.java`
- Modify: `src/main/java/dev/firstagent/AgentLoop.java`
- Test: `src/test/java/dev/firstagent/AgentLoopTest.java`（不变，应继续绿）

- [ ] **Step 1: 改 `LoopStrategy.prepareRequest` 签名（void → 返回 List）**

把 `prepareRequest` 一处改为返回"真正发给模型的输入"；默认恒等，老语义不变：

```java
    /**
     * 对齐 pi prepareRequest：发请求前投影/路由。MVP 默认恒等（返回原历史）。
     * 改为返回"实际发给模型的消息"，压缩(折叠)/记忆(注入)才能在 prepareRequest 里真正生效。
     */
    default List<Message> prepareRequest(List<Message> history) { return history; }
```

- [ ] **Step 2: `AgentLoop` 用返回值当 `llm.chat` 输入**

在 `AgentLoop.execute` 内，把这两行：

```java
                strategy.prepareRequest(history);      // pi prepareRequest：投影/路由（MVP no-op）

                AssistantReply reply = llm.chat(history);      // 请求由派生历史
```

改成下面两行（用返回值当输入）：

```java
                List<Message> requestMessages = strategy.prepareRequest(history);  // 压缩/记忆在此生效
                AssistantReply reply = llm.chat(requestMessages);                   // 请求 = prepareRequest 的返回值
```

- [ ] **Step 3: 运行既有测试，确认语义不变**

Run:
```bash
cd /c/Users/13374/IdeaProjects/first-agent-minimal && JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)" PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH" mvn -q test
```
Expected: 现有 `AgentLoopTest` 等全绿（默认 `prepareRequest` 返回原 history，行为不变）。

- [ ] **Step 4: Commit**
```bash
git add src/main/java/dev/firstagent/LoopStrategy.java src/main/java/dev/firstagent/AgentLoop.java
git commit -m "refactor(strategy): prepareRequest returns List<Message> (identity default) so memory/compression shape llm input"
```

---

### Task 2: telemetry 核心 —— `TelemetrySpan` + `TelemetryRecorder`

**Files:**
- Create: `src/main/java/dev/firstagent/telemetry/TelemetrySpan.java`
- Create: `src/main/java/dev/firstagent/telemetry/TelemetryRecorder.java`
- Test: `src/test/java/dev/firstagent/telemetry/TelemetryRecorderTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/telemetry/TelemetryRecorderTest.java`:
```java
package dev.firstagent.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryRecorderTest {

    @Test void recordsSpansInOrderWithAttributes() {
        TelemetryRecorder r = new TelemetryRecorder();
        r.record("turn.start", Map.of());
        r.record("tool.start", Map.of("tool", "echo"));
        r.record("agent.end", Map.of("ok", "true"));

        assertEquals(List.of("turn.start", "tool.start", "agent.end"), r.spanNames());
        TelemetrySpan tool = r.spans().get(1);
        assertEquals("tool.start", tool.name());
        assertEquals("echo", tool.attributes().get("tool"));
        assertTrue(tool.durationNanos() >= 0);
    }
}
```

- [ ] **Step 2: Run 确认失败**
Run: `cd /c/Users/13374/IdeaProjects/first-agent-minimal && JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)" PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH" mvn -q -Dtest=TelemetryRecorderTest test`
Expected: FAIL（`TelemetryRecorder` 不存在）。

- [ ] **Step 3: 实现 telemetry 两文件**

`src/main/java/dev/firstagent/telemetry/TelemetrySpan.java`:
```java
package dev.firstagent.telemetry;

import java.util.Map;

/** 一条 span —— name + 属性 + 起止纳秒 + 可选 parentId。对齐 pi 的 TelemetrySpan（简化）。 */
public record TelemetrySpan(String name, Map<String, String> attributes,
                            long startNanos, long endNanos, String parentId) {
    public long durationNanos() { return endNanos - startNanos; }
}
```

`src/main/java/dev/firstagent/telemetry/TelemetryRecorder.java`:
```java
package dev.firstagent.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 内存 span 录制器：记录已完成 span，供测试断言 / CLI 打印。线程安全(同步块)。 */
public final class TelemetryRecorder {
    private final List<TelemetrySpan> spans = new ArrayList<>();

    /** 记录一条起止瞬时(时长 0)的 span；attrs 为空则用空 map。 */
    public void record(String name, Map<String, String> attributes) {
        long now = System.nanoTime();
        Map<String, String> attrs = attributes == null ? Map.of() : attributes;
        synchronized (spans) {
            spans.add(new TelemetrySpan(name, attrs, now, now, null));
        }
    }

    /** 全部 span，按录制顺序。 */
    public List<TelemetrySpan> spans() {
        synchronized (spans) { return List.copyOf(spans); }
    }

    /** 仅 span 名列表，方便断言时序。 */
    public List<String> spanNames() {
        return spans().stream().map(TelemetrySpan::name).toList();
    }

    /** 打印全部 span(CLI 用)。 */
    public void prettyPrint() {
        for (TelemetrySpan s : spans()) System.out.println(s.name() + " " + s.attributes());
    }
}
```

- [ ] **Step 4: Run 确认通过**
Run: `cd /c/Users/13374/IdeaProjects/first-agent-minimal && JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)" PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH" mvn -q -Dtest=TelemetryRecorderTest test`
Expected: PASS。

- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/firstagent/telemetry src/test/java/dev/firstagent/telemetry
git commit -m "feat(telemetry): add Span + in-memory recorder (assertable)"
```

---

### Task 3: 压缩 —— `SummarizingCompactor`（实现 `ContextCompactor` seam）

**Files:**
- Create: `src/main/java/dev/firstagent/session/SummarizingCompactor.java`
- Test: `src/test/java/dev/firstagent/session/SummarizingCompactorTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/session/SummarizingCompactorTest.java`:
```java
package dev.firstagent.session;

import dev.firstagent.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SummarizingCompactorTest {

    private static Message msg(String text) { return Message.user(text); }

    @Test void foldsOldMessagesIntoSummaryWhenOverBudget() {
        List<Message> history = List.of(
                msg("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),  // 32 字符 → 8 tokens
                msg("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),  // 32 → 8
                msg("cccccccccccccccccccccccccccccccc"),  // 32 → 8
                msg("dddddddddddddddddddddddddddddddd"),  // 32 → 8
                msg("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")); // 32 → 8  合计 40 > budget 30
        SummarizingCompactor c = new SummarizingCompactor(30);

        Optional<List<Message>> out = c.compact(history);

        assertTrue(out.isPresent(), "超预算应折叠");
        List<Message> folded = out.get();
        assertTrue(folded.get(0).text().startsWith("[压缩摘要]"), "首条应为摘要 system");
        // 保留最新 KEEP_RECENT=4 条原文
        for (int i = 1; i < folded.size(); i++) {
            assertEquals(history.get(history.size() - 4 + (i - 1)), folded.get(i));
        }
    }

    @Test void returnsEmptyWhenUnderBudget() {
        List<Message> history = List.of(msg("a"), msg("b"), msg("c"));
        SummarizingCompactor c = new SummarizingCompactor(1000);

        assertTrue(c.compact(history).isEmpty(), "预算内不压缩");
    }

    @Test void returnsEmptyWhenNotEnoughMessages() {
        List<Message> history = List.of(
                msg("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                msg("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        SummarizingCompactor c = new SummarizingCompactor(1);  // 超预算，但只有 2 条 ≤ KEEP_RECENT

        assertTrue(c.compact(history).isEmpty(), "消息太少不折叠");
    }
}
```

- [ ] **Step 2: Run 确认失败**
Run: `cd /c/Users/13374/IdeaProjects/first-agent-minimal && JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)" PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH" mvn -q -Dtest=SummarizingCompactorTest test`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现**

`src/main/java/dev/firstagent/session/SummarizingCompactor.java`:
```java
package dev.firstagent.session;

import dev.firstagent.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 朴素折叠压缩 —— 实现 ContextCompactor seam。TokenEstimator 简化为「字符数/4」。
 * 预算内 → 不压缩；超预算且消息足够多 → 把最旧(超出 KEEP_RECENT)的消息折叠成一条
 * "[压缩摘要]" system 消息前置，保留最新 KEEP_RECENT 条原文。原始输入列表绝不修改。
 */
public final class SummarizingCompactor implements ContextCompactor {

    /** 保留最新 N 条原文，更早的全部折叠进摘要。 */
    static final int KEEP_RECENT = 4;
    private static final int CHARS_PER_TOKEN = 4;

    private final int budgetTokens;

    public SummarizingCompactor(int budgetTokens) {
        this.budgetTokens = budgetTokens;
    }

    /** 直接作用于消息列表的压缩入口（prepareRequest 用它，不依赖持久化）。 */
    public Optional<List<Message>> compact(List<Message> messages) {
        int tokens = messages.stream().mapToInt(m -> sizeTokens(m)).sum();
        if (tokens <= budgetTokens) return Optional.empty();
        if (messages.size() <= KEEP_RECENT) return Optional.empty();

        List<Message> older = messages.subList(0, messages.size() - KEEP_RECENT);
        List<Message> recent = messages.subList(messages.size() - KEEP_RECENT, messages.size());
        String summaryText = older.stream()
                .map(Message::text).filter(s -> s != null && !s.isBlank())
                .reduce((a, b) -> a + "\n" + b).orElse("(无文本)");

        List<Message> out = new ArrayList<>();
        out.add(Message.system("[压缩摘要] " + summaryText));
        out.addAll(recent);
        return Optional.of(out);
    }

    /** ContextCompactor seam：适配会话日志投影。 */
    @Override
    public Optional<List<Message>> maybeCompact(Session session) {
        return compact(session.entries().stream().map(MessageEntry::message).toList());
    }

    private static int sizeTokens(Message m) {
        String t = m.text();
        return Math.max(1, t.length() / CHARS_PER_TOKEN);
    }
}
```

- [ ] **Step 4: Run 确认通过**
Run: `cd /c/Users/13374/IdeaProjects/first-agent-minimal && JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)" PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH" mvn -q -Dtest=SummarizingCompactorTest test`
Expected: PASS。

- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/firstagent/session/SummarizingCompactor.java src/test/java/dev/firstagent/session/SummarizingCompactorTest.java
git commit -m "feat(session): naive-fold SummarizingCompactor (implements ContextCompactor seam)"
```

---

### Task 4: 事件 → span 桥 `AgentEventTelemetryBridge`

**Files:**
- Create: `src/main/java/dev/firstagent/telemetry/AgentEventTelemetryBridge.java`
- Test: `src/test/java/dev/firstagent/telemetry/AgentEventTelemetryBridgeTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/telemetry/AgentEventTelemetryBridgeTest.java`:
```java
package dev.firstagent.telemetry;

import dev.firstagent.AgentEvent;
import dev.firstagent.Message;
import dev.firstagent.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventTelemetryBridgeTest {

    @Test void mapsEventsToSpanSequence() {
        TelemetryRecorder r = new TelemetryRecorder();
        AgentEventTelemetryBridge bridge = new AgentEventTelemetryBridge(r);

        bridge.accept(new AgentEvent.TurnStarted());
        bridge.accept(new AgentEvent.ToolStarted(new ToolCall("c1", "echo", "{}")));
        bridge.accept(new AgentEvent.TurnEnded());
        bridge.accept(new AgentEvent.AgentEnded(List.of()));

        assertEquals(List.of("turn.start", "tool.start", "turn.end", "agent.end"), r.spanNames());
        // 工具 span 带 tool 属性
        assertEquals("echo", r.spans().get(1).attributes().get("tool"));
    }
}
```

- [ ] **Step 2: Run 确认失败**
Run: `cd /c/Users/13374/IdeaProjects/first-agent-minimal && JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)" PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH" mvn -q -Dtest=AgentEventTelemetryBridgeTest test`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现**

`src/main/java/dev/firstagent/telemetry/AgentEventTelemetryBridge.java`:
```java
package dev.firstagent.telemetry;

import dev.firstagent.AgentEvent;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 把 AgentEvent 事件流映射成 telemetry span。作为 Consumer<AgentEvent> 传给
 * AgentLoop.execute(input, emit) —— 引擎只管发事件，桥只管录 span（可观测与执行解耦）。
 */
public final class AgentEventTelemetryBridge implements Consumer<AgentEvent> {
    private final TelemetryRecorder rec;

    public AgentEventTelemetryBridge(TelemetryRecorder rec) { this.rec = rec; }

    @Override
    public void accept(AgentEvent e) {
        if (e instanceof AgentEvent.TurnStarted) rec.record("turn.start", Map.of());
        else if (e instanceof AgentEvent.MessageStarted m)
            rec.record("message.start", Map.of("role", m.message().role().name()));
        else if (e instanceof AgentEvent.MessageEnded) rec.record("message.end", Map.of());
        else if (e instanceof AgentEvent.ToolStarted t)
            rec.record("tool.start", Map.of("tool", t.call().name()));
        else if (e instanceof AgentEvent.ToolEnded t)
            rec.record("tool.end", Map.of("tool", t.call().name()));
        else if (e instanceof AgentEvent.TurnEnded) rec.record("turn.end", Map.of());
        else if (e instanceof AgentEvent.AgentEnded) rec.record("agent.end", Map.of());
    }
}
```

- [ ] **Step 4: Run 确认通过**
Run: `cd /c/Users/13374/IdeaProjects/first-agent-minimal && JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)" PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH" mvn -q -Dtest=AgentEventTelemetryBridgeTest test`
Expected: PASS。

- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/firstagent/telemetry/AgentEventTelemetryBridge.java src/test/java/dev/firstagent/telemetry/AgentEventTelemetryBridgeTest.java
git commit -m "feat(telemetry): bridge AgentEvent stream -> spans"
```

---

### Task 5: 记忆编排 + 集成测试 —— `MemoryRecallStrategy`

**Files:**
- Create: `src/main/java/dev/firstagent/memory/MemoryRecallStrategy.java`
- Test: `src/test/java/dev/firstagent/memory/MemoryRecallIntegrationTest.java`

- [ ] **Step 1: 写失败集成测试（fixture）**

`src/test/java/dev/firstagent/memory/MemoryRecallIntegrationTest.java`:
```java
package dev.firstagent.memory;

import dev.firstagent.*;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.session.Session;
import dev.firstagent.session.SessionStore;
import dev.firstagent.session.SummarizingCompactor;
import dev.firstagent.telemetry.AgentEventTelemetryBridge;
import dev.firstagent.telemetry.TelemetryRecorder;
import dev.firstagent.tools.EchoTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成小测试框架：fixture 驱动。预置一段旧会话 → 跑 MemoryRecallStrategy 驱动的 AgentLoop →
 * 断言 (a) 跨会话记忆注入历史 (b) telemetry span 时序 (c) 超阈值压缩折叠。
 */
class MemoryRecallIntegrationTest {

    private static final String CWD = "cwdX";
    private static final String SYSTEM = "你是一个演示助手。" + "a".repeat(120); // 60+ tokens，保证超预算

    @TempDir Path tmp;

    @Test void recallsPriorSession_recordsTelemetry_andCompacts() {
        SessionStore store = new SessionStore(tmp);
        // 预置「更早一次会话」作为长期记忆来源
        Session prev = store.create(CWD, "prior");
        store.append(prev, Message.user("用户上次要北京天气"));
        store.append(prev, Message.assistant("用户决定去长城", List.of()));

        TelemetryRecorder telemetry = new TelemetryRecorder();
        SummarizingCompactor compactor = new SummarizingCompactor(40); // 小预算强制压缩
        MemoryRecallStrategy strategy = new MemoryRecallStrategy(store, compactor, CWD, telemetry);
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("c1", "echo", "{\"a\":1}")), "tool_use"),
                new AssistantReply("", List.of(new ToolCall("c2", "echo", "{\"b\":2}")), "tool_use"),
                new AssistantReply("最后答案", List.of(), "end_turn"));
        ToolRegistry tools = new ToolRegistry().register(new EchoTool());
        AgentLoop loop = new AgentLoop(SYSTEM, llm, tools, 10, strategy);

        String answer = loop.execute("你好", new AgentEventTelemetryBridge(telemetry));

        assertEquals("最后答案", answer);

        // (a) 跨会话记忆召回并注入历史
        List<Message> history = llm.lastHistory();
        assertTrue(history.stream().anyMatch(m ->
                m.role() == Message.Role.SYSTEM && m.text().contains("长期记忆")),
                "应注入上上次会话的压缩摘要作为长期记忆");
        assertTrue(history.stream().anyMatch(m -> m.text().contains("北京天气")),
                "召回内容应含旧会话关键词");

        // (c) 超阈值压缩折叠发生：历史里出现 [压缩摘要] system
        assertTrue(history.stream().anyMatch(m ->
                m.role() == Message.Role.SYSTEM && m.text().startsWith("[压缩摘要]")),
                "应发生折叠压缩");

        // (b) telemetry span 时序：memory 命中 + agent 收尾都在
        List<String> names = telemetry.spanNames();
        assertTrue(names.contains("memory.recall"), "应录 memory.recall span");
        assertTrue(names.contains("memory.inject"), "应录 memory.inject span");
        assertTrue(names.contains("agent.end"), "应录 agent.end span");
        // 事件桥 sequence 之前录音于 memory span
        assertTrue(names.indexOf("memory.recall") < names.indexOf("agent.end"),
                "memory.recall 应先于 agent.end");
    }

    @Test void appendsMessagesToSessionStore() {
        SessionStore store = new SessionStore(tmp);
        TelemetryRecorder telemetry = new TelemetryRecorder();
        MemoryRecallStrategy strategy =
                new MemoryRecallStrategy(store, new SummarizingCompactor(100000), CWD, telemetry);
        MockLlm llm = MockLlm.scripted(new AssistantReply("明白", List.of(), "end_turn"));
        AgentLoop loop = new AgentLoop(SYSTEM, llm, new ToolRegistry().register(new EchoTool()), 10, strategy);

        loop.execute("测试持久化", new AgentEventTelemetryBridge(telemetry));

        // 本轮产生的会话落盘了，能按 id 再加载出来
        assertEquals(1, store.list().size(), "应持久化 1 个会话");
        Session s = store.list().get(0);
        assertTrue(s.entries().size() >= 2, "会话应至少含 user + assistant");
    }
}
```

- [ ] **Step 2: Run 确认失败**
Run: `cd /c/Users/13374/IdeaProjects/first-agent-minimal && JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)" PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH" mvn -q -Dtest=MemoryRecallIntegrationTest test`
Expected: FAIL（`MemoryRecallStrategy` 不存在）。

- [ ] **Step 3: 实现 `MemoryRecallStrategy`**

`src/main/java/dev/firstagent/memory/MemoryRecallStrategy.java`:
```java
package dev.firstagent.memory;

import dev.firstagent.*;
import dev.firstagent.session.Session;
import dev.firstagent.session.SessionStore;
import dev.firstagent.session.SummarizingCompactor;
import dev.firstagent.telemetry.TelemetryRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆编排策略 —— 一个 LoopStrategy 把三条链路串进 AgentLoop：
 * 1. 跨会话召回：首轮 findMostRecent(cwd) 把旧会话折叠摘要作为「长期记忆」注入。
 * 2. 持久化：每轮把新产出的 Message append 进 SessionStore（崩溃安全 JSONL）。
 * 3. 压缩：prepareRequest 返回 SummarizingCompactor.compact(history) 的结果。
 *
 * prepareRequest 改为返回 List<Message>，记忆注入 + 压缩折叠才对 llm.chat 真正生效。
 */
public final class MemoryRecallStrategy implements LoopStrategy {

    private static final String MEMORY_PREFIX = "[长期记忆] ";

    private final SessionStore store;
    private final SummarizingCompactor compactor;
    private final String cwd;
    private final TelemetryRecorder telemetry;

    private Session currentSession;
    private Message memoryPrefix;      // 召回出的长期记忆 system 消息
    private boolean memoryInjected;
    private int persistedCount;        // 已持久化到 store 的历史条数（增量 append，避免重复）

    public MemoryRecallStrategy(SessionStore store, SummarizingCompactor compactor,
                                String cwd, TelemetryRecorder telemetry) {
        this.store = store;
        this.compactor = compactor;
        this.cwd = cwd;
        this.telemetry = telemetry;
    }

    @Override
    public void prepareNextTurn(AssistantReply lastReply) {
        if (currentSession != null) return;          // 仅首轮初始化
        if (memoryPrefix == null) {
            Optional<Session> prior = store.findMostRecent(cwd);
            if (prior.isPresent()) {
                List<String> texts = prior.get().entries().stream()
                        .map(e -> e.message().text()).filter(s -> s != null && !s.isBlank()).toList();
                String summary = String.join("\n", texts);
                if (!summary.isBlank()) {
                    memoryPrefix = Message.system(MEMORY_PREFIX + summary);
                    telemetry.record("memory.recall", Map.of("cwd", cwd));
                }
            }
        }
        currentSession = store.create(cwd, "minimal-agent");
        telemetry.record("session.start", Map.of("cwd", cwd));
    }

    @Override
    public List<Message> prepareRequest(List<Message> history) {
        persistNew(history);
        List<Message> compacted = compactor.compact(history).orElse(history);
        List<Message> out = new ArrayList<>();
        if (memoryPrefix != null && !memoryInjected) {
            out.add(memoryPrefix);
            memoryInjected = true;
            telemetry.record("memory.inject", Map.of("cwd", cwd));
        }
        // 若发生了折叠，记一条 span（比原始历史少了几条）
        if (compacted.size() < history.size()) {
            telemetry.record("compaction", Map.of("before", String.valueOf(history.size()),
                    "after", String.valueOf(compacted.size())));
        }
        out.addAll(compacted);
        return out;
    }

    @Override
    public TurnDecision finishTurn(List<Message> history, AssistantReply lastReply) {
        return lastReply.hasToolCalls() ? TurnDecision.continueTurn() : TurnDecision.end();
    }

    /** 把 history 里超出已持久化计数的新消息逐条 append 进 SessionStore。 */
    private void persistNew(List<Message> history) {
        if (currentSession == null) return;
        for (int i = persistedCount; i < history.size(); i++) {
            store.append(currentSession, history.get(i));
        }
        persistedCount = history.size();
    }
}
```

- [ ] **Step 4: Run 确认通过**
Run: `cd /c/Users/13374/IdeaProjects/first-agent-minimal && JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)" PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH" mvn -q -Dtest=MemoryRecallIntegrationTest test`
Expected: PASS。若 `（a）北京天气`断言失败，检查 `findMostRecent` 是否命中预置会话（预置会话与新建会话同 cwd，新建在 prepareNextTurn 里发生，findMostRecent 在 create 之前调用 → 应命中 pre 会话）。

- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/firstagent/memory src/test/java/dev/firstagent/memory
git commit -m "feat(memory): MemoryRecallStrategy wires cross-session recall + persistence + compression; integration test"
```

---

### Task 6: 装配 + CLI `MinimalAgent`

**Files:**
- Create: `src/main/java/dev/firstagent/app/MinimalAgent.java`

- [ ] **Step 1: 实现 CLI**

`src/main/java/dev/firstagent/app/MinimalAgent.java`:
```java
package dev.firstagent.app;

import dev.firstagent.*;
import dev.firstagent.llm.MockLlm;
import dev.firstagent.memory.MemoryRecallStrategy;
import dev.firstagent.session.SessionStore;
import dev.firstagent.session.SummarizingCompactor;
import dev.firstagent.telemetry.AgentEventTelemetryBridge;
import dev.firstagent.telemetry.TelemetryRecorder;
import dev.firstagent.tools.EchoTool;
import dev.firstagent.tools.FailTool;

import java.nio.file.Path;
import java.util.List;

/**
 * 最小 Agent CLI —— 先跑起来看到结果。
 * 复用 MockLlm 离线跑通「工具调用 → 收尾」闭环，打 telemetry span + 最终答案。
 * 换真模型：把 MockLlm 换成 OpenAILlmProvider() 并配 OPENAI_API_KEY 即可。
 */
public final class MinimalAgent {

    private static final String SYSTEM = "你是一个演示助手。可以调用工具收集信息后回答。";

    public static void main(String[] args) {
        String userInput = args.length > 0 ? args[0] : "请用 echo 工具回显一句话，然后告诉我结果。";

        SessionStore store = new SessionStore(Path.of("sessions"));
        SummarizingCompactor compactor = new SummarizingCompactor(512);
        TelemetryRecorder telemetry = new TelemetryRecorder();
        String cwd = Path.of("").toAbsolutePath().toString();
        MemoryRecallStrategy strategy = new MemoryRecallStrategy(store, compactor, cwd, telemetry);

        ToolRegistry tools = new ToolRegistry().register(new EchoTool()).register(new FailTool());
        LlmProvider llm = MockLlm.scripted(
                new AssistantReply("", List.of(new ToolCall("call_1", "echo", "{\"msg\":\"你好\"}")), "tool_use"),
                new AssistantReply("已用 echo 工具完成回显，工具结果见调用记录。", List.of(), "end_turn"));

        AgentLoop loop = new AgentLoop(SYSTEM, llm, tools, 10, strategy);
        AgentEventTelemetryBridge bridge = new AgentEventTelemetryBridge(telemetry);

        String answer = loop.execute(userInput, bridge);

        System.out.println("\n==== telemetry spans ====");
        telemetry.prettyPrint();
        System.out.println("\n==== 最终答案 ====");
        System.out.println(answer);
    }
}
```

- [ ] **Step 2: 编译 + 跑 CLI**

Run:
```bash
cd /c/Users/13374/IdeaProjects/first-agent-minimal && JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)" PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH" mvn -q compile
cd /c/Users/13374/IdeaProjects/first-agent-minimal && JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)" PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH" mvn -q exec:java -Dexec.mainClass=dev.firstagent.app.MinimalAgent
```
Expected: 打印 telemetry span 列表（含 turn/message/tool/agent + memory/session）与最终答案，不抛异常。第二次运行能看到首次会话被 `memory.recall` 召回（sessions/ 下持久化生效）。

- [ ] **Step 3: 全量回归**
Run: `cd /c/Users/13374/IdeaProjects/first-agent-minimal && JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)" PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH" mvn -q test`
Expected: 全绿（既有 + TelemetryRecorderTest + SummarizingCompactorTest + AgentEventTelemetryBridgeTest + MemoryRecallIntegrationTest）。

- [ ] **Step 4: 隐私扫描（必做，不提交敏感路径/密钥）**
Run: `cd /c/Users/13374/IdeaProjects/first-agent-minimal && git add -A && git status`
Expected: 仅新增 `dev/firstagent/{telemetry,session,memory,app}/*.java` + 测试 + `docs/superpowers/plans/2026-10-05-minimal-agent.md`；**无** .idea/sessions/*.jsonl / 本机绝对路径 / sk- 密钥。若 `sessions/` 落盘了测试会话，确认已被 `.gitignore` 忽略（session-mvp 已加 `/.ignore runtime session storage dir`），否则用 `git check-ignore sessions` 验证。

- [ ] **Step 5: Commit**
```bash
git add src/main/java/dev/firstagent/app src/main/java/dev/firstagent/memory docs/superpowers/plans/2026-10-05-minimal-agent.md
git commit -m "feat(app): MinimalAgent CLI assembles loop + memory + compression + telemetry"
```

---

## 自检记录
- **Spec 覆盖**：§三 telemetry 3 文件 → Task 2/4；§三压缩 → Task 3；§三记忆 → Task 5；§三 app → Task 6；§二唯一改动 prepareRequest → Task 1；§六测试 → Task 3/4/5；§七 AC1→Task6 全量、AC2/AC3→Task6 CLI 双跑、AC4→Task1。
- **占位扫描**：每步含完整可执行代码，无误；CLI 真 provider 留作注释说明（spec §八 明确延后）。
- **类型一致性**：`prepareRequest` 返回 `List<Message>` 贯穿 Task1/5；`compact(List<Message>)` 与 `maybeCompact(Session)` 一致；`SummarizingCompactor(budgetTokens)` 构造在 Task3/5/6 统一。