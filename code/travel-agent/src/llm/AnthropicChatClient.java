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
 * Anthropic 原生协议客户端 —— 对应 Anthropic Messages API。
 *
 * 与 OpenAI 客户端的差异全部隔离在这里,上层引擎(Message/RunLoop/Agent)无感:
 * - 端点 {base}/messages,而非 /chat/completions;
 * - system 抽到请求顶层字段,不进 messages 数组(Anthropic 要求);
 * - messages 只含 user/assistant 角色(本项目工具观测以 user 消息回喂,天然满足);
 * - 必填 max_tokens;
 * - 回答在 content[0].text,而非 choices[0].message.content。
 *
 * 三个配置从环境变量读:LLM_API_KEY / LLM_BASE_URL / LLM_MODEL(可选 LLM_MAX_TOKENS)。
 * 构造函数可注入 HttpClient 与各值,便于本地假网关测试(纯 JDK com.sun.net.httpserver)。
 */
public class AnthropicChatClient implements ChatClient {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1";
    private static final int DEFAULT_MAX_TOKENS = 1024;

    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;

    public AnthropicChatClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                System.getenv("LLM_API_KEY"),
                System.getenv().getOrDefault("LLM_BASE_URL", DEFAULT_BASE_URL),
                System.getenv().getOrDefault("LLM_MODEL", "claude-3-5-sonnet"),
                Integer.parseInt(System.getenv().getOrDefault("LLM_MAX_TOKENS", String.valueOf(DEFAULT_MAX_TOKENS))));
    }

    /** 可注入构造:http 可换成指向本地假网关的客户端,便于测试。baseUrl/model 防御性 trim,容忍环境变量误带空白。 */
    public AnthropicChatClient(HttpClient http, String apiKey, String baseUrl, String model, int maxTokens) {
        this.http = http;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.trim();
        this.model = model.trim();
        this.maxTokens = maxTokens;
    }

    @Override
    public String chat(List<Message> messages) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置 LLM_API_KEY 环境变量。");
        }
        try {
            // 1) 拼请求体:system 抽到顶层,其余消息透传(仅 user/assistant)
            StringBuilder system = new StringBuilder();
            StringBuilder body = new StringBuilder("{\"model\":").append(Json.quote(model))
                    .append(",\"max_tokens\":").append(maxTokens);
            for (Message m : messages) {
                if ("system".equals(m.role())) {
                    if (system.length() > 0) system.append("\n\n");
                    system.append(m.content());
                }
            }
            if (system.length() > 0) {
                body.append(",\"system\":").append(Json.quote(system.toString()));
            }
            body.append(",\"messages\":[");
            boolean first = true;
            for (Message m : messages) {
                if ("system".equals(m.role())) continue;
                if (!first) body.append(",");
                first = false;
                body.append("{\"role\":").append(Json.quote(m.role()))
                        .append(",\"content\":").append(Json.quote(m.content())).append("}");
            }
            body.append("]}");

            // 2) POST {base}/messages,带 Bearer 认证
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/messages"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            // 3) 响应:非 200 或非 JSON 都带原始片段抛错;正常读 content[0].text
            String raw = resp.body();
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Anthropic 接口返回 " + resp.statusCode() + ": " + snippet(raw));
            }
            Map<String, Object> root;
            try {
                root = Json.parseObject(raw);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Anthropic 返回无法解析为 JSON,开头为: <" + snippet(raw) + ">", e);
            }
            Object content = Json.get(root, "content[0].text");
            if (content == null) {
                throw new IllegalStateException("Anthropic 返回里没有 content[0].text,开头为: <" + snippet(raw) + ">");
            }
            return String.valueOf(content);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("调用 Anthropic LLM 网络失败 - " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("调用 Anthropic LLM 被中断", e);
        }
    }

    /** 截断长响应,便于错误信息里定位。 */
    private static String snippet(String raw) {
        return raw.length() > 800 ? raw.substring(0, 800) + "…[已截断]" : raw;
    }
}