package dev.firstagent.tools;

import dev.firstagent.ToolExecutionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CalculatorToolTest {

    @Test void nameAndDescription() {
        assertEquals("calculate", new CalculatorTool().name());
        assertFalse(new CalculatorTool().description().isBlank());
    }

    @Test void additionMultiplicationPrecedence() {
        String r = new CalculatorTool().execute("{\"expression\":\"2+3*4\"}");
        assertEquals("14", r);
    }

    @Test void parenthesesAndDivision() {
        String r = new CalculatorTool().execute("{\"expression\":\"(123+456)*789/12\"}");
        assertEquals("38069.25", r);
    }

    @Test void decimalResult() {
        String r = new CalculatorTool().execute("{\"expression\":\"1+0.25\"}");
        assertEquals("1.25", r);
    }

    @Test void invalidJsonThrows() {
        assertThrows(ToolExecutionException.class, () -> new CalculatorTool().execute("not-json"));
    }

    @Test void missingExpressionThrows() {
        assertThrows(ToolExecutionException.class, () -> new CalculatorTool().execute("{\"foo\":\"bar\"}"));
    }

    @Test void malformedExpressionThrows() {
        assertThrows(ToolExecutionException.class, () -> new CalculatorTool().execute("{\"expression\":\"2+*3\"}"));
    }
}