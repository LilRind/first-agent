# Pi 工具模块 Java MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `feature/tool-module-mvp` worktree 里，按 Pi 工具模块（`IdeaProjects/Learning/pi/packages/coding-agent/src/core/tools/`）写一个简化 Java MVP：4 个真实编码工具（Read/Write/Grep/Bash）+ 最小装配（`SchematizedTool`/`ToolSpec`/`ToolRegistry`）+ 自包含 demo，全部 `mvn test` 绿、独立自包含、不碰在途 RunLoop 重构。

**Architecture:** 复用现有 `dev.firstagent.AgentTool` 契约（`execute(String json)→String`），每个具体工具用 Jackson 解析参数 Map、纯 JDK 操作真实文件系统/进程；额外声明 `inputSchema()`（不进 live 接口，留二期）以保留 Pi 的 schema 自描述；`ToolsDemo` 注册工具 → 打印 specs JSON → 派发预置 `ToolCall` 演示"模型发调用→工具真执行"。Grep 用纯 Java `Files.walk`+`Pattern`（不依赖 ripgrep），Bash 用 `ProcessBuilder` 走系统 shell（Windows `cmd /c`）。

**Tech Stack:** Java 17、Maven 3.9.16、jackson-databind 2.17.2、JUnit 5.10.2。零新增依赖。

**运行前置（每个含 mvn/java 的命令先 export，不跨 shell 持久化）：**
```bash
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"
export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
```

**已存在（本计划复用，不创建）：** `dev.firstagent.AgentTool`（`name/description/execute(String)/isConcurrencySafe`）、`ToolExecutionException`（String 与 String+Throwable 构造）、`ToolRegistry`（register/get/contains）、`ToolCall(id,name,argumentsJson)`、`tools.EchoTool/FailTool`。

**工作目录：** `C:/Users/13374/IdeaProjects/first-agent-tool-mvp`（worktree，分支 `feature/tool-module-mvp`）。所有文件路径相对此根。

---

### Task 1: 支撑类型 —— SchematizedTool / ToolSpec / PathUtil / Truncate

**Files:**
- Create: `src/main/java/dev/firstagent/tools/SchematizedTool.java`
- Create: `src/main/java/dev/firstagent/tools/ToolSpec.java`
- Create: `src/main/java/dev/firstagent/tools/PathUtil.java`
- Create: `src/main/java/dev/firstagent/tools/Truncate.java`
- Test: `src/test/java/dev/firstagent/tools/TruncateTest.java`
- Test: `src/test/java/dev/firstagent/tools/PathUtilTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/tools/TruncateTest.java`
```java
package dev.firstagent.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TruncateTest {

    @Test void keepsShortText() {
        assertEquals("a\nb", Truncate.truncate("a\nb", 2000, 16 * 1024));
    }

    @Test void truncatesByLines() {
        String out = Truncate.truncate("1\n2\n3\n4", 2, 16 * 1024);
        assertTrue(out.contains("1") && out.contains("2"), out);
        assertFalse(out.contains("3"), out);
        assertTrue(out.contains("more lines"), out);
    }

    @Test void truncatesByBytes() {
        String out = Truncate.truncate("abc", 2000, 2);
        assertTrue(out.contains("bytes"), out);
    }
}
```

`src/test/java/dev/firstagent/tools/PathUtilTest.java`
```java
package dev.firstagent.tools;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PathUtilTest {

    @Test void absolutePathIsUsedAsIs() {
        Path p = PathUtil.resolveToCwd("C:/x/y.txt", "/cwd");
        assertTrue(p.isAbsolute());
    }

    @Test void relativeResolvesToCwd() {
        Path p = PathUtil.resolveToCwd("x/y.txt", "C:/base");
        assertEquals(Path.of("C:/base/x/y.txt").normalize(), p);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=TruncateTest,PathUtilTest test`
Expected: FAIL —— `cannot find symbol: class Truncate` / `PathUtil`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/tools/SchematizedTool.java`
```java
package dev.firstagent.tools;

import java.util.Map;

import dev.firstagent.AgentTool;

/**
 * 带 schema 自描述的工具（Pi 风格）。AgentTool 接口未含 inputSchema（按活项目留二期），
 * 故 MVP 单独声明一个子接口，让每个真实工具都提供模型可见的参数 Schema。
 */
public interface SchematizedTool extends AgentTool {
    Map<String, Object> inputSchema();
}
```

`src/main/java/dev/firstagent/tools/ToolSpec.java`
```java
package dev.firstagent.tools;

