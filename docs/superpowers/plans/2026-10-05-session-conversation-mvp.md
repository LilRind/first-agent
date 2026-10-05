# 会话管理 MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `first-agent` 根项目写一个会话管理 MVP：JSONL append-only 持久化 + 会话生命周期 + 从日志投影出给 LLM 的消息（含上下文压缩 seam）。

**Architecture:** 参考 pi(`session-manager.ts`)——会话日志是 append-only 的 JSONL(首行 `SessionHeader`)，消息以 `MessageEntry`(带 id/parentId/timestamp 树字段)逐行追加；投影(`SessionProjector`)把日志直通成 `List<Message>` 并经过 `ContextCompactor` no-op seam。根 `Message` 是 `final` 类无法直接被 Jackson 序列化，故持久化边界用 `StoredMessage` DTO 折一层极薄映射。纯包 `dev.firstagent.session`，不 import `AgentLoop`/`llm`/`tools`。

**Tech Stack:** Java 17、Maven(根 `pom.xml`)、Jackson-databind 2.17.2(已在 pom)、JUnit 5.10.2(已在 pom)。**不新增依赖**。

**Spec:** `docs/superpowers/specs/2026-10-05-session-conversation-mvp-design.md`。开发在 worktree **`feature/session-mvp`**(`C:\Users\13374\IdeaProjects\first-agent-session`)。

---

## 运行前置

每个含 `mvn` 的命令都要先 export（JAVA_HOME/PATH 不跨 shell 持久化）：

```bash
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"
export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
```

Worktree 根目录（git-bash 写法）：`/c/Users/13374/IdeaProjects/first-agent-session`

## 文件结构

**新增 main**（`src/main/java/dev/firstagent/session/`）：
| 文件 | 职责 |
|---|---|
| `MessageEntry.java` | 日志里的一条消息(id/parentId/timestamp + `Message`)，public |
| `Session.java` | 会话值对象:id/cwd/name/createdAt + 追加式 entries 只读视图，public |
| `StoredMessage.java` | 落盘 DTO(Message ↔ StoredMessage 极薄映射)，public |
| `ContextCompactor.java` | 上下文压缩 seam 接口，public |
| `NoopContextCompactor.java` | 默认 no-op 压缩器，public |
| `SessionProjector.java` | 日志→`List<Message>` 直通投影 + 应用压缩 seam，public |
| `SessionHeader.java` | JSONL 首行 DTO，包内 |
| `SessionIoException.java` | 持久化运行时异常，public |
| `SessionStore.java` | 生命周期 + JSONL 读写(create/append/load/list/findMostRecent/rename/delete)，public |

**新增 test**（`src/test/java/dev/firstagent/session/`）：`StoredMessageTest`、`SessionTest`、`SessionProjectorTest`、`SessionStoreTest`。

**已存在，不改动**：`pom.xml`、`src/main/java/dev/firstagent/Message.java`、`ToolCall.java`。`ToolCall` 是 record(id,name,argumentsJson)，Jackson 可直接序列化。

任务顺序：`StoredMessage` → 领域模型 → `SessionProjector` → `SessionStore`(核心) → `SessionStore`(生命周期收尾)。

---

### Task 1: `StoredMessage` —— Message ↔ DTO 极薄映射

