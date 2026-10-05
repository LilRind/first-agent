package dev.firstagent.tools;

import java.util.Map;

/** 一个工具的"模型可见规格"——名字、说明、JSON Schema 参数（Pi 的 schema 自描述）。 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {
}
