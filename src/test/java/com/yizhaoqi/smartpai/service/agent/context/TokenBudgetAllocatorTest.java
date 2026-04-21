package com.yizhaoqi.smartpai.service.agent.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenBudgetAllocatorTest {

    private ContextContribution mk(String layer, int priority, int tokens, String body) {
        return ContextContribution.of(layer, priority, tokens,
                List.of(Map.of("role", layer, "content", body)));
    }

    private ContextContribution mkCompressible(String layer, int priority, int tokens, int compressedTokens) {
        return ContextContribution.compressible(layer, priority, tokens,
                List.of(Map.of("role", layer, "content", "FULL")),
                List.of(Map.of("role", layer, "content", "SHORT")),
                compressedTokens, layer + "(c)");
    }

    @Test
    void keepsEverythingUnderBudget() {
        var res = TokenBudgetAllocator.allocate(
                List.of(mk("system", 100, 50, "sys"),
                        mk("history", 70, 200, "hist")),
                1000);
        assertEquals(2, res.kept());
        assertEquals(0, res.dropped());
        assertEquals(0, res.compressed());
        assertEquals(250, res.usedTokens());
    }

    @Test
    void dropsLowPriorityWhenTight() {
        var res = TokenBudgetAllocator.allocate(
                List.of(mk("system", 100, 500, "sys"),
                        mk("memory", 50, 900, "mem")),
                800);
        // system 先入选占 500；memory 要 900 放不下，没压缩替代 → 丢
        assertEquals(1, res.kept());
        assertEquals(1, res.dropped());
        assertEquals(500, res.usedTokens());
    }

    @Test
    void usesCompressedAltWhenAvailable() {
        var res = TokenBudgetAllocator.allocate(
                List.of(mk("system", 100, 400, "sys"),
                        mkCompressible("history", 70, 1000, 100)),
                800);
        assertEquals(1, res.kept());
        assertEquals(1, res.compressed());
        assertEquals(0, res.dropped());
        assertEquals(500, res.usedTokens());
        // 消息顺序按 natural: system 在前，history 在后
        assertEquals("system", res.messages().get(0).get("role"));
        assertEquals("history", res.messages().get(1).get("role"));
        assertEquals("SHORT", res.messages().get(1).get("content"));
    }

    @Test
    void preservesNaturalOrderEvenWhenPriorityReorders() {
        // 定义顺序：user 在 index=0，但 priority 最低；system 在 index=2，但 priority 最高
        var res = TokenBudgetAllocator.allocate(
                List.of(mk("user", 50, 10, "u"),
                        mk("history", 70, 10, "h"),
                        mk("system", 100, 10, "s")),
                1000);
        assertEquals(3, res.kept());
        // 按 natural order 输出：user, history, system
        assertEquals("user", res.messages().get(0).get("role"));
        assertEquals("history", res.messages().get(1).get("role"));
        assertEquals("system", res.messages().get(2).get("role"));
    }

    @Test
    void emptyInputReturnsEmpty() {
        var res = TokenBudgetAllocator.allocate(List.of(), 1000);
        assertTrue(res.messages().isEmpty());
        assertEquals(0, res.usedTokens());
    }
}
