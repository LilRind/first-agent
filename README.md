A simplified Coding Agent inspired by the implementation of Pi

## 工具模块（Pi 简化 MVP）

`dev.firstagent.tools` 下的真实编码工具（复用 `AgentTool` 契约，纯 JDK + Jackson）：

| 工具 | 说明 |
|---|---|
| `ReadTool` | 读文本文件，`offset`/`limit` + 截断 |
| `WriteTool` | 写文件，自动建父目录、覆盖 |
| `GrepTool` | 纯 Java 正则搜索，不依赖 ripgrep，支持 glob/ignoreCase/context/limit |
| `BashTool` | `ProcessBuilder` 跑命令，Windows `cmd /c` / POSIX `sh -c`，超时 + 截断 |

每个工具额外实现 `SchematizedTool.inputSchema()`（schema 自描述，Pi 风格），
`ToolsDemo` 打印模型可见 `ToolSpec` 清单并派发预置调用演示端到端执行。

运行 demo：
```bash
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"
export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
"$JAVA_HOME/bin/java" -cp "target/classes;$(cat target/cp.txt)" dev.firstagent.tools.ToolsDemo
```
