package com.yizhaoqi.smartpai.service.tool;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4b 回归：验证 ToolResult 错误码 / 人话分离。
 *
 * 需要覆盖：
 * <ul>
 *   <li>{@code error(code, humanMessage)}：code 进 meta.errorCode、summary = humanMessage（不拼 code 前缀）</li>
 *   <li>{@code error(code, humanMessage, extra)}：extra 不能覆盖 error/code/message</li>
 *   <li>旧签名 {@code error("code: 人话")}：自动拆分，meta.errorCode 仍然存在</li>
 *   <li>旧签名 {@code error("纯粹一句话")}：不误伤，meta 为空</li>
 *   <li>旧签名 {@code error("Error: something")}：大写 "Error" 不是合法 code，应视作人话</li>
 * </ul>
 */
class ToolResultTest {

    @Test
    void explicitCodeSignature_fillsMetaErrorCode_andKeepsSummaryClean() {
        ToolResult r = ToolResult.error("cookie_invalid", "缺字段 a1，请重新扫码");

        assertTrue(r.isError());
        assertEquals("缺字段 a1，请重新扫码", r.summary(),
                "summary 不应再带 code 前缀");

        assertEquals("cookie_invalid", r.meta().get("errorCode"),
                "meta.errorCode 应等于 code");

        // data 载荷给 LLM：同时包含 code + message
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals(Boolean.TRUE, data.get("error"));
        assertEquals("cookie_invalid", data.get("code"));
        assertEquals("缺字段 a1，请重新扫码", data.get("message"));
    }

    @Test
    void explicitCodeSignature_withExtra_extraDoesNotOverrideReservedFields() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("httpStatus", 500);
        extra.put("code", "should_be_ignored"); // 试图覆盖
        extra.put("message", "should_be_ignored"); // 试图覆盖
        extra.put("error", false); // 试图覆盖
        extra.put("cookieId", 42L);

        ToolResult r = ToolResult.error("upstream_error", "聚光返回 500", extra);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals(Boolean.TRUE, data.get("error"), "error 字段不能被覆盖");
        assertEquals("upstream_error", data.get("code"), "code 字段不能被 extra 覆盖");
        assertEquals("聚光返回 500", data.get("message"), "message 字段不能被 extra 覆盖");
        assertEquals(500, data.get("httpStatus"));
        assertEquals(42L, data.get("cookieId"));

        assertEquals("upstream_error", r.meta().get("errorCode"));
    }

    @Test
    void legacyPrefix_autoSplitsCodeAndMessage() {
        ToolResult r = ToolResult.error("not_found: cookie #42 不存在");

        // summary 应该去掉 code 前缀——这是对前端展示的关键修复
        assertEquals("cookie #42 不存在", r.summary());
        assertEquals("not_found", r.meta().get("errorCode"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals("not_found", data.get("code"));
        assertEquals("cookie #42 不存在", data.get("message"));
    }

    @Test
    void legacyPrefix_doesNotMatchCapitalizedWords() {
        // "Error" 大写开头 → 不符合 snake_case，不应被当作 code
        ToolResult r = ToolResult.error("Error: something happened");

        assertEquals("Error: something happened", r.summary(), "非 code 前缀应原样保留");
        assertNull(r.meta().get("errorCode"));
        assertTrue(r.meta().isEmpty(), "没有 code 时 meta 应为空");
    }

    @Test
    void pureMessage_noCodePrefix_noErrorCodeInMeta() {
        ToolResult r = ToolResult.error("这是一段纯粹的中文错误描述，没有 code 前缀");

        assertEquals("这是一段纯粹的中文错误描述，没有 code 前缀", r.summary());
        assertTrue(r.meta().isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertFalse(data.containsKey("code"), "没识别到 code 时 data 里也不该有 code 键");
        assertEquals(Boolean.TRUE, data.get("error"));
    }

    @Test
    void successResults_haveNoErrorCode_andAreNotError() {
        ToolResult ok = ToolResult.of(Map.of("id", 1), "done");

        assertFalse(ok.isError());
        assertNull(ok.meta().get("errorCode"));
    }
}
