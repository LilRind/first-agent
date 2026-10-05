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
                "glob", Map.of("type", "string", "description", "按 glob 过滤文件，递归匹配，如 '*.java' 匹配任意层级"),
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
                    if (rel.isEmpty()) rel = file.getFileName().toString();
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
        Path rel = root.relativize(file);
        // 无路径分隔符的 glob 应递归匹配任意层级（如 *.java 匹配子目录）。
        // Java 的 glob "**/glob" 不匹配根级文件，故根级用原 glob 匹配，嵌套用 "**/" 前缀匹配，二者取或。
        if (glob.contains("/")) {
            return file.getFileSystem().getPathMatcher("glob:" + glob).matches(rel);
        }
        return file.getFileSystem().getPathMatcher("glob:" + glob).matches(rel)
            || file.getFileSystem().getPathMatcher("glob:**/" + glob).matches(rel);
    }

    private static JsonNode parse(String json) {
        try { return MAPPER.readTree(json); }
        catch (Exception e) { throw new ToolExecutionException("参数不是合法 JSON: " + e.getMessage(), e); }
    }
}