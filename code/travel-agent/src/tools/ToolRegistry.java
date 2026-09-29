package tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具注册表 —— 对应书本的 available_tools dict。
 *
 * 书本 Python：available_tools = { "get_weather": fn, "get_attraction": fn }
 * 我们这里：register 把 name -> Tool 存进 Map。主循环根据 LLM 解析出的 Action
 * 里的工具名到这里查找并派发。额外加一个 buildSystemPrompt() 帮助方法，
 * 把每个工具的描述拼回系统提示词，让 LLM 知道有哪些工具可用。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Tool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /** 按名字取工具；不存在返回 null，调用方再决定怎么兜底。 */
    public Tool get(String name) {
        return tools.get(name);
    }

    /** 把所有工具的 description 拼成一整段，用于插入系统提示词。 */
    public String describeTools() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools.values()) {
            sb.append("- `").append(t.description()).append("`\n");
        }
        return sb.toString().strip();
    }
}