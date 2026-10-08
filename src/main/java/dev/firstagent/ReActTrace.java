package dev.firstagent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ReAct 轨迹重建 —— 订阅 AgentLoop 的 AgentEvent 流，把「思考/行动/观察/Finish」按序还原。
 * 引擎零改动：AgentLoop.execute(input, trace) 复用现有 Consumer&lt;AgentEvent&gt; 出口。
 *
 * 映射：assistant(有 toolCalls) → Step.thought + action("name[json]")；
 *       同轮后随的 TOOL 结果 → 回填 Step.observation；execute 返回值 → finish。
 * 诚实边界：模型可静默发工具调（text 为空）→ thought 如实为 null，渲染成"(无思考文本)"，不编造。
 */
public final class ReActTrace implements Consumer<AgentEvent> {

    /** 一步的思考/行动/观察。 */
    public record Step(String thought, String action, String observation) {}

    private final List<Step> steps = new ArrayList<>();
    private Step current;         // 正在累积的一步(等 observation 回填)
    private String finish;

    @Override
    public void accept(AgentEvent e) {
        if (e instanceof AgentEvent.MessageEnded m) {
            Message msg = m.message();
            if (msg.role() == Message.Role.ASSISTANT && !msg.toolCalls().isEmpty()) {
                for (ToolCall tc : msg.toolCalls()) {
                    Step s = new Step(msg.text(), tc.name() + "[" + tc.argumentsJson() + "]", null);
                    steps.add(s);
                    current = s;
                }
            }
            // 纯回答：无工具，不生成 step（finish 由外壳回填）
        } else if (e instanceof AgentEvent.ToolEnded t) {
            if (current != null) {
                steps.set(steps.size() - 1,
                        new Step(current.thought(), current.action(), t.result().text()));
                current = null;
            }
        }
    }

    public List<Step> steps() { return List.copyOf(steps); }

    /** 外壳(调用方)在 execute 返回后调用，供测试/CLI 取得最终答案。 */
    public void finish(String answer) { this.finish = answer; }
    public String finish() { return finish; }
}