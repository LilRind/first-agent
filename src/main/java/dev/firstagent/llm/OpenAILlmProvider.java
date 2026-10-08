package dev.firstagent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.firstagent.AppConfig;
import dev.firstagent.AssistantReply;
import dev.firstagent.LlmProvider;
import dev.firstagent.Message;
import dev.firstagent.ToolCall;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 真实 OpenAI 兼容 provider：读 env OPENAI_API_KEY，调 /chat/completions。无 key 时集成测试自动跳过。 */
public class OpenAILlmProvider implements LlmProvider {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    /** 宽松工具参数 schema：仅声明 object 类型，字段由 description 引导（完整 input_schema 为改善项）。 */
    private static final ObjectNode LOOSE_PARAMS = M.createObjectNode()
            .put("type", "object")
            .set("properties", M.createObjectNode());

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))          // 防网络挂起永久阻塞（Important-2）
            .build();
    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final List<ToolSpec> tools;                     // 工具声明；空 = 原行为（不发 tools）

    /** 无参构造：从 AppConfig 读三个字段（.env 文件 > 环境变量 > 默认值）。无工具声明。 */
    public OpenAILlmProvider() {
        this(new AppConfig(), List.of());
    }

    /** 显式注入 config（测试可用 mock/temp .env）。无工具声明。 */
    public OpenAILlmProvider(AppConfig cfg) {
        this(cfg, List.of());
    }

    public OpenAILlmProvider(String apiKey, String endpoint, String model) {
        this(apiKey, endpoint, model, List.of());
    }

    /** 带工具声明：真实模型可发起工具调用（ReAct 完整闭环的硬前提）。 */
    public OpenAILlmProvider(AppConfig cfg, List<ToolSpec> tools) {
        this(cfg.get("OPENAI_API_KEY", null),
             cfg.get("OPENAI_BASE_URL", DEFAULT_ENDPOINT),
             cfg.get("OPENAI_MODEL", DEFAULT_MODEL), tools);
    }

    public OpenAILlmProvider(String apiKey, String endpoint, String model, List<ToolSpec> tools) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.model = model;
        this.tools = tools == null ? List.of() : tools;
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
            if (!tools.isEmpty()) {                              // 真实闭环：声明工具让模型可发起调用
                ArrayNode arr = M.createArrayNode();
                for (ToolSpec t : tools) {
                    ObjectNode fn = arr.addObject().putObject("function");
                    fn.put("name", t.name());
                    fn.put("description", t.description());
                    fn.set("parameters", LOOSE_PARAMS.deepCopy());   // 宽松 object 参数 schema
                }
                body.set("tools", arr);
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {    // Important-1：报错不静默
                String detail = M.readTree(resp.body()).path("error").path("message").asText(resp.body());
                throw new IllegalStateException("OpenAI HTTP " + resp.statusCode() + ": " + detail);
            }
            JsonNode choice = M.readTree(resp.body()).path("choices").get(0);
            JsonNode msg = choice != null ? choice.path("message") : M.nullNode();
            if (msg.isMissingNode() || msg.isNull()) {
                throw new IllegalStateException("OpenAI 响应无 choices/message: " + resp.body());
            }

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
            String stopReason = choice != null ? choice.path("finish_reason").asText(null) : null; // Minor-4
            return new AssistantReply(text, calls, stopReason);
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