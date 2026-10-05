# Spec — 会话管理 MVP（线性 append-only 日志 + 投影 + JSONL 持久化）

> 参考蓝本：pi（`packages/coding-agent/src/core/session-manager.ts` + `docs/session-format.md` + `agent-session.研读摘注.md`）。
> 目标：做 **第 1 点（会话生命周期 + 持久化）**，把 **第 2 点（上下文/历史管理）** 设计成一个薄接口（上下文压缩 seam）挂上去。
> 后续开发流程：superpowers（writing-plans → test-driven-development）。

## 1. 范围与目标

这个分支（`feature/session-mvp`）只做会话管理的 MVP：

- **做**：会话生命周期（create / load / append / list / findMostRecent / delete / rename）+ 按 cwd 分组 + JSONL 落盘 + 从日志投影出给 LLM 的消息 + 上下文压缩 seam（MVP 为 no-op）。
- **不做**：真上下文压缩、树分支导航（fork/clone/branch summary）、usage/compaction/context_edit/branch_summary 等 entry 类型、模型路由、token 精确计数、与 RunLoop 的集成（留到将来合并）。

### 与 pi 的对齐 / 简化（诚实边界）

| 项 | pi 原生（已核实源码） | 本 MVP |
|---|---|---|
| 存储格式 | JSONL，首行 `SessionHeader`(version/id/cwd/timestamp) | 同构，`version=3` |
| 追加 | `_appendEntry` → `_persist`：首个排他建文件写头，之后 `appendFileSync` 加一行（真 append，O(1)） | 同构：create 排他写头 + append 追加一行 |
| 日志即事实源 | `buildSessionProjection()` 从 entry 日志投影出消息，非可变数组 | 同构：`SessionProjector` 从 `List<Entry>` 派生 `List<Message>` |
| 结构 | **树**（id/parentId，in-place 分支） | **简化为单链**：每个 entry 带 `id`/`parentId`/`timestamp`，但 MVP 只沿线性链走，`parentId` 恒指上一条；文件格式与 pi v3 兼容，将来加树不用改格式 |
| entry 类型 | message/model_change/thinking_level_change/usage/compaction/context_edit/branch_summary/custom | 只做 `SessionHeader` + `MessageEntry` |
| 按 cwd 分组 | `sessions/--<path>--/<ts>_<id>.jsonl` | 同构：`baseDir/<消毒cwd>/<ts>_<id>.jsonl` |
| continue | `findMostRecentSession(sessionDir, cwd)` 取最近一条 | 同构：`findMostRecent(cwd)` |
| 上下文管理 | 真压缩：`shouldCompact(预估token vs 模型窗口)` → 追 compaction entry(firstKeptEntryId)，日志不删 | 只留 **上下文压缩 seam**（no-op），注释点名这是 pi 真压缩的插槽 |

## 2. 位置 / 结构

- 开发在根项目 `first-agent`（**不是** `code/travel-agent`），位于独立 worktree `feature/session-mvp`。
- 新包：`src/main/java/dev/firstagent/session/`（包 `dev.firstagent.session`）。
- 测试：`src/test/java/dev/firstagent/session/`。
- 纯包，**不 import** `dev.firstagent.AgentLoop` / `llm` / `tools`，与并发重构零接触。
- 技术栈：Java 17、Maven（根 `pom.xml`）、Jackson-databind 2.17.2、JUnit 5.10.2。不新增依赖。

## 3. 组件（4 个，职责单一）

### 3.1 `Session`（值对象）
- 字段：`id`（UUID）、`cwd`、`name?`、`createdAt`、`List<Entry>`（内存中的追加日志）。
- active branch = 整条线性链（MVP）。
- 不可变/受控：消息只能经 `SessionStore.append` 追加，不能直接改历史。

### 3.2 Entry 模型（pi v3 兼容 + 简化）
- `SessionHeader`：`type="session"`、`version=3`、`id`、`cwd`、`timestamp`。文件首行，非树节点。
- `MessageEntry`：`type="message"`、`id`、`parentId`（MVP 恒指上一条）、`timestamp` + 一条消息。
  - **内存**：持有根项目的 `dev.firstagent.Message`。
  - **落盘**：折成 `StoredMessage` record DTO（`role`/`text`/`toolCalls`/`toolCallId`/`isError`）写 JSONL；**读盘**反折回 `Message`。
  - 为何 DTO：根 `Message` 是 `final` + 私有构造，Jackson 不能直接序列化；用 DTO 不侵入共享 demo 类，持久化边界只多一层极薄映射。