import java.util.Map;

/** 一个工具的"模型可见规格"——名字、说明、JSON Schema 参数（Pi 的 schema 自描述）。 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
}
```

`src/main/java/dev/firstagent/tools/PathUtil.java`
```java
package dev.firstagent.tools;

import java.nio.file.Path;

/** 把模型给的路径解析成绝对路径：~ 展开 / 绝对直用 / 相对拼 cwd。 */
public final class PathUtil {
    private PathUtil() {}

    public static Path resolveToCwd(String path, String cwd) {
        String p = path == null ? "" : path.strip();
        if (p.startsWith("~")) {
            p = System.getProperty("user.home") + p.substring(1);
        }
        Path base = Path.of(p);
        if (base.isAbsolute()) return base.normalize();
        return Path.of(cwd).resolve(p).normalize();
    }
}
```

`src/main/java/dev/firstagent/tools/Truncate.java`
```java
package dev.firstagent.tools;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 行数/字节截断，防止工具输出无限膨胀。定值上限：2000 行、16KB。 */
public final class Truncate {
    public static final int DEFAULT_MAX_LINES = 2000;
    public static final int DEFAULT_MAX_BYTES = 16 * 1024;

    private Truncate() {}

    /** 先按行截断，再对保留内容按字节截断；超限追加可行动提示。 */
    public static String truncate(String text, int maxLines, int maxBytes) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        boolean truncatedLines = lines.length > maxLines;
        int kept = Math.min(lines.length, maxLines);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kept; i++) sb.append(lines[i]).append('\n');
        String result = sb.toString();

        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            byte[] head = Arrays.copyOf(bytes, maxBytes);
            String cut = new String(head, StandardCharsets.UTF_8);
            // 剪到最后一个换行，避免切断 UTF-8 多字节字符。
            int lastNl = cut.lastIndexOf('\n');
            String safe = lastNl > 0 ? cut.substring(0, lastNl + 1) : cut;
            return safe + "[... 截断于 " + maxBytes + " bytes]";
        }
        if (truncatedLines) {
            result += "[... 还有 " + (lines.length - maxLines) + " 行]";
        }
        return result.strip();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=TruncateTest,PathUtilTest test`
Expected: PASS（3 + 2 用例全绿）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/firstagent/tools/SchematizedTool.java src/main/java/dev/firstagent/tools/ToolSpec.java src/main/java/dev/firstagent/tools/PathUtil.java src/main/java/dev/firstagent/tools/Truncate.java src/test/java/dev/firstagent/tools/TruncateTest.java src/test/java/dev/firstagent/tools/PathUtilTest.java
git commit -m "feat(tools): add SchematizedTool, ToolSpec, PathUtil, Truncate support types"
```

---

### Task 2: WriteTool —— 写文件（自动建父目录）

**Files:**
- Create: `src/main/java/dev/firstagent/tools/WriteTool.java`
- Test: `src/test/java/dev/firstagent/tools/WriteToolTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/tools/WriteToolTest.java`
```java
package dev.firstagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class WriteToolTest {
    @TempDir Path tmp;

    @Test void writesAndOverwrites() throws Exception {
        WriteTool tool = new WriteTool(tmp.toString());
        String r = tool.execute("{\"path\":\"a.txt\",\"content\":\"hello\"}");
        assertEquals("hello", Files.readString(tmp.resolve("a.txt"), StandardCharsets.UTF_8));
        tool.execute("{\"path\":\"a.txt\",\"content\":\"world\"}");
        assertEquals("world", Files.readString(tmp.resolve("a.txt"), StandardCharsets.UTF_8));
    }

    @Test void createsParentDirs() throws Exception {
        WriteTool tool = new WriteTool(tmp.toString());
        tool.execute("{\"path\":\"sub/deep/b.txt\",\"content\":\"x\"}");
        assertTrue(Files.isRegularFile(tmp.resolve("sub/deep/b.txt")));
    }

    @Test void missingContentThrows() {
        WriteTool tool = new WriteTool(tmp.toString());
        assertThrows(dev.firstagent.ToolExecutionException.class,
            () -> tool.execute("{\"path\":\"a.txt\"}"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=WriteToolTest test`
Expected: FAIL —— `cannot find symbol: class WriteTool`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/tools/WriteTool.java`
```java
package dev.firstagent.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.firstagent.ToolExecutionException;

