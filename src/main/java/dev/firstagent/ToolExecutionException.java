package dev.firstagent;

/** 工具执行/参数解析失败的统一异常。AgentLoop 捕获后合成 tool_use_error 回填。 */
public class ToolExecutionException extends RuntimeException {
    public ToolExecutionException(String message) { super(message); }
    public ToolExecutionException(String message, Throwable cause) { super(message, cause); }
}