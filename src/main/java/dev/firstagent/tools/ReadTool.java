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
