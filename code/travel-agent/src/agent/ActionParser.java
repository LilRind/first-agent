package agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 LLM 输出解析成结构化动作 —— 对应书本"解析 Action 那一行"的逻辑。
 *
 * LLM 被要求输出：
 *   Thought: ...
 *   Action: get_weather(city="北京")
 * 或收尾：
 *   Action: Finish[最终答案]
 *
 * 我们做两件事：
 * 1. 从整段输出里抠出 Action 那一行；
 * 2. 判断它是"调用工具"还是"Finish 收尾"，并拆出工具名和参数。
 * 真实工程会用结构化输出（JSON / 函数调用协议），这里先手写解析，把原理讲明白。
 */
public final class ActionParser {

    /** 匹配 Action: 那一行（行尾不留空格）。 */
    private static final Pattern ACTION_LINE = Pattern.compile("Action:\\s*(.*?)\\s*$", Pattern.MULTILINE);
    /** 匹配 Finish[xxx] 收尾。 */
    private static final Pattern FINISH = Pattern.compile("Finish\\[(.*)]", Pattern.DOTALL);
    /** 匹配工具名。 */
    private static final Pattern TOOL_NAME = Pattern.compile("^([a-zA-Z_]+)\\(");
    /** 匹配参数 key="value"，逗号分隔。 */
    private static final Pattern ARG = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private ActionParser() {
    }

    /** 解析结果：要么是工具调用，要么是 Finish。 */
    public sealed interface Parsed permits ToolCall, Finish { }

    /** 调用工具：name + 参数表。 */
    public record ToolCall(String name, Map<String, String> args) implements Parsed {
    }

    /** 收尾：最终答案。 */
    public record Finish(String answer) implements Parsed {
    }

    /**
     * 从 LLM 的一段输出中解析出动作。解析不出 Action 时返回 null，由上层兜底。
     */
    public static Parsed parse(String llmOutput) {
        Matcher m = ACTION_LINE.matcher(llmOutput);
        if (!m.find()) {
            return null;
        }
        String action = m.group(1).trim();

        // 1) Finish 收尾
        Matcher fin = FINISH.matcher(action);
        if (fin.find()) {
            return new Finish(fin.group(1).trim());
        }

        // 2) 工具调用
        Matcher name = TOOL_NAME.matcher(action);
        if (name.find()) {
            Map<String, String> args = new LinkedHashMap<>();
            Matcher arg = ARG.matcher(action);
            while (arg.find()) {
                args.put(arg.group(1), arg.group(2));
            }
            return new ToolCall(name.group(1), args);
        }

        // 3) 识别不了，算解析失败
        return null;
    }
}
