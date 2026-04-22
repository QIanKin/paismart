package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.SpotlightTokenRefresher;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpotlightOauthRefreshToolTest {

    private SpotlightTokenRefresher refresher;
    private XhsCookieService cookies;
    private SpotlightOauthRefreshTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        refresher = mock(SpotlightTokenRefresher.class);
        cookies = mock(XhsCookieService.class);
        tool = new SpotlightOauthRefreshTool(refresher, cookies);
    }

    @Test
    void nonAdminAlsoAllowed_godmode() {
        PermissionResult r = tool.checkPermission(
                ToolContext.builder().userId("u1").orgTag("acme").role("user").build(),
                mapper.createObjectNode());
        assertInstanceOf(PermissionResult.Allow.class, r);
    }

    @Test
    void isDestructive() {
        assertTrue(tool.isDestructive(mapper.createObjectNode()));
        assertFalse(tool.isConcurrencySafe(mapper.createObjectNode()));
    }

    @Test
    void noTargetWhenOrgHasNoSpotlightCookie() {
        when(cookies.list(anyString())).thenReturn(List.of(
                cookie(1L, "xhs_pc", XhsCookie.Status.ACTIVE)));

        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(),
                mapper.createObjectNode());
        assertTrue(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) r.data();
        // Phase 4b: 结构化 errorCode 进 data.code + meta.errorCode
        assertEquals("no_target", payload.get("code"));
        assertEquals("no_target", r.meta().get("errorCode"));
        // 人话 summary 里不应该有 no_target 前缀
        assertFalse(r.summary().startsWith("no_target:"));
        assertTrue(String.valueOf(payload.get("message")).contains("数据源"));
    }

    @Test
    void usesExplicitIdWhenProvided() {
        when(refresher.refresh(anyLong(), anyString()))
                .thenReturn(SpotlightTokenRefresher.Result.ok("2026-05-01T00:00:00Z", 7200));

        ObjectNode in = mapper.createObjectNode();
        in.put("id", 42L);
        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);
        assertFalse(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals(42L, data.get("id"));
        assertEquals(Boolean.TRUE, data.get("ok"));
    }

    @Test
    void picksFirstActiveSpotlightByDefault() {
        XhsCookie a = cookie(1L, "xhs_pc", XhsCookie.Status.ACTIVE);
        XhsCookie b = cookie(2L, "xhs_spotlight", XhsCookie.Status.EXPIRED);
        XhsCookie c = cookie(3L, "xhs_spotlight", XhsCookie.Status.ACTIVE);
        when(cookies.list("acme")).thenReturn(List.of(a, b, c));
        when(refresher.refresh(anyLong(), anyString()))
                .thenReturn(SpotlightTokenRefresher.Result.ok("2026-06-01T00:00:00Z", 7200));

        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(),
                mapper.createObjectNode());
        assertFalse(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals(3L, data.get("id")); // skip EXPIRED, pick active one
    }

    @Test
    void refresherFailurePropagatedAsErrorResult() {
        when(refresher.refresh(anyLong(), anyString()))
                .thenReturn(SpotlightTokenRefresher.Result.configMissing());

        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);
        assertTrue(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals("config_missing", data.get("errorType"));
    }

    private static XhsCookie cookie(Long id, String platform, XhsCookie.Status s) {
        XhsCookie c = new XhsCookie();
        c.setId(id);
        c.setOwnerOrgTag("acme");
        c.setPlatform(platform);
        c.setStatus(s);
        c.setAccountLabel("lbl-" + id);
        return c;
    }
}
