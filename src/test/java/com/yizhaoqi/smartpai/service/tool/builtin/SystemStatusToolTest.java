package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import com.yizhaoqi.smartpai.service.skill.SkillRegistry;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolRegistry;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsCookieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemStatusToolTest {

    private XhsCookieService cookies;
    private SkillRegistry skills;
    private ToolRegistry tools;
    private SystemStatusTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        cookies = mock(XhsCookieService.class);
        skills = mock(SkillRegistry.class);
        tools = mock(ToolRegistry.class);
        tool = new SystemStatusTool(cookies, skills, tools);
        // 直接注入 @Value 绑定的字段（绕过 Spring 容器）
        ReflectionTestUtils.setField(tool, "cdpEndpoint", "http://host.docker.internal:9222");
        ReflectionTestUtils.setField(tool, "spotlightSeedEnabled", false);
        ReflectionTestUtils.setField(tool, "spotlightAdvertiserId", "");
    }

    @Test
    void nonAdminIsDenied() {
        PermissionResult r = tool.checkPermission(
                ToolContext.builder().userId("u").orgTag("acme").role("user").build(),
                mapper.createObjectNode());
        assertInstanceOf(PermissionResult.Deny.class, r);
    }

    @Test
    void reportsMissingSpotlightWhenNoActive() throws Exception {
        when(cookies.list("acme")).thenReturn(Collections.emptyList());
        when(cookies.list("default")).thenReturn(Collections.emptyList());
        when(skills.listVisible("acme")).thenReturn(Collections.emptyList());
        when(tools.all()).thenReturn(Collections.<Tool>emptyList());

        ToolContext ctx = ToolContext.builder().userId("u").orgTag("acme").role("admin").build();
        ToolResult r = tool.call(ctx, mapper.createObjectNode());
        assertFalse(r.isError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> missing = (List<Map<String, String>>) data.get("missingConfig");
        assertTrue(missing.stream().anyMatch(m -> "xhs_spotlight.credential".equals(m.get("code"))),
                "应该报出 spotlight 缺失");
        assertTrue(missing.stream().anyMatch(m -> "xhs_web.shared-pool".equals(m.get("code"))),
                "共享池空应该也要报");
    }

    @Test
    void spotlightActivePresentSuppressesIssue() throws Exception {
        XhsCookie spotlight = cookie(1L, "xhs_spotlight", XhsCookie.Status.ACTIVE);
        XhsCookie sharedPc = cookie(9L, "xhs_pc", XhsCookie.Status.ACTIVE);
        when(cookies.list("acme")).thenReturn(List.of(spotlight));
        when(cookies.list("default")).thenReturn(List.of(sharedPc));
        when(skills.listVisible("acme")).thenReturn(Collections.emptyList());
        when(tools.all()).thenReturn(Collections.<Tool>emptyList());

        ToolContext ctx = ToolContext.builder().userId("u").orgTag("acme").role("admin").build();
        ToolResult r = tool.call(ctx, mapper.createObjectNode());
        assertFalse(r.isError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> missing = (List<Map<String, String>>) data.get("missingConfig");
        assertTrue(missing.stream().noneMatch(m -> "xhs_spotlight.credential".equals(m.get("code"))));
        assertTrue(missing.stream().noneMatch(m -> "xhs_web.shared-pool".equals(m.get("code"))));
    }

    @Test
    void cdpEndpointBlankIsFlagged() throws Exception {
        ReflectionTestUtils.setField(tool, "cdpEndpoint", "");
        when(cookies.list("acme")).thenReturn(Collections.emptyList());
        when(cookies.list("default")).thenReturn(Collections.emptyList());
        when(skills.listVisible("acme")).thenReturn(Collections.emptyList());
        when(tools.all()).thenReturn(Collections.<Tool>emptyList());

        ToolContext ctx = ToolContext.builder().userId("u").orgTag("acme").role("admin").build();
        ToolResult r = tool.call(ctx, mapper.createObjectNode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.data();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> missing = (List<Map<String, String>>) data.get("missingConfig");
        assertTrue(missing.stream().anyMatch(m -> "browser.cdp-endpoint".equals(m.get("code"))));
    }

    private static XhsCookie cookie(Long id, String platform, XhsCookie.Status s) {
        XhsCookie c = new XhsCookie();
        c.setId(id);
        c.setOwnerOrgTag(platform.equals("xhs_pc") && id == 9L ? "default" : "acme");
        c.setPlatform(platform);
        c.setStatus(s);
        return c;
    }
}