- `type` / `version` 字段保留，为将来加 entry 类型与树留扩展位。`model_change`/`usage`/`compaction` 等**暂不做**。

### 3.3 `SessionStore`（持久化 + 生命周期）
- API：`create(Session)` / `load(id)` / `append(session, Message)` / `list()` / `findMostRecent(cwd)` / `delete(id)` / `rename(id, name)`。
- 存储布局：`baseDir`（可配，默认根下 `sessions/`）+ 按 cwd 消毒的子目录 + `<timestamp>_<id>.jsonl`（镜像 pi 的 `--<path>--/`）。
- 写盘：
  - create：排他建文件（`CREATE_NEW`/`wx` 语义），写入 `SessionHeader` 行。
  - append：`Files.newBufferedWriter` 追加一行（**真 append**，O(1)，崩溃安全）。
- 加载：读取整文件，逐行 parse；**lenient**——坏行跳过不崩（镜像 pi "parsed without validation"）。
- 并发：MVP 单会话单写者，不做锁；如需缓存活跃 session 用 `ConcurrentHashMap`。

### 3.4 `SessionProjector` + 上下文压缩 seam
- `project(Session) -> List<Message>`：沿线性链把每条 `MessageEntry` 存的 `Message` **直通返回**（pi 式 passthrough，无字段级映射；仅加 null 兜底）。
- **上下文压缩 seam**：接口 `interface ContextCompactor { Optional<List<Message>> maybeCompact(Session s); }` + 默认实现 `NoopContextCompactor`（返回空）。MVP 只接 seam、默认 no-op。
  - 注释必须点名：这是 pi 真上下文压缩 `shouldCompact(预估 token vs 模型窗口)` → 追加 compaction entry（含 `firstKeptEntryId`、日志不删）的插槽，属第 2 点，不在本分支实现。

## 4. 数据流

```
消息(用户/工具) ──> store.append(session, msg)  // 追加内存日志 + 落盘一行(真 append)
                       │
                       v
              projector.project(session)        // 沿单链直通派生 List<Message>
                       │
                       └── (经上下文压缩 seam, MVP 为 no-op)
                                    │
                                    v
                        (将来)交给 RunLoop/ChatClient
```

## 5. 错误处理 / 并发

- **lenient 加载**：JSONL 某行损坏/截断 → 跳过该行不崩。
- **append 崩溃安全**：真 append，中断只丢末尾不完整行，已写行不损坏。
- **并发**：单会话单写者，不做锁；活跃 session 缓存用 `ConcurrentHashMap`。

## 6. 测试（TDD，JUnit，temp dir）

1. **生命周期往返**：create/list/load/append/continue(findMostRecent)/delete/rename 各路径。
2. **append-only 不变量**：追加后文件**恰好多一行**、旧行字节**不变**。
3. **投影直通**：entry → `List<Message>` 顺序正确、内容一致。
4. **压缩 seam**：默认 no-op 返回空；可注入假策略验证 seam 存在。
5. **lenient 加载**：手动构造含坏行的 JSONL，加载跳过坏行不崩。
6. **按 cwd 分组 + findMostRecent**：多 cwd 各自独立目录；`findMostRecent(cwd)` 取该 cwd 最近一条。

## 7. 验收标准（AC）

- AC-1：`session/` 包存在，`Session`/`SessionStore`/`SessionProjector` 可编译。
- AC-2：JSONL 首行为 `SessionHeader`(version=3)，后续为 `MessageEntry` 行，格式与 pi v3 兼容。
- AC-3：`append` 为真追加——追加后文件恰好多一行、旧行字节不变。
- AC-4：`project(Session)` 返回该会话全部消息的直通 `List<Message>`（顺序=追加顺序）。
- AC-5：存在 `ContextCompactor` seam + `NoopContextCompactor` 默认实现，注释点名 pi 真压缩插槽。
- AC-6：坏行被跳过、加载不崩（lenient）。
- AC-7：`mvn test` 全绿（在 worktree `feature/session-mvp` 内）。
