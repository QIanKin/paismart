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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class XhsCookieListToolTest {

    private XhsCookieService service;
    private XhsCookieListTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        service = mock(XhsCookieService.class);
        tool = new XhsCookieListTool(service);
    }

    @Test
    void schemaIsObjectWithEnumFilters() {
        assertEquals("object", tool.inputSchema().get("type").asText());
        assertTrue(tool.inputSchema().has("properties"));
        assertTrue(tool.inputSchema().get("properties").has("platform"));
        assertTrue(tool.inputSchema().get("properties").has("status"));
    }

    @Test
    void nonAdminIsDenied() {
        PermissionResult r = tool.checkPermission(
                ToolContext.builder().userId("u1").orgTag("acme").role("user").build(),
                mapper.createObjectNode());
        assertInstanceOf(PermissionResult.Deny.class, r);
    }

    @Test
    void missingOrgIsDenied() {
        PermissionResult r = tool.checkPermission(
                ToolContext.builder().userId("u1").role("admin").build(),
                mapper.createObjectNode());
        assertInstanceOf(PermissionResult.Deny.class, r);
    }

    @Test
    void adminSeesAggregatedBuckets() throws Exception {
        XhsCookie a = cookie(1L, "xhs_pc", XhsCookie.Status.ACTIVE);
        XhsCookie b = cookie(2L, "xhs_pc", XhsCookie.Status.EXPIRED);
        XhsCookie c = cookie(3L, "xhs_spotlight", XhsCookie.Status.ACTIVE);
        when(service.list("acme")).thenReturn(List.of(a, b, c));

        ToolContext ctx = ToolContext.builder().userId("u1").orgTag("acme").role("admin").build();
        ToolResult result = tool.call(ctx, mapper.createObjectNode());

        assertFalse(result.isError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertEquals(3, data.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Integer> byStatus = (Map<String, Integer>) data.get("byStatus");
        assertEquals(2, byStatus.get("ACTIVE"));
        assertEquals(1, byStatus.get("EXPIRED"));
    }

    @Test
    void platformFilterNarrowsResults() throws Exception {
        XhsCookie a = cookie(1L, "xhs_pc", XhsCookie.Status.ACTIVE);
        XhsCookie c = cookie(3L, "xhs_spotlight", XhsCookie.Status.ACTIVE);
        when(service.list("acme")).thenReturn(List.of(a, c));

        ObjectNode in = mapper.createObjectNode();
        in.put("platform", "xhs_spotlight");
        ToolContext ctx = ToolContext.builder().userId("u1").orgTag("acme").role("admin").build();
        ToolResult result = tool.call(ctx, in);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        assertEquals(1, data.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("cookies");
        assertEquals("xhs_spotlight", rows.get(0).get("platform"));
    }

    private static XhsCookie cookie(Long id, String platform, XhsCookie.Status s) {
        XhsCookie c = new XhsCookie();
        c.setId(id);
        c.setOwnerOrgTag("acme");
        c.setPlatform(platform);
        c.setStatus(s);
        c.setPriority(10);
        c.setSuccessCount(0);
        c.setFailCount(0);
        c.setAccountLabel("test-" + id);
        c.setCookieKeys("a1,web_session");
        return c;
    }
}
