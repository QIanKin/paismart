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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class XhsCookieUpdateToolTest {

    private XhsCookieService service;
    private XhsCookieUpdateTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(XhsCookieService.class);
        tool = new XhsCookieUpdateTool(service);
    }

    @Test
    void nonAdminAlsoAllowed_godmode() {
        // Phase 4c: role=user 也允许。
        PermissionResult r = tool.checkPermission(
                ToolContext.builder().userId("u1").orgTag("acme").role("user").build(),
                mapper.createObjectNode());
        assertInstanceOf(PermissionResult.Allow.class, r);
    }

    @Test
    void missingIdErrors() {
        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(),
                mapper.createObjectNode());
        assertTrue(r.isError());
    }

    @Test
    void notFoundPropagatedAsError() {
        when(service.update(anyLong(), eq("acme"), any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        in.put("status", "DISABLED");

        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);
        assertTrue(r.isError());
    }

    @Test
    void illegalStatusReturnsError() {
        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        in.put("status", "WHATEVER");

        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);
        assertTrue(r.isError());
    }

    @Test
    void happyPathReturnsUpdatedRow() {
        XhsCookie updated = new XhsCookie();
        updated.setId(7L);
        updated.setPlatform("xhs_spotlight");
        updated.setAccountLabel("ZFC");
        updated.setStatus(XhsCookie.Status.ACTIVE);
        updated.setPriority(20);
        updated.setCookiePreview("***");
        when(service.update(eq(7L), eq("acme"), anyString(), any(), any(), any(), eq(XhsCookie.Status.ACTIVE)))
                .thenReturn(Optional.of(updated));

        ObjectNode in = mapper.createObjectNode();
        in.put("id", 7L);
        in.put("cookie", "{\"accessToken\":\"x\"}");
        in.put("status", "ACTIVE");

        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(), in);
        assertFalse(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals(7L, data.get("id"));
        assertEquals("ACTIVE", data.get("status"));
    }
}
