package dev.firstagent;

/** 模型请求调用一个工具。id 用于把执行结果回填到对应的 tool_use。 */
public record ToolCall(String id, String name, String argumentsJson) {
}