/** 写文件工具：args {path, content}。自动创建父目录；已存在则覆盖。 */
public class WriteTool implements SchematizedTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String cwd;

    public WriteTool(String cwd) { this.cwd = cwd; }

    @Override public String name() { return "write"; }
    @Override public String description() {
        return "Write content to a file, creating parent directories if needed. Args: path (required), content (required).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "要写入的文件路径"),
                "content", Map.of("type", "string", "description", "文件内容")),
            "required", List.of("path", "content"));
    }

    @Override
    public String execute(String argumentsJson) throws ToolExecutionException {
        JsonNode args = parse(argumentsJson);
        String path = args.path("path").asText(null);
        String content = args.path("content").asText(null);
        if (path == null || path.isBlank()) throw new ToolExecutionException("缺少参数 path");
        if (content == null) throw new ToolExecutionException("缺少参数 content");
        Path file = PathUtil.resolveToCwd(path, cwd);
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolExecutionException("写入文件失败: " + e.getMessage(), e);
        }
        return "成功写入 " + path + "（" + content.length() + " 字符）";
    }

    private static JsonNode parse(String json) {
        try { return MAPPER.readTree(json); }
        catch (Exception e) { throw new ToolExecutionException("参数不是合法 JSON: " + e.getMessage(), e); }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=WriteToolTest test`
Expected: PASS（3 用例全绿）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/firstagent/tools/WriteTool.java src/test/java/dev/firstagent/tools/WriteToolTest.java
git commit -m "feat(tools): add WriteTool (mkdirs + overwrite)"
```

---

### Task 3: ReadTool —— 读文件（offset/limit + 截断）

**Files:**
- Create: `src/main/java/dev/firstagent/tools/ReadTool.java`
- Test: `src/test/java/dev/firstagent/tools/ReadToolTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/tools/ReadToolTest.java`
```java
package dev.firstagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReadToolTest {
    @TempDir Path tmp;

    private Path writeFile(String content) throws Exception {
        Path f = tmp.resolve("sample.txt");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    @Test void readsWholeFile() throws Exception {
        writeFile("line1\nline2\nline3\n");
        ReadTool tool = new ReadTool(tmp.toString());
        String out = tool.execute("{\"path\":\"sample.txt\"}");
        assertTrue(out.contains("line1") && out.contains("line3"), out);
    }

    @Test void respectsLimit() throws Exception {
        writeFile(String.join("\n", List.of("a", "b", "c", "d", "e")));
        ReadTool tool = new ReadTool(tmp.toString());
        String out = tool.execute("{\"path\":\"sample.txt\",\"limit\":2}");
        assertTrue(out.contains("a") && out.contains("b"), out);
        assertFalse(out.contains("c"), out);
    }

    @Test void missingPathThrows() {
        ReadTool tool = new ReadTool(tmp.toString());
        assertThrows(dev.firstagent.ToolExecutionException.class, () -> tool.execute("{}"));
    }

    @Test void missingFileThrows() {
        ReadTool tool = new ReadTool(tmp.toString());
        assertThrows(dev.firstagent.ToolExecutionException.class,
            () -> tool.execute("{\"path\":\"nope.txt\"}"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=ReadToolTest test`
Expected: FAIL —— `cannot find symbol: class ReadTool`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/tools/ReadTool.java`
```java
package dev.firstagent.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.firstagent.ToolExecutionException;

/** 读文件工具：args {path, offset?, limit?}。按行读，支持偏移与行数限制，输出截断。 */
public class ReadTool implements SchematizedTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String cwd;

    public ReadTool(String cwd) { this.cwd = cwd; }

    @Override public String name() { return "read"; }
    @Override public String description() {
        return "Read the contents of a text file. Args: path (required), offset (1-indexed line to start), limit (max lines). "
            + "Output truncated to " + Truncate.DEFAULT_MAX_LINES + " lines.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "path", Map.of("type", "string", "description", "要读取的文件路径"),
                "offset", Map.of("type", "number", "description", "从第几行开始(1 起始)"),
                "limit", Map.of("type", "number", "description", "最多读多少行")),
            "required", List.of("path"));
    }

    @Override
    public String execute(String argumentsJson) throws ToolExecutionException {
        JsonNode args = parse(argumentsJson);
        String path = args.path("path").asText(null);
        if (path == null || path.isBlank()) throw new ToolExecutionException("缺少参数 path");
        int offset = Math.max(1, args.path("offset").asInt(1));
        Integer limit = args.hasNonNull("limit") ? args.path("limit").asInt() : null;

        Path file = PathUtil.resolveToCwd(path, cwd);
        if (!Files.isRegularFile(file)) throw new ToolExecutionException("文件不存在: " + path);
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolExecutionException("读取文件失败: " + e.getMessage(), e);
        }
        int start = offset - 1;
        if (start >= lines.size()) throw new ToolExecutionException("offset 超出文件末尾(共 " + lines.size() + " 行)");
        int end = (limit != null && limit > 0) ? Math.min(start + limit, lines.size()) : lines.size();
        String body = String.join("\n", lines.subList(start, end));
        return Truncate.truncate(body, Truncate.DEFAULT_MAX_LINES, Truncate.DEFAULT_MAX_BYTES);
    }

    private static JsonNode parse(String json) {
        try { return MAPPER.readTree(json); }
        catch (Exception e) { throw new ToolExecutionException("参数不是合法 JSON: " + e.getMessage(), e); }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=ReadToolTest test`
Expected: PASS（4 用例全绿）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/firstagent/tools/ReadTool.java src/test/java/dev/firstagent/tools/ReadToolTest.java
git commit -m "feat(tools): add ReadTool (offset/limit + truncation)"
```

---

### Task 4: GrepTool —— 纯 Java 正则搜索（不依赖 ripgrep）

**Files:**
- Create: `src/main/java/dev/firstagent/tools/GrepTool.java`
- Test: `src/test/java/dev/firstagent/tools/GrepToolTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/tools/GrepToolTest.java`
```java
package dev.firstagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GrepToolTest {
    @TempDir Path tmp;

    private void seed() throws Exception {
        Files.writeString(tmp.resolve("a.java"), "class A {\n int x = 1;\n}\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("b.txt"), "hello world\nfoo bar\n", StandardCharsets.UTF_8);
    }

    @Test void findsMatchingLines() throws Exception {
        seed();
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"class\"}");
        assertTrue(out.contains("a.java:1"), out);
    }

    @Test void ignoreCase() throws Exception {
        seed();
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"HELLO\",\"ignoreCase\":true}");
        assertTrue(out.contains("hello"), out);
    }

    @Test void noMatchReturnsMessage() throws Exception {
        seed();
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"zzzznope\"}");
        assertTrue(out.contains("无匹配"), out);
    }

    @Test void globFiltersFiles() throws Exception {
        seed();
        GrepTool tool = new GrepTool(tmp.toString());
        String out = tool.execute("{\"pattern\":\"x\",\"glob\":\"*.java\"}");
        assertTrue(out.contains("a.java"), out);
        assertFalse(out.contains("b.txt"), out);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=GrepToolTest test`
Expected: FAIL —— `cannot find symbol: class GrepTool`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/tools/GrepTool.java`
```java
package dev.firstagent.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.firstagent.ToolExecutionException;

/** 搜索工具：纯 Java Files.walk + Pattern（不依赖 ripgrep，跨平台）。
 *  args {pattern, path?, glob?, ignoreCase?, context?, limit?}。返回 file:line:text。 */
public class GrepTool implements SchematizedTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 100;
    private final String cwd;

    public GrepTool(String cwd) { this.cwd = cwd; }

    @Override public String name() { return "grep"; }
    @Override public String description() {
        return "Search file contents for a pattern. Args: pattern (required), path, glob, ignoreCase, context, limit. "
            + "Returns file:line:text. Truncated to " + DEFAULT_LIMIT + " matches.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "pattern", Map.of("type", "string", "description", "正则模式"),
                "path", Map.of("type", "string", "description", "搜索的目录或文件(默认当前目录)"),
                "glob", Map.of("type", "string", "description", "按 glob 过滤文件，如 '*.java'"),
                "ignoreCase", Map.of("type", "boolean", "description", "忽略大小写"),
                "context", Map.of("type", "number", "description", "匹配行前后各显示几行"),
                "limit", Map.of("type", "number", "description", "最大返回匹配数(默认 " + DEFAULT_LIMIT + ")")),
            "required", List.of("pattern"));
    }

    @Override
    public String execute(String argumentsJson) throws ToolExecutionException {
        JsonNode args = parse(argumentsJson);
        String patternStr = args.path("pattern").asText(null);
        if (patternStr == null || patternStr.isBlank()) throw new ToolExecutionException("缺少参数 pattern");
        int flags = args.path("ignoreCase").asBoolean(false) ? Pattern.CASE_INSENSITIVE : 0;
        Pattern pattern;
        try { pattern = Pattern.compile(patternStr, flags); }
        catch (Exception e) { throw new ToolExecutionException("非法正则 pattern: " + e.getMessage(), e); }

        String searchDir = args.path("path").asText(".");
        String glob = args.hasNonNull("glob") ? args.path("glob").asText() : null;
        int context = args.path("context").asInt(0);
        int limit = args.hasNonNull("limit") ? args.path("limit").asInt() : DEFAULT_LIMIT;
        limit = Math.max(1, limit);

        Path root = PathUtil.resolveToCwd(searchDir, cwd);
        if (!Files.exists(root)) throw new ToolExecutionException("路径不存在: " + searchDir);

        StringBuilder out = new StringBuilder();
        int count = 0;
        boolean limitReached = false;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path file : (Iterable<Path>) stream::iterator) {
                if (limitReached) break;
                if (!Files.isRegularFile(file)) continue;
                if (glob != null && !matchesGlob(file, root, glob)) continue;
                List<String> lines;
                try { lines = Files.readAllLines(file, StandardCharsets.UTF_8); }
                catch (IOException e) { continue; } // 跳过不可读文件
                for (int i = 0; i < lines.size(); i++) {
                    if (!pattern.matcher(lines.get(i)).find()) continue;
                    if (count >= limit) { limitReached = true; break; }
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (context <= 0) {
                        out.append(rel).append(':').append(i + 1).append(": ").append(lines.get(i).strip()).append('\n');
                    } else {
                        for (int j = Math.max(0, i - context); j <= Math.min(lines.size() - 1, i + context); j++) {
                            char sep = (j == i) ? ':' : '-';
                            out.append(rel).append(sep).append(j + 1).append(": ").append(lines.get(j).strip()).append('\n');
                        }
                    }
                    count++;
                }
            }
        } catch (IOException e) {
            throw new ToolExecutionException("搜索失败: " + e.getMessage(), e);
        }

        String body = out.toString();
        if (body.isEmpty()) return "无匹配结果";
        if (limitReached) body += "\n[达到匹配上限 " + limit + "，可用 limit=" + (limit * 2) + " 查看更多]";
        return Truncate.truncate(body, Truncate.DEFAULT_MAX_LINES, Truncate.DEFAULT_MAX_BYTES);
    }

    private static boolean matchesGlob(Path file, Path root, String glob) {
        PathMatcher m = file.getFileSystem().getPathMatcher("glob:" + glob);
        return m.matches(root.relativize(file));
    }

    private static JsonNode parse(String json) {
        try { return MAPPER.readTree(json); }
        catch (Exception e) { throw new ToolExecutionException("参数不是合法 JSON: " + e.getMessage(), e); }
    }
}
```

> 注：`for (Path file : (Iterable<Path>) stream::iterator)` 用方法引用把 `Stream` 转 `Iterable`，避免 `Files.walk` 未关流。等价写法也可用 `stream.filter(...)` 链式收集后遍历。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=GrepToolTest test`
Expected: PASS（4 用例全绿）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/firstagent/tools/GrepTool.java src/test/java/dev/firstagent/tools/GrepToolTest.java
git commit -m "feat(tools): add GrepTool (pure-Java regex, no ripgrep)"
```

---

### Task 5: BashTool —— 跑命令（ProcessBuilder + 超时）

**Files:**
- Create: `src/main/java/dev/firstagent/tools/BashTool.java`
- Test: `src/test/java/dev/firstagent/tools/BashToolTest.java`

- [ ] **Step 1: 写失败测试**

`src/test/java/dev/firstagent/tools/BashToolTest.java`
```java
package dev.firstagent.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {
    @TempDir Path tmp;

    @Test void runsCommandAndCapturesOutput() {
        BashTool tool = new BashTool(tmp.toString());
        String out = tool.execute("{\"command\":\"echo hello\"}");
        assertTrue(out.contains("hello"), out);
        assertTrue(out.contains("exit=0"), out);
    }

    @Test void reportsNonZeroExit() {
        BashTool tool = new BashTool(tmp.toString());
        String out = tool.execute("{\"command\":\"exit 3\"}");
        assertTrue(out.contains("exit=3"), out);
    }

    @Test void missingCommandThrows() {
        BashTool tool = new BashTool(tmp.toString());
        assertThrows(dev.firstagent.ToolExecutionException.class, () -> tool.execute("{}"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -Dtest=BashToolTest test`
Expected: FAIL —— `cannot find symbol: class BashTool`

- [ ] **Step 3: 写最小实现**

`src/main/java/dev/firstagent/tools/BashTool.java`
```java
package dev.firstagent.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.firstagent.ToolExecutionException;

/** 执行 shell 命令工具：args {command, timeoutMs?}。ProcessBuilder 走系统默认 shell（Windows cmd /c，POSIX sh -c）。 */
public class BashTool implements SchematizedTool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final long MAX_TIMEOUT_MS = 120_000;
    private final String cwd;

    public BashTool(String cwd) { this.cwd = cwd; }

    @Override public String name() { return "bash"; }
    @Override public String description() {
        return "Run a shell command and return its output. Args: command (required), timeoutMs (optional, max 120000).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "command", Map.of("type", "string", "description", "要执行的命令"),
                "timeoutMs", Map.of("type", "number", "description", "超时毫秒(默认 30000)")),
            "required", List.of("command"));
    }

    @Override
    public String execute(String argumentsJson) throws ToolExecutionException {
        JsonNode args = parse(argumentsJson);
        String command = args.path("command").asText(null);
        if (command == null || command.isBlank()) throw new ToolExecutionException("缺少参数 command");
        long timeout = args.hasNonNull("timeoutMs") ? args.path("timeoutMs").asLong() : DEFAULT_TIMEOUT_MS;
        timeout = Math.min(Math.max(1, timeout), MAX_TIMEOUT_MS);

        List<String> cmdline = isWindows()
            ? List.of("cmd", "/c", command)
            : List.of("/bin/sh", "-c", command);

        try {
            Process p = new ProcessBuilder(cmdline).directory(Path.of(cwd).toFile()).start();
            if (!p.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new ToolExecutionException("命令超时(" + timeout + "ms): " + command);
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            String merged = out.strip() + (err.isBlank() ? "" : "\n[stderr]\n" + err.strip());
            String result = "exit=" + p.exitValue() + "\n" + merged;
            return Truncate.truncate(result, Truncate.DEFAULT_MAX_LINES, Truncate.DEFAULT_MAX_BYTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ToolExecutionException("命令被中断", e);
        } catch (IOException e) {
            throw new ToolExecutionException("无法执行命令: " + e.getMessage(), e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static JsonNode parse(String json) {
        try { return MAPPER.readTree(json); }
        catch (Exception e) { throw new ToolExecutionException("参数不是合法 JSON: " + e.getMessage(), e); }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -Dtest=BashToolTest test`
Expected: PASS（3 用例全绿）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/firstagent/tools/BashTool.java src/test/java/dev/firstagent/tools/BashToolTest.java
git commit -m "feat(tools): add BashTool (ProcessBuilder + timeout)"
```

---

### Task 6: ToolsDemo —— 自包含 demo + 全量校验 + README

**Files:**
- Create: `src/main/java/dev/firstagent/tools/ToolsDemo.java`
- Modify: `README.md`

- [ ] **Step 1: 写 demo**

`src/main/java/dev/firstagent/tools/ToolsDemo.java`
```java
package dev.firstagent.tools;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.firstagent.AgentTool;
import dev.firstagent.ToolCall;
import dev.firstagent.ToolRegistry;

/** 自包含 demo：注册 4 个真实工具 → 打印模型可见 ToolSpec(JSON) → 派发预置调用演示"模型发调用→工具真执行"。 */
public class ToolsDemo {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String cwd = System.getProperty("user.dir");
        List<SchematizedTool> tools = List.of(
            new ReadTool(cwd), new WriteTool(cwd), new GrepTool(cwd), new BashTool(cwd));

        ToolRegistry registry = new ToolRegistry();
        for (AgentTool t : tools) registry.register(t);

        System.out.println("=== 1. 模型可见的工具规格 (schema 自描述) ===");
        for (SchematizedTool t : tools) {
            ToolSpec spec = new ToolSpec(t.name(), t.description(), t.inputSchema());
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(spec));
        }

        System.out.println("\n=== 2. 派发执行 (模型发调用 → 工具真执行) ===");
        dispatch(registry, new ToolCall("call_1", "write", "{\"path\":\"target/demo.txt\",\"content\":\"hello 工具模块\"}"));
        dispatch(registry, new ToolCall("call_2", "read",   "{\"path\":\"target/demo.txt\"}"));
        dispatch(registry, new ToolCall("call_3", "grep",   "{\"pattern\":\"工具\",\"path\":\"target/demo.txt\"}"));
        dispatch(registry, new ToolCall("call_4", "bash",   "{\"command\":\"echo from-bash\"}"));
    }

    private static void dispatch(ToolRegistry registry, ToolCall call) {
        AgentTool tool = registry.get(call.name());
        if (tool == null) { System.out.println(call.id() + " (" + call.name() + ") -> unknown tool"); return; }
        try {
            System.out.println(call.id() + " (" + call.name() + ") -> " + tool.execute(call.argumentsJson()));
        } catch (Exception e) {
            System.out.println(call.id() + " (" + call.name() + ") -> ERROR " + e.getMessage());
        }
    }
}
```

> 注：demo 写文件到 `target/demo.txt`（`target/` 已被 .gitignore 忽略，不会误入库）。

- [ ] **Step 2: 跑 demo（无 Maven exec 插件，用 build-classpath 组装运行）**

```bash
export JAVA_HOME="$(cygpath -w /c/Users/13374/.jdks/ms-17.0.16)"
export PATH="/c/Users/13374/.maven/expanded/apache-maven-3.9.16/bin:$PATH"
mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
CP="$(cat target/cp.txt)"
java -cp "target/classes;$CP" dev.firstagent.tools.ToolsDemo
```
Expected: 打印 4 个 ToolSpec JSON；随后 `call_1 write`、`call_2 read`、`call_3 grep` 返回成功文本，`call_4 bash` 输出含 `from-bash` 与 `exit=0`。

- [ ] **Step 3: 全量校验**

Run: `mvn -q test`
Expected: 全绿（TruncateTest 3 + PathUtilTest 2 + WriteToolTest 3 + ReadToolTest 4 + GrepToolTest 4 + BashToolTest 3 + 既有 DemoToolsTest 2 + 既有 AgentLoop/SalvageParser/llm 测试，全部 PASS）

- [ ] **Step 4: 更新 README**

在 `README.md`（当前仅一行 "A simplified Coding Agent inspired by the implementation of Pi"）追加工具模块说明：
```markdown

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
java -cp "target/classes;$(cat target/cp.txt)" dev.firstagent.tools.ToolsDemo
```
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/firstagent/tools/ToolsDemo.java README.md
git commit -m "feat(tools): add ToolsDemo end-to-end demo + README"
```

---

## Self-Review

**1. Spec 覆盖：**
- AC-1 4 个真实工具实现 `AgentTool`：Task 2/3/4/5（Write/Read/Grep/Bash）✓
- AC-2 每个工具 `inputSchema()` + demo 合成 `ToolSpec`：Task 1 定义 `SchematizedTool`/`ToolSpec`，Task 2-5 各工具实现 `inputSchema()`，Task 6 demo 打印 ✓
- AC-3 `ToolsDemo.main` 可跑通（注册→打印 specs→派发调用拿真实结果）：Task 6 ✓
- AC-4 `mvn test` 全绿不回归：Task 6 Step 3 ✓
- AC-5 不编辑在途文件（LoopStrategy/TurnDecision/AgentEvent）：全计划只 `Modify: README.md`，不碰任何 RunLoop/Session/loop 文件 ✓

**2. 占位符扫描：** 无 TBD/TODO；每个 code 步骤含完整可粘贴代码；命令含预期输出。

**3. 类型一致性：**
- `SchematizedTool extends AgentTool`，各工具 `implements SchematizedTool`、`@Override inputSchema()` 返回 `Map<String,Object>` —— Task 1 定义、Task 2-5 复用，签名一致。
- `Truncate.truncate(String,int,int)` / `PathUtil.resolveToCwd(String,String)→Path` / `ToolRegistry.register(AgentTool)` / `ToolCall(id,name,argumentsJson)` —— 与既有契约对齐。
- ToolsDemo 用 `List<SchematizedTool>` 迭代并调用 `t.inputSchema()`，与 `SchematizedTool` 定义一致。

**已知取舍（文档已述）：** Grep 纯 Java 正则（非 ripgrep）、无流式/渲染/diff（方案 2 砍掉）、`inputSchema` 不进 live `AgentTool`（活项目"留二期"）。
