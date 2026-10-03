package dev.firstagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.firstagent.AssistantReply;
import dev.firstagent.LlmProvider;
import dev.firstagent.Message;
import dev.firstagent.ToolCall;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** 真实 OpenAI 兼容 provider：读 env OPENAI_API_KEY，调 /chat/completions。无 key 时集成测试自动跳过。 */
public class OpenAILlmProvider implements LlmProvider {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String apiKey;
    private final String endpoint;
    private final String model;

    public OpenAILlmProvider() {
        this(System.getenv("OPENAI_API_KEY"), DEFAULT_ENDPOINT, "gpt-4o-mini");
    }

    public OpenAILlmProvider(String apiKey, String endpoint, String model) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.model = model;
    }

    @Override
    public AssistantReply chat(List<Message> history) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未设置 OPENAI_API_KEY");
        }
        try {
            ObjectNode body = M.createObjectNode();
            body.put("model", model);
            body.set("messages", toOpenAIMessages(history));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode msg = M.readTree(resp.body()).path("choices").get(0).path("message");

            String text = msg.path("content").isValueNode() ? msg.path("content").asText() : null;
            List<ToolCall> calls = new ArrayList<>();
            JsonNode tcs = msg.path("tool_calls");
            if (tcs.isArray()) {
                for (JsonNode tc : tcs) {
                    calls.add(new ToolCall(
                            tc.path("id").asText(),
                            tc.path("function").path("name").asText(),
                            tc.path("function").path("arguments").asText()));
                }
            }
            return new AssistantReply(text, calls, msg.path("stop_reason").asText(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI 调用被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI 调用失败: " + e.getMessage(), e);
        }
    }

    /** 把 append-only 历史映射成 OpenAI 消息格式。 */
    private ArrayNode toOpenAIMessages(List<Message> history) {
        ArrayNode arr = M.createArrayNode();
        for (Message m : history) {
            ObjectNode o = arr.addObject();
            switch (m.role()) {
                case SYSTEM, USER -> {
                    o.put("role", m.role() == Message.Role.SYSTEM ? "system" : "user");
                    o.put("content", m.text());
                }
                case ASSISTANT -> {
                    if (m.toolCalls().isEmpty()) {
                        o.put("role", "assistant");
                        o.put("content", m.text());
                    } else {
                        o.put("role", "assistant");
                        o.putNull("content");                          // OpenAI 要求含 tool_calls 时 content 可空
                        ArrayNode tcs = o.putArray("tool_calls");
                        for (ToolCall tc : m.toolCalls()) {
                            ObjectNode t = tcs.addObject();
                            t.put("id", tc.id());
                            t.put("type", "function");
                            ObjectNode fn = t.putObject("function");
                            fn.put("name", tc.name());
                            fn.put("arguments", tc.argumentsJson());
                        }
                    }
                }
                case TOOL -> {
                    o.put("role", "tool");
                    o.put("tool_call_id", m.toolCallId());
                    o.put("content", m.isError() ? "[tool_use_error] " + m.text() : m.text());
                }
            }
        }
        return arr;
    }
}