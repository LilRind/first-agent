package dev.firstagent;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SalvageParserTest {

    @Test void validJsonParses() {
        JsonNode node = SalvageParser.parse("{\"query\":\"hello\"}");
        assertEquals("hello", node.path("query").asText());
    }

    @Test void illegalJsonThrowsToolExecutionException() {
        ToolExecutionException e = assertThrows(ToolExecutionException.class,
                () -> SalvageParser.parse("not-json"));
        assertEquals("参数不完整，请以完整合法 JSON 重新给参数", e.getMessage());
    }

    @Test void blankJsonThrows() {
        assertThrows(ToolExecutionException.class, () -> SalvageParser.parse("   "));
    }
}