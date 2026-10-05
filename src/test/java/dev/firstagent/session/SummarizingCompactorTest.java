package dev.firstagent.session;

import dev.firstagent.Message;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SummarizingCompactorTest {

    private static Message msg(String text) { return Message.user(text); }

    @Test void foldsOldMessagesIntoSummaryWhenOverBudget() {
        List<Message> history = List.of(
                msg("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),  // 32 字符 → 8 tokens
                msg("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),  // 32 → 8
                msg("cccccccccccccccccccccccccccccccc"),  // 32 → 8
                msg("dddddddddddddddddddddddddddddddd"),  // 32 → 8
                msg("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")); // 32 → 8  合计 40 > budget 30
        SummarizingCompactor c = new SummarizingCompactor(30);

        Optional<List<Message>> out = c.compact(history);

        assertTrue(out.isPresent(), "超预算应折叠");
        List<Message> folded = out.get();
        assertTrue(folded.get(0).text().startsWith("[压缩摘要]"), "首条应为摘要 system");
        // 保留最新 KEEP_RECENT=4 条原文
        assertEquals(1 + 4, folded.size());
        for (int i = 1; i < folded.size(); i++) {
            assertEquals(history.get(history.size() - 4 + (i - 1)), folded.get(i));
        }
    }

    @Test void returnsEmptyWhenUnderBudget() {
        List<Message> history = List.of(msg("a"), msg("b"), msg("c"));
        SummarizingCompactor c = new SummarizingCompactor(1000);

        assertTrue(c.compact(history).isEmpty(), "预算内不压缩");
    }

    @Test void returnsEmptyWhenNotEnoughMessages() {
        List<Message> history = List.of(
                msg("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                msg("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
        SummarizingCompactor c = new SummarizingCompactor(1);  // 超预算，但只有 2 条 ≤ KEEP_RECENT

        assertTrue(c.compact(history).isEmpty(), "消息太少不折叠");
    }
}