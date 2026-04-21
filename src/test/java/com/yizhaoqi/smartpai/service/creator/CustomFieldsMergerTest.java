package com.yizhaoqi.smartpai.service.creator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomFieldsMergerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mergesNewKeysIntoExisting() throws Exception {
        String merged = CustomFieldsMerger.merge(
                "{\"bd\":\"张三\",\"level\":\"S\"}",
                "{\"city\":\"北京\",\"level\":\"A\"}");
        JsonNode n = mapper.readTree(merged);
        assertEquals("张三", n.get("bd").asText());
        assertEquals("北京", n.get("city").asText());
        assertEquals("A", n.get("level").asText(), "incoming 覆盖 existing");
    }

    @Test
    void nullValueRemovesKey() throws Exception {
        String merged = CustomFieldsMerger.merge(
                "{\"bd\":\"张三\",\"level\":\"S\"}",
                "{\"level\":null}");
        JsonNode n = mapper.readTree(merged);
        assertTrue(n.has("bd"));
        assertFalse(n.has("level"), "null 表示删除");
    }

    @Test
    void nullExistingKeepsIncomingCleaned() throws Exception {
        String merged = CustomFieldsMerger.merge(null,
                "{\"a\":1,\"b\":null}");
        JsonNode n = mapper.readTree(merged);
        assertEquals(1, n.get("a").asInt());
        assertFalse(n.has("b"));
    }

    @Test
    void nullIncomingPreservesExisting() throws Exception {
        String merged = CustomFieldsMerger.merge("{\"a\":1}", null);
        assertEquals("{\"a\":1}", merged);
    }

    @Test
    void emptyResultBecomesNull() {
        String merged = CustomFieldsMerger.merge("{\"a\":1}", "{\"a\":null}");
        assertNull(merged);
    }

    @Test
    void malformedJsonTreatedAsEmpty() throws Exception {
        String merged = CustomFieldsMerger.merge("not-json", "{\"a\":1}");
        JsonNode n = mapper.readTree(merged);
        assertEquals(1, n.get("a").asInt());
    }
}
