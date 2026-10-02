package core;

import test.Assert;

/**
 * core.Message —— 不可变消息模型,四种 role + 工厂 + equals/hashCode。
 * RED:此时 core.Message 尚不存在,javac 编译失败即特性缺失。
 */
public class MessageTest {

    public static void main(String[] args) {
        // 四种 role 均可构造
        Message sys = Message.system("你是助手");
        Message usr = Message.user("查下天气");
        Message asst = Message.assistant("好的");
        Message t = Message.tool("[{\"temperature\":\"20\"}]");

        // role 与 content 正确
        Assert.equal("system", sys.role());
        Assert.equal("你是助手", sys.content());
        Assert.equal("user", usr.role());
        Assert.equal("assistant", asst.role());
        Assert.equal("tool", t.role());

        // equals/hashCode 契约
        Assert.equalsContract(sys, Message.system("你是助手"), Message.system("别的内容"));
        Assert.equalsContract(usr, Message.user("查下天气"), Message.user("查下天气?"));

        // 不同 role 必不等
        Assert.true_(!sys.equals(Message.user("你是助手")), "不同 role 不该相等");

        System.out.println("PASS " + MessageTest.class.getName());
    }
}