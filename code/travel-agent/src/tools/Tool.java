package tools;

import java.util.Map;

/**
 * agent 的一个"工具"的统一抽象。
 *
 * 书本 Python 版把工具塞进一个 dict：available_tools = { "get_weather": fn, ... }。
 * Java 里我们用一个接口 + 注册表来等价：每个工具实现这个接口，
 * 说明"我叫什么、要哪些参数、怎么执行"，注册表负责按名字查到它。
 * 这样主循环就不用关心每个工具内部怎么实现，只认 name -> call(arguments)。
 */
public interface Tool {

    /** 工具名，例如 "get_weather"。LLM 在 Action 里写的就是这个名字。 */
    String name();

    /** 工具参数说明，用于写进系统提示词给 LLM 看（它是"说明书"的一部分）。 */
    String description();

    /** 真正执行工具。arguments 是 LLM 传进来的参数名->值。返回自然语言结果。 */
    String execute(Map<String, String> arguments);
}
