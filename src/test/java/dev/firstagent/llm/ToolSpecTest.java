package dev.firstagent.llm;

import dev.firstagent.tools.CalculatorTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolSpecTest {

    @Test void fromAgentToolKeepsNameAndDescription() {
        ToolSpec s = ToolSpec.from(new CalculatorTool());
        assertEquals("calculate", s.name());
        assertFalse(s.description().isBlank());
    }
}