package com.yizhaoqi.smartpai.service.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeWhitelistTest {

    @Test
    void allLayersEmptyMeansUnrestricted() {
        assertNull(AgentRuntime.mergeWhitelists(List.of(), null, List.of()));
    }

    @Test
    void intersectsNonEmptyLayers() {
        List<String> merged = AgentRuntime.mergeWhitelists(
                List.of("knowledge_search", "web_fetch", "bash"),
                List.of("web_fetch", "bash"),
                List.of("web_fetch", "tool_search")
        );

        assertEquals(List.of("web_fetch"), merged);
    }

    @Test
    void emptyIntersectionStaysEmptyInsteadOfFallingBackToAllTools() {
        List<String> merged = AgentRuntime.mergeWhitelists(
                List.of("knowledge_search"),
                List.of("bash"),
                List.of()
        );

        assertTrue(merged != null && merged.isEmpty());
    }
}
