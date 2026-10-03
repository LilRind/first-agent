package dev.firstagent.llm;

import dev.firstagent.AssistantReply;
import dev.firstagent.LlmProvider;
import dev.firstagent.Message;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** 脚本化的假 provider：按 Queue 依次弹出预设回复，测试离线运行、不依赖网络/密钥。 */
public class MockLlm implements LlmProvider {
    private final Deque<AssistantReply> script = new ArrayDeque<>();
    private int calls = 0;
    private List<Message> lastHistory;

    public static MockLlm scripted(AssistantReply... replies) {
        MockLlm m = new MockLlm();
        for (AssistantReply r : replies) m.script.addLast(r);
        return m;
    }

    @Override
    public AssistantReply chat(List<Message> history) {
        calls++;
        lastHistory = history;                       // 记录这次看到的历史，供 AC-7 断言"请求由历史派生"
        AssistantReply r = script.pollFirst();
        if (r == null) throw new IllegalStateException("MockLlm 脚本已耗尽：第 " + calls + " 次调用无预设回复");
        return r;
    }

    public int calls() { return calls; }
    public List<Message> lastHistory() { return lastHistory; }
}