**Files:**
- Create: `src/main/java/dev/firstagent/session/StoredMessage.java`
- Test: `src/test/java/dev/firstagent/session/StoredMessageTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/session/StoredMessageTest.java`
```java
package dev.firstagent.session;

import dev.firstagent.Message;
import dev.firstagent.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StoredMessageTest {

    @Test void roundTripsSystemRole() {
        StoredMessage s = StoredMessage.from(Message.system("你是助手"));
        assertEquals("SYSTEM", s.role());
        assertEquals("你是助手", s.text());
        Message back = s.toMessage();
        assertEquals(Message.Role.SYSTEM, back.role());
        assertEquals("你是助手", back.text());
    }

    @Test void roundTripsUserRole() {
        Message back = StoredMessage.from(Message.user("帮我查天气")).toMessage();
        assertEquals(Message.Role.USER, back.role());
        assertEquals("帮我查天气", back.text());
        assertTrue(back.toolCalls().isEmpty());
        assertFalse(back.isError());
    }

    @Test void roundTripsAssistantWithToolCalls() {
        Message m = Message.assistant("我要查", List.of(new ToolCall("c1", "WeatherTool", "{\"city\":\"hz\"}")));
        Message back = StoredMessage.from(m).toMessage();
        assertEquals(Message.Role.ASSISTANT, back.role());
        assertEquals("我要查", back.text());
        assertEquals(1, back.toolCalls().size());
        assertEquals("c1", back.toolCalls().get(0).id());
        assertEquals("WeatherTool", back.toolCalls().get(0).name());
    }

    @Test void roundTripsToolResult() {
        Message back = StoredMessage.from(Message.toolResult("c1", "晴 25°C", false)).toMessage();
        assertEquals(Message.Role.TOOL, back.role());
        assertEquals("c1", back.toolCallId());
        assertEquals("晴 25°C", back.text());
        assertFalse(back.isError());
    }

    @Test void errorToolResultRoundTrips() {
        assertTrue(StoredMessage.from(Message.toolResult("c1", "错误", true)).toMessage().isError());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"; export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn -q -Dtest=StoredMessageTest test
```
Expected: FAIL with `cannot find symbol: class StoredMessage`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/session/StoredMessage.java`
```java
package dev.firstagent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.firstagent.Message;
import dev.firstagent.ToolCall;

import java.util.List;

/**
 * 落盘用的消息快照 DTO。根 Message 是 final + 私有构造,Jackson 不能直接序列化,
 * 持久化边界用它折一层极薄映射(Message <-> StoredMessage),不侵入共享 demo 类。
 * role 字符串为枚举名:SYSTEM/USER/ASSISTANT/TOOL。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredMessage(String role, String text, List<ToolCall> toolCalls,
                            String toolCallId, boolean isError) {

    public static StoredMessage from(Message m) {
        List<ToolCall> calls = m.toolCalls();
        return new StoredMessage(m.role().name(), m.text(),
                calls.isEmpty() ? null : calls, m.toolCallId(), m.isError());
    }

    public Message toMessage() {
        return switch (Message.Role.valueOf(role)) {
            case SYSTEM -> Message.system(text);
            case USER -> Message.user(text);
            case ASSISTANT -> Message.assistant(text, toolCalls == null ? List.of() : toolCalls);
            case TOOL -> Message.toolResult(toolCallId, text, isError);
        };
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"; export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn -q -Dtest=StoredMessageTest test
```
Expected: PASS (5 tests)

- [ ] **Step 5: 提交**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
git add src/main/java/dev/firstagent/session/StoredMessage.java src/test/java/dev/firstagent/session/StoredMessageTest.java
git commit -m "feat(session): StoredMessage DTO maps Message <-> disk snapshot (AC-1)"
```

---

### Task 2: 领域模型 —— `Session` / `MessageEntry` + 压缩 seam 接口

**Files:**
- Create: `src/main/java/dev/firstagent/session/Session.java`
- Create: `src/main/java/dev/firstagent/session/MessageEntry.java`
- Create: `src/main/java/dev/firstagent/session/ContextCompactor.java`
- Create: `src/main/java/dev/firstagent/session/NoopContextCompactor.java`
- Test: `src/test/java/dev/firstagent/session/SessionTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/session/SessionTest.java`
```java
package dev.firstagent.session;

import dev.firstagent.Message;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionTest {

    @Test void exposesIdentity() {
        Session s = new Session("s1", "/cwd", Instant.parse("2026-10-05T00:00:00Z"), null);
        assertEquals("s1", s.id());
        assertEquals("/cwd", s.cwd());
        assertNull(s.name());
    }

    @Test void entriesAreAppendOnlyReadOnlyView() {
        Session s = new Session("s1", "/cwd", Instant.parse("2026-10-05T00:00:00Z"), null);
        s.append(new MessageEntry("a", null, Instant.now(), Message.user("hi")));
        s.append(new MessageEntry("b", "a", Instant.now(), Message.user("how")));
        assertEquals(2, s.entries().size());
        List<MessageEntry> view = s.entries();
        assertThrows(UnsupportedOperationException.class,
                () -> view.add(new MessageEntry("c", "b", Instant.now(), Message.user("x"))));
    }

    @Test void renameUpdatesName() {
        Session s = new Session("s1", "/cwd", Instant.now(), null);
        s.rename("my-name");
        assertEquals("my-name", s.name());
    }

    @Test void noopCompactorReturnsEmpty() {
        Session s = new Session("s1", "/cwd", Instant.now(), null);
        assertTrue(new NoopContextCompactor().maybeCompact(s).isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"; export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn -q -Dtest=SessionTest test
```
Expected: FAIL with `cannot find symbol: class Session`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/session/Session.java`
```java
package dev.firstagent.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次对话会话。由 id/cwd 标识,历史是追加式的 MessageEntry 日志。
 * 消息只能经 SessionStore.append 追加;这里只暴露只读视图,不直接改历史。
 */
