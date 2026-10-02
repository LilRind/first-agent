package llm;

/**
 * LLM 客户端工厂 —— 依据协议名选 OpenAI / Anthropic 实现。
 *
 * LLM_PROTOCOL 环境变量:openai(默认)/ anthropic。其余三个配置
 * (LLM_API_KEY / LLM_BASE_URL / LLM_MODEL,以及可选的 LLM_MAX_TOKENS)
 * 由各个客户端自己的默认构造从环境读取,名字一致、不另立新 key。
 *
 * 未知值/空/null 都回退到 OpenAI(保守默认),避免选型失败静默跑不了。
 */
public final class ChatClientFactory {

    private ChatClientFactory() {
    }

    /**
     * 按协议名构造一个真实 LLM 客户端。LLM_PROTOCOL=anthropic → Anthropic 客户端,否则 OpenAI 客户端。
     */
    public static ChatClient create(String protocol) {
        if ("anthropic".equalsIgnoreCase(protocol)) {
            return new AnthropicChatClient();
        }
        return new OpenAiCompatibleChatClient();
    }
}