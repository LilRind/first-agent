package dev.firstagent.tools;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.firstagent.AgentTool;
import dev.firstagent.ToolCall;
import dev.firstagent.ToolRegistry;

/** 自包含 demo：注册 4 个真实工具 → 打印模型可见 ToolSpec(JSON) → 派发预置调用演示"模型发调用→工具真执行"。 */
public class ToolsDemo {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String cwd = System.getProperty("user.dir");
        List<SchematizedTool> tools = List.of(
            new ReadTool(cwd), new WriteTool(cwd), new GrepTool(cwd), new BashTool(cwd));

        ToolRegistry registry = new ToolRegistry();
        for (AgentTool t : tools) registry.register(t);

        System.out.println("=== 1. 模型可见的工具规格 (schema 自描述) ===");
        for (SchematizedTool t : tools) {
            ToolSpec spec = new ToolSpec(t.name(), t.description(), t.inputSchema());
            System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(spec));
        }

        System.out.println("\n=== 2. 派发执行 (模型发调用 → 工具真执行) ===");
        dispatch(registry, new ToolCall("call_1", "write", "{\"path\":\"target/demo.txt\",\"content\":\"hello 工具模块\"}"));
        dispatch(registry, new ToolCall("call_2", "read",   "{\"path\":\"target/demo.txt\"}"));
        dispatch(registry, new ToolCall("call_3", "grep",   "{\"pattern\":\"工具\",\"path\":\"target/demo.txt\"}"));
        dispatch(registry, new ToolCall("call_4", "bash",   "{\"command\":\"echo from-bash\"}"));
    }

    private static void dispatch(ToolRegistry registry, ToolCall call) {
        AgentTool tool = registry.get(call.name());
        if (tool == null) { System.out.println(call.id() + " (" + call.name() + ") -> unknown tool"); return; }
        try {
            System.out.println(call.id() + " (" + call.name() + ") -> " + tool.execute(call.argumentsJson()));
        } catch (Exception e) {
            System.out.println(call.id() + " (" + call.name() + ") -> ERROR " + e.getMessage());
        }
    }
}
