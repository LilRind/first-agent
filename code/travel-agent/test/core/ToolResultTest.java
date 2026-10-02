package core;

import test.Assert;

/**
 * core.ToolResult —— 工具执行结果。RED:此时类不存在,javac 编译失败即特性缺失。
 */
public class ToolResultTest {

    public static void main(String[] args) {
        // 正常结果:文本 + 非错误标记
        ToolResult ok = ToolResult.ok("北京晴，气温20℃");
        Assert.equal("北京晴，气温20℃", ok.text());
        Assert.equal(false, ok.isError());

        // 错误结果:文本 + 错误标记
        ToolResult err = ToolResult.error("未配置 TAVILY_API_KEY");
        Assert.equal("未配置 TAVILY_API_KEY", err.text());
        Assert.equal(true, err.isError());

        // equals/hashCode 契约
        Assert.equalsContract(ok, ToolResult.ok("北京晴，气温20℃"), ToolResult.ok("北京晴"));
        Assert.equalsContract(err, ToolResult.error("未配置 TAVILY_API_KEY"), ToolResult.error("未配置"));

        System.out.println("PASS " + ToolResultTest.class.getName());
    }
}