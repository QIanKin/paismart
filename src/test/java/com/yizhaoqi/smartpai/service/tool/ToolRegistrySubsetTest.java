package com.yizhaoqi.smartpai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolRegistrySubsetTest {

    @Test
    void nullMeansAllButEmptyMeansNone() {
        Tool alpha = new StubTool("alpha");
        Tool beta = new StubTool("beta");
        ToolRegistry registry = new ToolRegistry(List.of(alpha, beta));

        assertEquals(2, registry.subset(null).size());
        assertEquals(0, registry.subset(List.of()).size());
    }

    @Test
    void unknownToolNamesAreIgnoredWithoutOpeningAllTools() {
        ToolRegistry registry = new ToolRegistry(List.of(new StubTool("alpha")));

        assertEquals(0, registry.subset(List.of("missing")).size());
    }

    private record StubTool(String name) implements Tool {
        @Override public String description() { return "stub"; }
        @Override public JsonNode inputSchema() { return ToolInputSchemas.object().additionalProperties(false).build(); }
        @Override public ToolResult call(ToolContext ctx, JsonNode input) { return ToolResult.text("ok"); }
    }
}