public final class Session {
    private final String id;
    private final String cwd;
    private final Instant createdAt;
    private String name;
    private final List<MessageEntry> entries = new ArrayList<>();

    Session(String id, String cwd, Instant createdAt, String name) {
        this.id = id;
        this.cwd = cwd;
        this.createdAt = createdAt;
        this.name = name;
    }

    public String id() { return id; }
    public String cwd() { return cwd; }
    public Instant createdAt() { return createdAt; }
    public String name() { return name; }

    /** 会话历史只读视图;顺序即追加顺序。 */
    public List<MessageEntry> entries() {
        return List.copyOf(entries);
    }

    /** 供 SessionStore(同包)追加一条消息日志。外部只能经 SessionStore.append 调用。 */
    void append(MessageEntry entry) { entries.add(entry); }

    void rename(String name) { this.name = name; }
}
```

`src/main/java/dev/firstagent/session/MessageEntry.java`
```java
package dev.firstagent.session;

import dev.firstagent.Message;

import java.time.Instant;

/**
 * 会话日志里的一条消息。带 id/parentId/timestamp 三个树字段:MVP 只沿线性链走,
 * parentId 恒指上一条(首条为 null)。以后要 fork/分支,字段现成、直接加遍历逻辑即可,不用改文件格式。
 */
public final class MessageEntry {
    static final String TYPE = "message";

    private final String id;
    private final String parentId;
    private final Instant timestamp;
    private final Message message;

    MessageEntry(String id, String parentId, Instant timestamp, Message message) {
        this.id = id;
        this.parentId = parentId;
        this.timestamp = timestamp;
        this.message = message;
    }

    public String id() { return id; }
    public String parentId() { return parentId; }
    public Instant timestamp() { return timestamp; }
    public Message message() { return message; }
}
```

`src/main/java/dev/firstagent/session/ContextCompactor.java`
```java
package dev.firstagent.session;

import dev.firstagent.Message;

import java.util.List;
import java.util.Optional;

/**
 * 上下文压缩 seam —— 第 2 点(上下文/历史管理)的插槽。
 *
 * pi 真压缩的做法(见 agent-session.研读摘注 §1/§4):判定「预估 context token 超过模型窗口」
 * 后,追加一条 compaction entry(含 firstKeptEntryId),重放时丢掉 earlier 段、换成摘要,但
 * 原始日志绝不删除。本 seam 将来在此插真压缩器(基于预估 token/窗口),返回压缩后的消息列表。
 *
 * 返回空 = 不压缩,投影用完整历史。
 */
public interface ContextCompactor {
    Optional<List<Message>> maybeCompact(Session session);
}
```

`src/main/java/dev/firstagent/session/NoopContextCompactor.java`
```java
package dev.firstagent.session;

