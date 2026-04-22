package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.model.xhs.XhsLoginSession;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsLoginSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class XhsQrLoginStartToolTest {

    private XhsLoginSessionService loginService;
    private XhsQrLoginStartTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        loginService = mock(XhsLoginSessionService.class);
        tool = new XhsQrLoginStartTool(loginService);
    }

    @Test
    void nonAdminDenied() {
        PermissionResult r = tool.checkPermission(
                ToolContext.builder().userId("u1").orgTag("acme").role("user").build(),
                mapper.createObjectNode());
        assertInstanceOf(PermissionResult.Deny.class, r);
    }

    @Test
    void missingUserIdDenied() {
        PermissionResult r = tool.checkPermission(
                ToolContext.builder().orgTag("acme").role("admin").build(),
                mapper.createObjectNode());
        assertInstanceOf(PermissionResult.Deny.class, r);
    }

    @Test
    void happyPathReturnsSessionId() {
        XhsLoginSession s = new XhsLoginSession();
        s.setSessionId("abc-123");
        s.setOwnerOrgTag("acme");
        s.setCreatedByUserId("u1");
        s.setPlatforms("xhs_pc,xhs_creator");
        s.setStatus(XhsLoginSession.Status.PENDING);
        s.setStartedAt(LocalDateTime.of(2026, 4, 21, 10, 0));
        s.setExpiresAt(LocalDateTime.of(2026, 4, 21, 10, 5));
        when(loginService.start(eq("acme"), eq("u1"), any()))
                .thenReturn(s);

        ObjectNode in = mapper.createObjectNode();
        ArrayNode arr = in.putArray("platforms");
        arr.add("xhs_pc");
        arr.add("xhs_creator");
        arr.add("bogus"); // should be filtered

        ToolContext ctx = ToolContext.builder().userId("u1").orgTag("acme").role("admin").build();
        ToolResult r = tool.call(ctx, in);

        assertFalse(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals("abc-123", data.get("sessionId"));
        assertEquals("PENDING", data.get("status"));
        assertTrue(String.valueOf(data.get("wsHint")).contains("abc-123"));
    }

    @Test
    void invalidArgsFromServicePropagated() {
        when(loginService.start(anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("platforms 不能为空"));

        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(),
                mapper.createObjectNode());
        assertTrue(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) r.data();
        assertTrue(String.valueOf(payload.get("message")).contains("bad_request"));
    }

    @Test
    void emptyPlatformsArrayPassesNullToService() {
        XhsLoginSession s = new XhsLoginSession();
        s.setSessionId("x");
        s.setStatus(XhsLoginSession.Status.PENDING);
        s.setStartedAt(LocalDateTime.now());
        s.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        s.setPlatforms("xhs_pc");
        when(loginService.start(eq("acme"), eq("u1"), eq((List<String>) null)))
                .thenReturn(s);

        ToolResult r = tool.call(
                ToolContext.builder().userId("u1").orgTag("acme").role("admin").build(),
                mapper.createObjectNode());
        assertFalse(r.isError());
    }
}
