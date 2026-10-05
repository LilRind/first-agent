package dev.firstagent.memory;

import dev.firstagent.*;
import dev.firstagent.session.Session;
import dev.firstagent.session.SessionStore;
import dev.firstagent.session.SummarizingCompactor;
import dev.firstagent.telemetry.TelemetryRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆编排策略 —— 一个 LoopStrategy 把三条链路串进 AgentLoop：
 * 1. 跨会话召回：首轮 findMostRecent(cwd) 把旧会话文本折叠成「长期记忆」system 前缀，每轮注入。
 * 2. 持久化：每轮把新产出的 Message append 进 SessionStore（崩溃安全 JSONL），重放可还原。
 * 3. 压缩：prepareRequest 返回 SummarizingCompactor.compact(history) 的结果（超预算折叠旧消息）。
 *
 * prepareRequest 改为返回 List<Message>，记忆注入 + 压缩折叠才对 llm.chat 真正生效。
 */
public final class MemoryRecallStrategy implements LoopStrategy {

    private static final String MEMORY_PREFIX = "[长期记忆] ";

    private final SessionStore store;
    private final SummarizingCompactor compactor;
    private final String cwd;
    private final TelemetryRecorder telemetry;

    private Session currentSession;
    private Message memoryPrefix;   // 召回出的长期记忆 system 消息（每轮前置，持续影响请求）
    private boolean injectRecorded; // memory.inject span 只记一次
    private int persistedCount;     // 已持久化到 store 的历史条数（增量 append，避免重复）

    public MemoryRecallStrategy(SessionStore store, SummarizingCompactor compactor,
                                String cwd, TelemetryRecorder telemetry) {
        this.store = store;
        this.compactor = compactor;
        this.cwd = cwd;
        this.telemetry = telemetry;
    }

    @Override
    public void prepareNextTurn(AssistantReply lastReply) {
        if (currentSession != null) return;              // 仅首轮初始化
        if (memoryPrefix == null) {
            Optional<Session> prior = store.findMostRecent(cwd);
            if (prior.isPresent()) {
                String summary = prior.get().entries().stream()
                        .map(e -> e.message().text()).filter(s -> s != null && !s.isBlank())
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                if (!summary.isBlank()) {
                    memoryPrefix = Message.system(MEMORY_PREFIX + summary);
                    telemetry.record("memory.recall", Map.of("cwd", cwd));
                }
            }
        }
        currentSession = store.create(cwd, "minimal-agent");
        telemetry.record("session.start", Map.of("cwd", cwd));
    }

    @Override
    public List<Message> prepareRequest(List<Message> history) {
        persistNew(history);
        List<Message> compacted = compactor.compact(history).orElse(history);
        if (compacted.size() < history.size()) {
            telemetry.record("compaction", Map.of("before", String.valueOf(history.size()),
                    "after", String.valueOf(compacted.size())));
        }
        List<Message> out = new ArrayList<>();
        if (memoryPrefix != null) {
            out.add(memoryPrefix);                       // 每轮前置，长期记忆持续影响请求
            if (!injectRecorded) {
                telemetry.record("memory.inject", Map.of("cwd", cwd));
                injectRecorded = true;
            }
        }
        out.addAll(compacted);
        return out;
    }

    @Override
    public TurnDecision finishTurn(List<Message> history, AssistantReply lastReply) {
        return lastReply.hasToolCalls() ? TurnDecision.continueTurn() : TurnDecision.end();
    }

    /** 把 history 里超出已持久化计数的新消息逐条 append 进 SessionStore。 */
    private void persistNew(List<Message> history) {
        if (currentSession == null) return;
        for (int i = persistedCount; i < history.size(); i++) {
            store.append(currentSession, history.get(i));
        }
        persistedCount = history.size();
    }
}