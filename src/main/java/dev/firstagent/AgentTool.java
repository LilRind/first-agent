package dev.firstagent;

import java.util.Map;

/**
 * 工具契约 —— 模型看得见的自描述对象（结构对齐 claude-code 的 Tool 契约 / pi 的 AgentTool）。
 *
 * name        — 模型用来调用它的名字
 * description — 喂给模型，让它知道何时用
 * isConcurrencySafe — 是否可并行(只读/无副作用)。v0 未用并行，先保留声明式 flag
 */
public interface AgentTool {
    String name();
    String description();

    /**
     * 执行工具。参数已由调用方解析成合法 JSON。
     * @return 返回给模型的文本结果（会被截断阈值截断）
     * @throws ToolExecutionException 执行失败 —— AgentLoop 会把它合成 tool_use_error 回填给模型
     */
    String execute(String argumentsJson) throws ToolExecutionException;

    default boolean isConcurrencySafe() { return false; }
}