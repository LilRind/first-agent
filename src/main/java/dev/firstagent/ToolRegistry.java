package dev.firstagent;

import java.util.LinkedHashMap;
import java.util.Map;

/** 按 name 注册/取工具。 */
public class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry register(AgentTool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public AgentTool get(String name) { return tools.get(name); }

    public boolean contains(String name) { return tools.containsKey(name); }
}