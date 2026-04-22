package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieHealthService;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class XhsCookiePingToolTest {

    private XhsCookieHealthService health;
    private XhsCookieService cookies;
    private XhsCookiePingTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        health = mock(XhsCookieHealthService.class);
        cookies = mock(XhsCookieService.class);
        tool = new XhsCookiePingTool(health, cookies);
    }

    @Test
    void nonAdminAlsoAllowed_godmode() {
        PermissionResult r = tool.checkPermission(
                ToolContext.builder().userId("u").orgTag("acme").role("user").build(),
                mapper.createObjectNode());
        assertInstanceOf(PermissionResult.Allow.class, r);
    }

    @Test
    void missingTargetReturnsError() throws Exception {
        ToolContext ctx = ToolContext.builder().userId("u").orgTag("acme").role("admin").build();
        ToolResult r = tool.call(ctx, mapper.createObjectNode());
        assertTrue(r.isError(), "没传 ids / platform 应报错");
    }

    @Test
    void pingsExplicitIds() throws Exception {
        when(health.ping(7L, "acme")).thenReturn(
                new XhsCookieHealthService.PingResult(true, 42L, null, null, "signal", LocalDateTime.now()));
        when(health.ping(8L, "acme")).thenReturn(
                new XhsCookieHealthService.PingResult(false, null, "timeout", "10s no resp", null, LocalDateTime.now()));

        ObjectNode in = mapper.createObjectNode();
        ArrayNode ids = in.putArray("ids");
        ids.add(7);
        ids.add(8);

        ToolContext ctx = ToolContext.builder().userId("u").orgTag("acme").role("admin").build();
        ToolResult r = tool.call(ctx, in);
        assertFalse(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals(1, data.get("okCount"));
        assertEquals(1, data.get("failCount"));
    }

    @Test
    void pingsByPlatformFiltersActiveOnly() throws Exception {
        XhsCookie active = cookie(1L, "xhs_pc", XhsCookie.Status.ACTIVE);
        XhsCookie expired = cookie(2L, "xhs_pc", XhsCookie.Status.EXPIRED);
        when(cookies.list("acme")).thenReturn(List.of(active, expired));
        when(health.ping(1L, "acme")).thenReturn(
                new XhsCookieHealthService.PingResult(true, 10L, null, null, null, LocalDateTime.now()));

        ObjectNode in = mapper.createObjectNode();
        in.put("platform", "xhs_pc");
        ToolContext ctx = ToolContext.builder().userId("u").orgTag("acme").role("admin").build();
        ToolResult r = tool.call(ctx, in);

        assertFalse(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        @SuppressWarnings("unchecked")
        List<Long> pinged = (List<Long>) data.get("pinged");
        assertEquals(List.of(1L), pinged, "只 ping ACTIVE 的那条");
    }

    private static XhsCookie cookie(Long id, String platform, XhsCookie.Status s) {
        XhsCookie c = new XhsCookie();
        c.setId(id);
        c.setOwnerOrgTag("acme");
        c.setPlatform(platform);
        c.setStatus(s);
        return c;
    }
}
