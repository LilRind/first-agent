package dev.firstagent.tools;

import java.util.Map;

import dev.firstagent.AgentTool;

/**
 * 带 schema 自描述的工具（Pi 风格）。AgentTool 接口未含 inputSchema（按活项目留二期），
 * 故 MVP 单独声明一个子接口，让每个真实工具都提供模型可见的参数 Schema。
 */
public interface SchematizedTool extends AgentTool {
    Map<String, Object> inputSchema();
}
