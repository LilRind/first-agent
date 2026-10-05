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