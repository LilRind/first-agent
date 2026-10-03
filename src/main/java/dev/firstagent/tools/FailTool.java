package dev.firstagent.tools;

import dev.firstagent.AgentTool;
import dev.firstagent.ToolExecutionException;

/** Demo 工具：每次执行必抛异常，演示工具失败闭环。 */
public class FailTool implements AgentTool {
    @Override public String name() { return "fail"; }
    @Override public String description() { return "总是失败，用于演示工具异常回填。"; }
    @Override public String execute(String argumentsJson) { throw new ToolExecutionException("always fails"); }
}