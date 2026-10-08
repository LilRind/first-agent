package dev.firstagent.plan;

import dev.firstagent.AssistantReply;
import dev.firstagent.llm.MockLlm;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlannerTest {

    private static final String QUESTION = "一个水果店周一卖出15个苹果，周二卖的是周一的2倍，共多少？";

    @Test void parsesPlainJsonArray() {
        MockLlm llm = MockLlm.scripted(new AssistantReply("[\"计算周一\",\"计算周二\",\"求和\"]", List.of(), "end_turn"));
        List<String> plan = new Planner(llm).plan(QUESTION);
        assertEquals(List.of("计算周一", "计算周二", "求和"), plan);
    }

    @Test void parsesJsonArrayWrappedInText() {
        MockLlm llm = MockLlm.scripted(new AssistantReply("好的，计划如下: [\"a\",\"b\"] 完成", List.of(), "end_turn"));
        List<String> plan = new Planner(llm).plan(QUESTION);
        assertEquals(List.of("a", "b"), plan);
    }

    @Test void retriesThenSucceedsOnSecondTry() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("不是JSON", List.of(), "end_turn"),
                new AssistantReply("[\"x\"]", List.of(), "end_turn"));
        assertEquals(List.of("x"), new Planner(llm, 2).plan(QUESTION));
    }

    @Test void returnsEmptyAfterMaxRetries() {
        MockLlm llm = MockLlm.scripted(
                new AssistantReply("垃圾", List.of(), "end_turn"),
                new AssistantReply("垃圾", List.of(), "end_turn"),
                new AssistantReply("垃圾", List.of(), "end_turn"));
        assertTrue(new Planner(llm, 2).plan(QUESTION).isEmpty());
    }
}