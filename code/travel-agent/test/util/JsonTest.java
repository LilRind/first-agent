package util;

import test.Assert;

/**
 * util.Json —— 极简 JSON 解析器 + 字符串转义助手 quote()。
 * RED:Json.quote 尚不存在,编译失败即特性缺失。
 *
 * 验证 quote() 能正确转义 JSON 字符串字面量里的保留字符,供两个 LLM 客户端
 * (OpenAI / Anthropic)共享使用。这里是"拆轮子看懂原理",真实项目用 Jackson。
 */
public class JsonTest {

    public static void main(String[] args) {
        // 普通字符串:原样包一层引号
        Assert.equal("\"abc\"", Json.quote("abc"));
        // 空串
        Assert.equal("\"\"", Json.quote(""));
        // 中文/普通字符原样保留(不转义,UTF-8 JSON 允许)
        Assert.equal("\"北京 20℃\"", Json.quote("北京 20℃"));

        // 保留字符逐一转义
        Assert.equal("\"a\\\"b\"", Json.quote("a\"b")); // 双引号
        Assert.equal("\"a\\\\b\"", Json.quote("a\\b")); // 反斜杠
        Assert.equal("\"a\\nb\"", Json.quote("a\nb"));  // 换行
        Assert.equal("\"a\\tb\"", Json.quote("a\tb"));  // 制表
        Assert.equal("\"a\\rb\"", Json.quote("a\rb"));  // 回车

        // —— get():嵌套路径,尤其 [i].key 这种 bracket 后跟点号 ——
        testGetNestedPath();

        System.out.println("PASS " + JsonTest.class.getName());
    }

    /** Json.get 按 "content[0].text" / "a[0].b.c[1]" 路径取值。 */
    private static void testGetNestedPath() {
        Object root = Json.parse("""
                {"content":[{"type":"text","text":"推荐去故宫"}],
                 "a":[{"b":{"c":["x","y"]}}]}""");
        Assert.equal("推荐去故宫", (String) Json.get(root, "content[0].text"));
        Assert.equal("y", (String) Json.get(root, "a[0].b.c[1]"));
        // 路径不存在返回 null,不抛异常
        Assert.true_(Json.get(root, "content[9].text") == null, "越界下标应返回 null");
    }
}