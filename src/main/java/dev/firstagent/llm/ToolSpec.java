package dev.firstagent.llm;

import dev.firstagent.AgentTool;

/**
 * provider 可见的工具声明（轻量 DTO，解耦 AgentTool 接口）。
 * name + description 序列化进 OpenAI tools 数组（功能声明）。
 */
public record ToolSpec(String name, String description) {
    public static ToolSpec from(AgentTool t) { return new ToolSpec(t.name(), t.description()); }
}