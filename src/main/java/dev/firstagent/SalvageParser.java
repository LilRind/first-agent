package dev.firstagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** D2 简化：校验 LLM 返回的参数是合法 JSON。非法/截断 → 抛 ToolExecutionException（回填 tool_use_error）。 */
public final class SalvageParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SalvageParser() {}

    public static JsonNode parse(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            throw new ToolExecutionException("参数不完整，请以完整合法 JSON 重新给参数");
        }
        try {
            return MAPPER.readTree(argumentsJson);
        } catch (Exception e) {
            throw new ToolExecutionException("参数不完整，请以完整合法 JSON 重新给参数", e);
        }
    }
}