# Design — Pi 工具模块 Java MVP（方案 2）

> 日期：2026-10-05
> 状态：已批准（brainstorming 定稿）
> 目标：在 `first-agent` 项目里，按 Pi（`IdeaProjects/Learning/pi` 的 `packages/coding-agent/src/core/tools/`）的工具模块，写一个**简化 Java MVP**：4 个真实编码工具 + 最小装配 + 自包含 demo。独立自包含，不碰在途的 RunLoop 重构，之后按最新代码合入。

## 背景与范围决策

**为什么是方案 2（非 1/3）：**
- 方案 1（只做框架层+演示工具）与现有 `EchoTool/FailTool` 重复，增量价值小。
- 方案 3（镜像 Pi 完整结构）的大头是 diff 引擎、流式输出累积、增量渲染器、ripgrep 依赖下载——这些是"算法/框架"难题，不是"工具模块"该学的，对 Demo 是负收益。
- 方案 2 恰好卡在"真实工具能操作文件系统/跑命令，端到端演示模型发调用→工具真执行"，又能控制在一次会话内写完。

**砍掉的 Pi 细节**：`output-accumulator`（流式累积）、`renderers/*`（增量渲染）、`edit.ts`+`edit-diff.ts`（diff 引擎）、`find.ts`/`ls.ts`/`powershell.ts`、ripgrep 依赖与 `tools-manager` 下载。Grep 改用**纯 Java 正则**（`Files.walk`+`Pattern`），跨平台、零依赖。

## 位置与构建

- 项目结构：`first-agent` 是仓库根 **Maven 工程**（`groupId=dev.firstagent`，Java 17，已有 jackson-databind + JUnit 依赖），代码在 `src/main/java/dev/firstagent/`。
- **不需要新增 pom.xml** —— 用现有根 pom，`mvn test` 即可跑。
- 新代码位置：`src/main/java/dev/firstagent/tools/`（扩展现有 EchoTool/FailTool 所在包）；测试在 `src/test/java/dev/firstagent/tools/`。
- 只**新增文件**，不编辑在途重构文件（`LoopStrategy`/`TurnDecision`/`AgentEvent` 等）。

## 工具契约复用

复用现有 `dev.firstagent.AgentTool`：
```java
interface AgentTool {
    String name();
    String description();
    String execute(String argumentsJson) throws ToolExecutionException;
    default boolean isConcurrencySafe() { return false; }
}
```
- 每个具体工具 `execute(String json)` 用 Jackson 解析参数 `Map`，纯 JDK + Jackson 实现行为。
- 因 live 接口无 `inputSchema()`（schema 注入按活项目"留二期"），每个工具**额外声明一个具体 `inputSchema()` 返回 `Map<String,Object>`**（不进接口），demo 据此合成模型可见的 `ToolSpec` 清单，保留 Pi 的"schema 自描述"味道而不破坏 live 契约。
- 派发用现有 `ToolRegistry`（register/get/contains）。

## 新增文件

| 文件 | 行为 / 关键点 |
|---|---|
| `tools/ReadTool.java` | args `{path, offset?, limit?}`；`Files.readAllLines`；行/字节截断；offset 越界给可行动提示 |
| `tools/WriteTool.java` | args `{path, content}`；`Files.createDirectories` 父目录 + `Files.writeString` |
| `tools/BashTool.java` | args `{command, timeoutMs?}`；`ProcessBuilder`（Windows `cmd /c` / POSIX `sh -c`）；捕获 stdout/stderr；超时默认 30s、上限 120s；截断 |
| `tools/GrepTool.java` | args `{pattern, path?, glob?, ignoreCase?, context?, limit?}`；纯 Java `Files.walk` + `Pattern`；返回 `file:line:text`；跳过二进制/超限 |
| `tools/PathUtil.java` | `resolveToCwd(path, cwd)`：`~` 展开、绝对路径直用、相对拼 cwd |
| `tools/Truncate.java` | 行数/字节截断，定值上限：`DEFAULT_MAX_LINES=2000`、`DEFAULT_MAX_BYTES=16*1024`；超限加 `[N more lines/bytes…]` 提示 |
| `tools/ToolSpec.java` | record `(name, description, inputSchema)`，模型可见规格 |
| `tools/ToolsDemo.java` | 自包含 `main`：注册 4 工具 → Jackson 打印 `ToolSpec` JSON → 构造一个预置 `ToolCall` 走 `ToolRegistry` 派发执行，演示"模型发调用→工具真执行" |
| `test/tools/ReadToolTest.java` | JUnit：读/offset/limit/截断/缺 path 报错 |
| `test/tools/WriteToolTest.java` | JUnit：写入/覆盖/自动建目录/缺 path |
| `test/tools/BashToolTest.java` | JUnit：跑简单命令/捕获输出/错误码 |
| `test/tools/GrepToolTest.java` | JUnit：匹配/glob/忽略大小写/无匹配 |

## Windows 注意

- BashTool 用 `ProcessBuilder` 调系统默认 shell：Windows 用 `cmd /c <command>`，POSIX 用 `/bin/sh -c <command>`。
- GrepTool 纯 Java 正则，不依赖 ripgrep，天然跨平台。

## 验收（AC）

- AC-1：4 个真实工具实现 `AgentTool`，`execute` 解析 JSON 参数、操作真实文件系统/进程。
- AC-2：每个工具提供 `inputSchema()`，demo 能合成模型可见 `ToolSpec` 清单并打印 JSON。
- AC-3：`ToolsDemo.main` 可跑通——注册 → 打印 specs → 派发一个预置调用拿到真实结果。
- AC-4：`mvn test` 全绿（新增 4 个测试 + 既有测试不回归）。
- AC-5：不编辑在途重构文件（`LoopStrategy`/`TurnDecision`/`AgentEvent`/`RunLoop`/`Session`），合并冲突≈0。

## worktree 与合并

- `git worktree add -b feature/tool-module-mvp <兄弟目录> feature/first-agent`（隔离未提交的 RunLoop 重构工作区）。
- 完成后 commit；合入时按最新代码走，文件不重叠（只新增 `tools/` 与测试）。
