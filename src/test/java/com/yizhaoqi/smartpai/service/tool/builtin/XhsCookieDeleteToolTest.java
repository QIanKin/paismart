package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
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
    void missingConfirmReturnsError() {
        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);

        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);
        assertTrue(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) r.data();
        assertTrue(String.valueOf(payload.get("message")).contains("confirm_required"));
    }

    @Test
    void missingIdReturnsError() {
        ObjectNode in = mapper.createObjectNode();
        in.put("confirm", true);
        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);
        assertTrue(r.isError());
    }

    @Test
    void notFoundWhenRowMissing() {
        when(service.findById(eq(7L), anyString())).thenReturn(Optional.empty());

        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        in.put("confirm", true);
        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);
        assertTrue(r.isError());
    }

    @Test
    void happyPathDeletesAndReports() {
        XhsCookie c = new XhsCookie();
        c.setId(7L);
        c.setPlatform("xhs_pc");
        c.setAccountLabel("old");
        when(service.findById(eq(7L), anyString())).thenReturn(Optional.of(c));
        when(service.delete(anyLong(), anyString())).thenReturn(true);

        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        in.put("confirm", true);
        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);
        assertFalse(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals(Boolean.TRUE, data.get("deleted"));
        assertEquals(7L, data.get("id"));
    }
}