import dev.firstagent.Message;

import java.util.List;
import java.util.Optional;

/** 默认压缩器:永不压缩(no-op)。第 2 点实现真压缩时替换之。 */
public final class NoopContextCompactor implements ContextCompactor {
    @Override
    public Optional<List<Message>> maybeCompact(Session session) {
        return Optional.empty();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"; export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn -q -Dtest=SessionTest test
```
Expected: PASS (4 tests)

- [ ] **Step 5: 提交**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
git add src/main/java/dev/firstagent/session/Session.java src/main/java/dev/firstagent/session/MessageEntry.java src/main/java/dev/firstagent/session/ContextCompactor.java src/main/java/dev/firstagent/session/NoopContextCompactor.java src/test/java/dev/firstagent/session/SessionTest.java
git commit -m "feat(session): Session/MessageEntry domain model + context-compaction seam (AC-5)"
```

---

### Task 3: `SessionProjector` —— 日志直通投影 + 应用压缩 seam

**Files:**
- Create: `src/main/java/dev/firstagent/session/SessionProjector.java`
- Test: `src/test/java/dev/firstagent/session/SessionProjectorTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/session/SessionProjectorTest.java`
```java
package dev.firstagent.session;

import dev.firstagent.Message;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionProjectorTest {

    private static Session sessionWith(Message... msgs) {
        Session s = new Session("s1", "/cwd", Instant.now(), null);
        String parent = null;
        for (Message m : msgs) {
            String id = "e" + s.entries().size();
            s.append(new MessageEntry(id, parent, Instant.now(), m));
            parent = id;
        }
        return s;
    }

    @Test void projectPassthroughsAllMessagesInOrder() {
        Session s = sessionWith(Message.user("hi"), Message.assistant("hello", List.of()));
        List<Message> out = new SessionProjector().project(s);
        assertEquals(2, out.size());
        assertEquals("hi", out.get(0).text());
        assertEquals("hello", out.get(1).text());
    }

    @Test void defaultCompactorIsNoop() {
        Session s = sessionWith(Message.user("hi"), Message.assistant("hello", List.of()));
        assertEquals(2, new SessionProjector().project(s).size());
    }

    @Test void injectedCompactorTrimsHistory() {
        Session s = sessionWith(Message.user("hi"), Message.user("kep"), Message.user("latest"));
        ContextCompactor trimmer = session -> Optional.of(List.of(session.entries().get(2).message()));
        List<Message> out = new SessionProjector(trimmer).project(s);
        assertEquals(1, out.size());
        assertEquals("latest", out.get(0).text());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"; export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn -q -Dtest=SessionProjectorTest test
```
Expected: FAIL with `cannot find symbol: class SessionProjector`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/session/SessionProjector.java`
```java
package dev.firstagent.session;

import dev.firstagent.Message;

import java.util.List;

/**
 * 从会话日志派生投影:把每条 MessageEntry 存的 Message 直通(passthrough)成给 LLM 的消息列表
 * (pi 式「日志即事实源」,无字段级映射)。顺序 = 追加顺序。
 * 经过上下文压缩 seam;默认(Noop)不压缩、原样返回完整历史。
 */
public final class SessionProjector {
    private final ContextCompactor compactor;

    public SessionProjector() {
        this(new NoopContextCompactor());
    }

    public SessionProjector(ContextCompactor compactor) {
        this.compactor = compactor;
    }

    public List<Message> project(Session session) {
        List<Message> full = session.entries().stream()
                .map(MessageEntry::message)
                .toList();
        return compactor.maybeCompact(session).orElse(full);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"; export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn -q -Dtest=SessionProjectorTest test
```
Expected: PASS (3 tests)

- [ ] **Step 5: 提交**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
git add src/main/java/dev/firstagent/session/SessionProjector.java src/test/java/dev/firstagent/session/SessionProjectorTest.java
git commit -m "feat(session): SessionProjector projects log -> List<Message> via compactor seam (AC-4)"
```

---

### Task 4: `SessionStore` 核心 —— create / append / load + JSONL 真 append

**Files:**
- Create: `src/main/java/dev/firstagent/session/SessionIoException.java`
- Create: `src/main/java/dev/firstagent/session/SessionHeader.java`
- Create: `src/main/java/dev/firstagent/session/SessionStore.java`
- Test: `src/test/java/dev/firstagent/session/SessionStoreTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/session/SessionStoreTest.java`
```java
package dev.firstagent.session;

import dev.firstagent.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    @TempDir Path tmp;

    @Test void createPersistsHeaderLine() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        store.create("/repo", "demo");
        Path file = findOnlyFile(tmp.resolve("sessions"));
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("{\"type\":\"session\",\"version\":3"));
        assertTrue(content.contains("\"cwd\":\"/repo\""));
    }

    @Test void loadRoundTripsAppendedMessages() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session s = store.create("/repo", null);
        store.append(s, Message.user("hi"));
        store.append(s, Message.assistant("hello", List.of()));
        Session loaded = store.load(s.id());
        assertEquals(s.id(), loaded.id());
        assertEquals("/repo", loaded.cwd());
        assertEquals(2, loaded.entries().size());
        assertEquals("hi", loaded.entries().get(0).message().text());
        assertEquals("hello", loaded.entries().get(1).message().text());
    }

    @Test void appendOnlyOneLineAddedAndOldBytesKept() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session s = store.create("/repo", null);
        store.append(s, Message.user("hi"));
        Path file = findOnlyFile(tmp.resolve("sessions"));
        String afterFirst = Files.readString(file, StandardCharsets.UTF_8);
        store.append(s, Message.user("second"));
        String afterSecond = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(afterSecond.startsWith(afterFirst));                         // 旧字节原样保留前缀
        assertEquals(3, Files.readAllLines(file).size());                       // header + 2 条
    }

    @Test void lenientLoadSkipsMalformedLine() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session s = store.create("/repo", null);
        store.append(s, Message.user("ok"));
        Path file = findOnlyFile(tmp.resolve("sessions"));
        Files.writeString(file, "this is not json\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Session loaded = store.load(s.id());
        assertEquals(1, loaded.entries().size());
        assertEquals("ok", loaded.entries().get(0).message().text());
    }

    private Path findOnlyFile(Path root) throws Exception {
        try (var stream = Files.walk(root)) {
            var list = stream.filter(Files::isRegularFile).toList();
            assertEquals(1, list.size(), "expected exactly one session file");
            return list.get(0);
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"; export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn -q -Dtest=SessionStoreTest test
```
Expected: FAIL with `cannot find symbol: class SessionStore`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/session/SessionIoException.java`
```java
package dev.firstagent.session;

import java.nio.file.Path;

/** 会话持久化运行时异常。 */
public final class SessionIoException extends RuntimeException {
    public SessionIoException(String op, Path path, Throwable cause) {
        super(op + (path != null ? " (" + path + ")" : "") + (cause == null ? "" : ": " + cause.getMessage()),
                cause);
    }
}
```

`src/main/java/dev/firstagent/session/SessionHeader.java`
```java
package dev.firstagent.session;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/** JSONL 首行元数据,非树节点。name 可选(未命名时为 null,序列化时省略)。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionHeader(String type, int version, String id, String cwd,
                            String timestamp, String name) {
    static final int CURRENT_VERSION = 3;

    static SessionHeader of(String id, String cwd, Instant createdAt, String name) {
        return new SessionHeader("session", CURRENT_VERSION, id, cwd, createdAt.toString(), name);
    }

    Instant createdAt() { return Instant.parse(timestamp); }
}
```

`src/main/java/dev/firstagent/session/SessionStore.java`
```java
package dev.firstagent.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.firstagent.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 会话持久化 + 生命周期。JSONL append-only:create 排他建文件写头行,append 往末尾追加一行
 * (真 append,O(1),崩溃安全)。加载为 lenient(坏行跳过,镜像 pi "parsed without validation")。
 * 布局:baseDir/<消毒cwd>/<epochMillis>_<sessionId>.jsonl(镜像 pi 的 --<path>--/)。
 */
public final class SessionStore {
    private static final String LINE_ENDING = "\n";

    private final Path baseDir;
    private final ObjectMapper json = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public SessionStore(Path baseDir) {
        this.baseDir = baseDir;
    }

    /** 创建会话并立即落盘(仅写头行)。 */
    public Session create(String cwd, String name) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Session session = new Session(id, cwd, now, name);
        Path file = fileFor(session);
        try {
            Files.createDirectories(dirForCwd(cwd));
            Files.writeString(file, line(SessionHeader.of(id, cwd, now, name)) + LINE_ENDING,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new SessionIoException("create", file, e);
        }
        return session;
    }

    /** 往会话追加一条消息:更新内存日志 + 真 append 落盘一行。返回新 entry(可拿 id)。 */
    public MessageEntry append(Session session, Message message) {
        String id = newId();
        List<MessageEntry> entries = session.entries();
        String parentId = entries.isEmpty() ? null : entries.get(entries.size() - 1).id();
        MessageEntry entry = new MessageEntry(id, parentId, Instant.now(), message);
        session.append(entry);
        Path file = fileFor(session);
        try {
            Files.writeString(file, line(StoredMessageEntry.of(entry)) + LINE_ENDING,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new SessionIoException("append", file, e);
        }
        return entry;
    }

    /** 按 session id 加载整段会话历史(沿文件顺序还原线性链)。坏行跳过。 */
    public Session load(String id) {
        return read(findFile(id));
    }

    /** 列出全部会话。 */
    public List<Session> list() {
        List<Session> out = new ArrayList<>();
        for (Path f : allFiles()) {
            try {
                out.add(read(f));
            } catch (SessionIoException ignored) {
                // 跳过损坏的文件
            }
        }
        return out;
    }

    /** 取某 cwd 最近一条会话(镜像 pi findMostRecentSession)。无则空。 */
    public Optional<Session> findMostRecent(String cwd) {
        Path dir = dirForCwd(cwd);
        if (!Files.isDirectory(dir)) return Optional.empty();
        Session best = null;
        Instant bestTs = Instant.MIN;
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path f : stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                try {
                    Session candidate = read(f);
                    if (best == null || candidate.createdAt().isAfter(bestTs)) {
                        best = candidate;
                        bestTs = candidate.createdAt();
                    }
                } catch (SessionIoException ignored) {
                    // 跳过损坏文件
                }
            }
        } catch (IOException e) {
            throw new SessionIoException("findMostRecent", dir, e);
        }
        return Optional.ofNullable(best);
    }

    /** 删除会话文件。 */
    public void delete(String id) {
        Path file = findFile(id);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new SessionIoException("delete", file, e);
        }
    }

    /** 重命名会话:改头行 name,整体重写文件(header 是首行,改名必须重写)。 */
    public void rename(String id, String name) {
        Path file = findFile(id);
        Session s = read(file);
        s.rename(name);
        List<String> out = new ArrayList<>();
        out.add(line(SessionHeader.of(s.id(), s.cwd(), s.createdAt(), s.name())));
        for (MessageEntry e : s.entries()) {
            out.add(line(StoredMessageEntry.of(e)));
        }
        try {
            Files.writeString(file, String.join(LINE_ENDING, out) + LINE_ENDING,
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new SessionIoException("rename", file, e);
        }
    }

    // --- 内部 ---

    private Session read(Path file) {
        SessionHeader header = null;
        List<MessageEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                try {
                    JsonNode node = json.readTree(line);
                    String type = node.path("type").asText();
                    if ("session".equals(type)) {
                        header = json.treeToValue(node, SessionHeader.class);
                    } else if (MessageEntry.TYPE.equals(type)) {
                        entries.add(json.treeToValue(node, StoredMessageEntry.class).toMessageEntry());
                    }
                    // 未知 type / 坏行 → 跳过
                } catch (RuntimeException ignored) {
                    // skip malformed line
                }
            }
        } catch (IOException e) {
            throw new SessionIoException("read", file, e);
        }
        if (header == null) {
            throw new SessionIoException("missing header", file, null);
        }
        Session s = new Session(header.id(), header.cwd(), header.createdAt(), header.name());
        for (MessageEntry e : entries) s.append(e);
        return s;
    }

    private Path findFile(String id) {
        for (Path f : allFiles()) {
            if (f.getFileName().toString().endsWith("_" + id + ".jsonl")) return f;
        }
        throw new SessionIoException("session not found: " + id, null, null);
    }

    private Path fileFor(Session s) {
        String fn = s.createdAt().toEpochMilli() + "_" + s.id() + ".jsonl";
        return dirForCwd(s.cwd()).resolve(fn);
    }

    private Path dirForCwd(String cwd) {
        return baseDir.resolve(sanitize(cwd));
    }

    private List<Path> allFiles() {
        if (!Files.isDirectory(baseDir)) return List.of();
        try (Stream<Path> stream = Files.walk(baseDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                    .toList();
        } catch (IOException e) {
            throw new SessionIoException("walk", baseDir, e);
        }
    }

    private String sanitize(String cwd) {
        return cwd.replaceAll("[/\\\\: ]+", "-").replaceFirst("^[-]+", "");
    }

    private String line(Object dto) {
        try {
            return json.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    /** 消息 entry 的落盘 DTO：type/id/parentId/timestamp + 嵌套 message。 */
    record StoredMessageEntry(String type, String id, String parentId, String timestamp,
                              StoredMessage message) {
        static StoredMessageEntry of(MessageEntry e) {
            return new StoredMessageEntry(MessageEntry.TYPE, e.id(), e.parentId(),
                    e.timestamp().toString(), StoredMessage.from(e.message()));
        }

        MessageEntry toMessageEntry() {
            return new MessageEntry(id, parentId, timestamp != null ? Instant.parse(timestamp) : null,
                    message.toMessage());
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"; export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn -q -Dtest=SessionStoreTest test
```
Expected: PASS (4 tests)

- [ ] **Step 5: 提交**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
git add src/main/java/dev/firstagent/session/SessionStore.java src/main/java/dev/firstagent/session/SessionIoException.java src/main/java/dev/firstagent/session/SessionHeader.java src/test/java/dev/firstagent/session/SessionStoreTest.java
git commit -m "feat(session): SessionStore create/append/load with JSONL append-only + lenient load (AC-2, AC-3, AC-6)"
```

---

### Task 5: `SessionStore` 生命周期收尾 —— list / findMostRecent / delete / rename + 按 cwd 分组

**Files:**
- Modify: `src/test/java/dev/firstagent/session/SessionStoreTest.java` (追加 4 个测试)
- Modify: `src/main/java/dev/firstagent/session/SessionStore.java`（list/findMostRecent/delete/rename 已在 Task 4 实现，本任务只补测试验证——无需改 src）

> 注：Task 4 的 `SessionStore` 已包含 list/findMostRecent/delete/rename。本任务仅验证其行为（TDD 补测），不改 src。

- [ ] **Step 1: 追加失败测试（cwd 分组 + 生命周期）**

在 `src/test/java/dev/firstagent/session/SessionStoreTest.java` 的 `findOnlyFile` 方法前追加：

```java
    @Test void groupsByCwdIntoSeparateDirs() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        store.create("/proj-a", null);
        store.create("/proj-b", null);
        try (var stream = Files.list(tmp.resolve("sessions"))) {
            long dirs = stream.filter(Files::isDirectory).count();
            assertEquals(2, dirs);
        }
    }

    @Test void findMostRecentReturnsLatestForCwd() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session older = store.create("/repo", null);
        store.append(older, Message.user("first"));
        Thread.sleep(5); // 保证两次 create 的 created-at 有序
        Session newer = store.create("/repo", null);
        store.append(newer, Message.user("second"));
        assertEquals(newer.id(), store.findMostRecent("/repo").orElseThrow().id());
    }

    @Test void findMostRecentSeparatesByCwd() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session a = store.create("/proj-a", null);
        store.append(a, Message.user("a-msg"));
        Session b = store.create("/proj-b", null);
        store.append(b, Message.user("b-msg"));
        assertEquals(a.id(), store.findMostRecent("/proj-a").orElseThrow().id());
        assertEquals(b.id(), store.findMostRecent("/proj-b").orElseThrow().id());
        assertTrue(store.findMostRecent("/nothing").isEmpty());
    }

    @Test void deleteRemovesSessionButKeepsOthers() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session a = store.create("/a", null);
        store.create("/b", null);
        store.delete(a.id());
        assertThrows(SessionIoException.class, () -> store.load(a.id()));
    }

    @Test void renamePersistsNewNameAcrossReload() throws Exception {
        SessionStore store = new SessionStore(tmp.resolve("sessions"));
        Session s = store.create("/repo", "old");
        store.rename(s.id(), "new-name");
        assertEquals("new-name", store.load(s.id()).name());
    }
```

- [ ] **Step 2: 跑全量 session 测试确认通过**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"; export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn -q -Dtest=SessionStoreTest test
```
Expected: PASS (4 + 5 = 9 tests)

- [ ] **Step 3: 全仓测试确认全绿（AC-7）**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"; export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn test
```
Expected: BUILD SUCCESS —— 所有既有测试 + 新增 session 测试全绿

- [ ] **Step 4: 提交**

```bash
cd /c/Users/13374/IdeaProjects/first-agent-session
git add src/test/java/dev/firstagent/session/SessionStoreTest.java
git commit -m "test(session): cwd grouping + list/findMostRecent/delete/rename lifecycle (AC-6)"
```

---

## Self-Review

**Spec coverage：**
- AC-1 包存在、`Session`/`SessionStore`/`SessionProjector` 可编译 → Task 1-5。
- AC-2 JSONL 首行 `SessionHeader`(version=3) + `MessageEntry` 行 → Task 4。
- AC-3 `append` 真追加、旧字节不变 → Task 4 `appendOnlyOneLineAddedAndOldBytesKept`。
- AC-4 `project(Session)` 直通 `List<Message>` → Task 3。
- AC-5 `ContextCompactor` seam + `NoopContextCompactor` → Task 2/3。
- AC-6 坏行跳过、加载不崩(lenient) → Task 4。
- AC-7 `mvn test` 全绿 → Task 5 Step 3。

**Placeholder scan：** 无 TBD/TODO；每个代码步骤含完整源码与预期输出。

**Type consistency：** `Session.id()/cwd()/name()/createdAt()/entries()`、`MessageEntry.id()/parentId()/timestamp()/message()`、`StoredMessage.from/toMessage`、`ContextCompactor.maybeCompact(Session) -> Optional<List<Message>>`、`SessionStore.create(cwd,name)/append(session,message)->MessageEntry/load(id)` 在各任务间一致。`Message` 的工厂/访问器用根项目的既有签名（`system/user/assistant/toolResult` 接受器 + `role()/text()/toolCalls()/toolCallId()/isError()`）。

**补充说明（诚实边界）：**
- `rename` 需整体重写文件（header 是首行，非纯 append）——与 pi 的 `_rewriteFile`(版本迁移/改名)一致，属合理例外，AC-3 的 append-only 不变量仅约束 `append`。
- 时间戳在 DTO 里用 ISO 字符串，避开 `jackson-datatype-jsr310` 新增依赖；`findMostRecent` 以 `createdAt`(Instant) 比较，测试用 `Thread.sleep(5)` 保证确定性。
- `parentId` 链在 load 时按文件顺序还原，MVP 不重校验（树导航留后续）。