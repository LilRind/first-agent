package llm;

import test.Assert;

/**
 * llm.ChatClientFactory —— 依据 LLM_PROTOCOL 选型 OpenAI / Anthropic 客户端。
 * RED:ChatClientFactory 尚不存在,编译失败即特性缺失。
 *
 * 两个默认构造在无 key 时也能实例化(key 缺失只在 chat() 里才抛),因此这里
 * 只断言"选到哪个具体客户端类型",不必真发网络。
 */
public class ChatClientFactoryTest {

    public static void main(String[] args) {
        Assert.true_(ChatClientFactory.create("anthropic") instanceof AnthropicChatClient, "anthropic → Anthropic 客户端");
        Assert.true_(ChatClientFactory.create("ANTHROPIC") instanceof AnthropicChatClient, "协议名大小写不敏感");
        Assert.true_(ChatClientFactory.create("openai") instanceof OpenAiCompatibleChatClient, "openai → OpenAI 客户端");
        Assert.true_(ChatClientFactory.create("") instanceof OpenAiCompatibleChatClient, "空串 → 默认 OpenAI");
        Assert.true_(ChatClientFactory.create(null) instanceof OpenAiCompatibleChatClient, "null → 默认 OpenAI");
        Assert.true_(ChatClientFactory.create("unknown") instanceof OpenAiCompatibleChatClient, "未知值 → 默认 OpenAI");

        System.out.println("PASS " + ChatClientFactoryTest.class.getName());
    }
}