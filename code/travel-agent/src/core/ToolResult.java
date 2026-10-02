package core;

/**
 * 一次工具调用的执行结果 —— 纯数据、不可变。
 *
 * 引擎把工具返回的观测结果包装成 ToolResult 回喂给 LLM。区别于 Message 的地方在于
 * 它显式携带 isError 标记：错误结果承载"问题出在哪"，正常结果承载"观测原文"，
 * 让上一层的决策(继续/终止/兜底)能区分这两种情况，而不是全靠文本猜。
 */
public record ToolResult(String text, boolean isError) {

    public static ToolResult ok(String text) {
        return new ToolResult(text, false);
    }

    public static ToolResult error(String text) {
        return new ToolResult(text, true);
    }
}