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
