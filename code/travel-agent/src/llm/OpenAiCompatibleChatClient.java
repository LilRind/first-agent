package llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import core.Message;
import util.Json;

/**
 * 真实 LLM 客户端 —— 对应书本的 openai 官方 SDK。
 *
 * 书本 Python 用 openai SDK 调用 GPT；Java 里我们直接对着 OpenAI 兼容的
 * Chat Completions HTTP 接口手写一个客户端，效果等价。
 *
 * 三个配置都从环境变量读取，这样你可以换任意一家 OpenAI 兼容的厂商
 * （GPT/DeepSeek/Kimi/GLM...）而不用改代码：
 *   LLM_API_KEY   —— API key
 *   LLM_BASE_URL  —— 接口地址，默认 https://api.openai.com/v1
 *   LLM_MODEL     —— 模型名，默认 gpt-3.5-turbo
 */
public class OpenAiCompatibleChatClient implements ChatClient {

    // 中性默认值(公开官方)。个人网关与模型经环境变量 LLM_BASE_URL / LLM_MODEL 覆盖,不写死进代码。
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1"; // 客户端会拼 /chat/completions,故含 /v1
    private static final String DEFAULT_MODEL = "gpt-3.5-turbo";

    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public OpenAiCompatibleChatClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                System.getenv("LLM_API_KEY"),
                System.getenv().getOrDefault("LLM_BASE_URL", DEFAULT_BASE_URL),
                System.getenv().getOrDefault("LLM_MODEL", DEFAULT_MODEL));
    }

    /** 可注入构造:http/baseUrl 可换成指向本地假网关的实现,便于测试。baseUrl/model 防御性 trim。 */
    public OpenAiCompatibleChatClient(HttpClient http, String apiKey, String baseUrl, String model) {
        this.http = http;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.trim();
        this.model = model.trim();
    }

    @Override
    public String chat(List<Message> messages) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 LLM_API_KEY 环境变量。可用 MockChatClient 离线跑通，或用真实 key。");
        }
        try {
            // 把 Message 列表拼成 JSON 数组
            StringBuilder msgs = new StringBuilder("[");
            for (int i = 0; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (i > 0) msgs.append(",");
                msgs.append("{\"role\":\"").append(m.role())
                        .append("\",\"content\":").append(Json.quote(m.content())).append("}");
            }
            msgs.append("]");
            String requestBody = "{\"model\":\"" + model + "\",\"messages\":" + msgs + "}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("LLM 接口返回 " + resp.statusCode() + ": " + resp.body());
            }
            String raw = resp.body();
            Map<String, Object> root;
            try {
                root = Json.parseObject(raw);
            } catch (RuntimeException e) {
                // 诊断:解析失败说明网关没返回 {…} 开头的 JSON,把原始响应带出来便于定位
                String snippet = raw.length() > 800 ? raw.substring(0, 800) + "…[已截断]" : raw;
                throw new IllegalStateException("LLM 返回无法解析为 JSON,开头为: <" + snippet + ">", e);
            }
            String content = Json.getString(root, "choices[0].message.content");
            if (content == null) {
                throw new IllegalStateException("LLM 返回里没有 choices[0].message.content");
            }
            return content;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("调用 LLM 网络失败 - " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 LLM 被中断", e);
        }
    }
}