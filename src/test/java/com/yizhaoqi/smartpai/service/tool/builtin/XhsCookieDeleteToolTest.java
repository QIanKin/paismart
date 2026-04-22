package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.service.tool.ConfirmationRequest;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 注意：确认拦截（_confirm / _confirmToken）由 {@code ToolExecutor} 负责，
 * 所以本单测不再断言"缺 confirm 报错"；那一路归到 ToolExecutorConfirmationTest。
 * 这里只关心工具自己暴露出来的 requiresConfirmation / call 行为。
 */
class XhsCookieDeleteToolTest {

    private XhsCookieService service;
    private XhsCookieDeleteTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(XhsCookieService.class);
        tool = new XhsCookieDeleteTool(service);
    }

    @Test
    void nonAdminDenied() {
        PermissionResult r = tool.checkPermission(
                ToolContext.builder().userId("u1").orgTag("acme").role("user").build(),
                mapper.createObjectNode());
        assertInstanceOf(PermissionResult.Deny.class, r);
    }

    @Test
    void isDestructiveAndWrite() {
        assertTrue(tool.isDestructive(mapper.createObjectNode()));
        assertFalse(tool.isReadOnly(mapper.createObjectNode()));
    }

    @Test
    void missingIdReturnsError() {
        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(),
                mapper.createObjectNode());
        assertTrue(r.isError());
    }

    @Test
    void requiresConfirmationIncludesTargetMetadata() {
        XhsCookie c = cookie(7L, "xhs_pc", "old", XhsCookie.Status.ACTIVE);
        when(service.findById(eq(7L), anyString())).thenReturn(Optional.of(c));

        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        ConfirmationRequest req = tool.requiresConfirmation(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);

        assertNotNull(req);
        assertTrue(req.summary().contains("#7"));
        assertTrue(req.summary().contains("xhs_pc"));
        assertTrue(req.summary().contains("old"));
        assertFalse(req.risks().isEmpty(), "risks 不应为空，LLM 需要向用户复述");
    }

    @Test
    void requiresConfirmationStillSetWhenRowMissing() {
        when(service.findById(anyLong(), anyString())).thenReturn(Optional.empty());

        ObjectNode in = mapper.createObjectNode();
        in.put("id", 99L);
        ConfirmationRequest req = tool.requiresConfirmation(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);

        assertNotNull(req);
        assertTrue(req.summary().contains("#99"));
    }

    @Test
    void notFoundWhenRowMissingAtCallTime() {
        when(service.findById(eq(7L), anyString())).thenReturn(Optional.empty());

        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);
        assertTrue(r.isError());
    }

    @Test
    void happyPathDeletesAndReports() {
        XhsCookie c = cookie(7L, "xhs_pc", "old", XhsCookie.Status.ACTIVE);
        when(service.findById(eq(7L), anyString())).thenReturn(Optional.of(c));
        when(service.delete(anyLong(), anyString())).thenReturn(true);

        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);
        assertFalse(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals(Boolean.TRUE, data.get("deleted"));
        assertEquals(7L, data.get("id"));
    }

    private static XhsCookie cookie(Long id, String platform, String label, XhsCookie.Status s) {
        XhsCookie c = new XhsCookie();
        c.setId(id);
        c.setOwnerOrgTag("acme");
        c.setPlatform(platform);
        c.setAccountLabel(label);
        c.setStatus(s);
        return c;
    }
}
