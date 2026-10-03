package dev.firstagent.tools;

import dev.firstagent.AgentTool;

/** Demo 工具：把收到的参数原样返回，演示工具调用成功。 */
public class EchoTool implements AgentTool {
    @Override public String name() { return "echo"; }
    @Override public String description() { return "回显给定的 JSON 参数，用于演示工具调用成功。"; }
    @Override public String execute(String argumentsJson) { return "echo: " + argumentsJson; }
}