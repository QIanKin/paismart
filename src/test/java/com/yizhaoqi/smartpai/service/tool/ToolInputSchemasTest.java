package com.yizhaoqi.smartpai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolInputSchemasTest {

    @Test
    void buildsObjectSchemaWithRequired() {
        JsonNode s = ToolInputSchemas.object()
                .stringProp("query", "the query", true)
                .integerProp("topK", "top N", false)
                .booleanProp("caseInsensitive", null, false)
                .build();
        assertEquals("object", s.path("type").asText());
        assertEquals("string", s.path("properties").path("query").path("type").asText());
        assertEquals("the query", s.path("properties").path("query").path("description").asText());
        assertEquals("integer", s.path("properties").path("topK").path("type").asText());
        assertTrue(s.path("required").toString().contains("query"));
        // only required listed in the array
        assertEquals(1, s.path("required").size());
    }

    @Test
    void enumPropRendersEnumArray() {
        JsonNode s = ToolInputSchemas.object()
                .enumProp("mode", "which output", List.of("files_with_matches", "count", "content"), true)
                .build();
        JsonNode en = s.path("properties").path("mode").path("enum");
        assertEquals(3, en.size());
        assertEquals("files_with_matches", en.get(0).asText());
    }

    @Test
    void additionalPropertiesFalseEnforced() {
        JsonNode s = ToolInputSchemas.object()
                .stringProp("x", "x", true)
                .additionalProperties(false)
                .build();
        assertEquals(false, s.path("additionalProperties").asBoolean(true));
    }
}
