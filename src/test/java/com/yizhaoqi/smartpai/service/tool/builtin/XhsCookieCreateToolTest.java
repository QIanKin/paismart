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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class XhsCookieCreateToolTest {

    private XhsCookieService service;
    private XhsCookieCreateTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(XhsCookieService.class);
        tool = new XhsCookieCreateTool(service);
    }

    @Test
    void schemaRequiresPlatformAndCookie() {
        assertEquals("object", tool.inputSchema().get("type").asText());
        var required = tool.inputSchema().get("required");
        assertNotNull(required);
        boolean hasPlatform = false, hasCookie = false;
        for (var n : required) {
            if ("platform".equals(n.asText())) hasPlatform = true;
            if ("cookie".equals(n.asText())) hasCookie = true;
        }
        assertTrue(hasPlatform && hasCookie);
    }

    @Test
    void isDestructiveAndWrite() {
        assertTrue(tool.isDestructive(mapper.createObjectNode()));
        assertFalse(tool.isReadOnly(mapper.createObjectNode()));
    }

    @Test
    void nonAdminAlsoAllowed_godmode() {
        // Phase 4c: Agent 对所有登录用户开放；role=user 不再被拒。
        PermissionResult r = tool.checkPermission(
                ToolContext.builder().userId("u1").orgTag("acme").role("user").build(),
                mapper.createObjectNode());
        assertInstanceOf(PermissionResult.Allow.class, r);
    }

    @Test
    void missingOrgTagStillDenied() {
        // orgTag 是数据隔离保障，不是 role 权限 —— 必须仍拒绝。
        PermissionResult r = tool.checkPermission(
                ToolContext.builder().userId("u1").orgTag(null).role("admin").build(),
                mapper.createObjectNode());
        assertInstanceOf(PermissionResult.Deny.class, r);
    }

    @Test
    void missingFieldsReturnsError() {
        ObjectNode in = mapper.createObjectNode();
        ToolContext ctx = ToolContext.builder().userId("u1").orgTag("acme").role("admin").build();
        ToolResult r = tool.call(ctx, in);
        assertTrue(r.isError());
    }

    @Test
    void invalidCookieTranslatedToErrorResult() {
        when(service.create(anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenThrow(new IllegalArgumentException("Cookie 缺少必要字段: a1/web_session/webId"));

        ObjectNode in = mapper.createObjectNode();
        in.put("platform", "xhs_pc");
        in.put("cookie", "webId=x");
        ToolContext ctx = ToolContext.builder().userId("u1").orgTag("acme").role("admin").build();
        ToolResult r = tool.call(ctx, in);

        assertTrue(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) r.data();
        // Phase 4b: errorCode 走 data.code + meta.errorCode，summary/message 是纯人话
        assertEquals("cookie_invalid", payload.get("code"));
        assertEquals("cookie_invalid", r.meta().get("errorCode"));
        assertTrue(String.valueOf(payload.get("message")).contains("cookie 内容不合法"),
                "summary 应为中文人话，不再带 code 前缀");
        assertFalse(r.summary().startsWith("cookie_invalid:"),
                "Phase 4b: summary 不应再有 code 前缀");
    }

    @Test
    void happyPathReturnsSavedRow() {
        XhsCookie saved = new XhsCookie();
        saved.setId(42L);
        saved.setOwnerOrgTag("acme");
        saved.setPlatform("xhs_pc");
        saved.setAccountLabel("test");
        saved.setCookiePreview("a1=xxx...====");
        saved.setCookieKeys("a1,web_session,webId");
        saved.setStatus(XhsCookie.Status.ACTIVE);
        saved.setPriority(10);
        saved.setSource(XhsCookie.Source.MANUAL);
        when(service.create(anyString(), anyString(), anyString(), any(), any(), any(), anyString()))
                .thenReturn(saved);

        ObjectNode in = mapper.createObjectNode();
        in.put("platform", "xhs_pc");
        in.put("cookie", "a1=x; web_session=y; webId=z");
        in.put("accountLabel", "test");
        ToolContext ctx = ToolContext.builder().userId("u1").orgTag("acme").role("admin").build();
        ToolResult r = tool.call(ctx, in);

        assertFalse(r.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        assertEquals(42L, data.get("id"));
        assertEquals("xhs_pc", data.get("platform"));
    }
}
