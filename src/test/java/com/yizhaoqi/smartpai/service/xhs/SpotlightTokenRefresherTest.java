package com.yizhaoqi.smartpai.service.xhs;

import com.yizhaoqi.smartpai.model.xhs.XhsCookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SpotlightTokenRefresher} 的非-HTTP 路径覆盖测试。
 * HTTP 分支（remote_error / ok）需要外部 HttpClient 桩，放到集成测试里跑，不在这里断言。
 */
class SpotlightTokenRefresherTest {

    private XhsCookieService cookieService;
    private CookieCipher cipher;
    private SpotlightTokenRefresher refresher;

    @BeforeEach
    void setUp() throws Exception {
        cookieService = mock(XhsCookieService.class);
        cipher = mock(CookieCipher.class);
        refresher = new SpotlightTokenRefresher(cookieService, cipher);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = SpotlightTokenRefresher.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(refresher, value);
    }

    @Test
    void configMissingWhenAppIdBlank() throws Exception {
        setField("appId", "");
        setField("appSecret", "secret");
        SpotlightTokenRefresher.Result r = refresher.refresh(1L, "acme");
        assertFalse(r.ok());
        assertEquals("config_missing", r.errorType());
    }

    @Test
    void notFoundWhenRowMissing() throws Exception {
        setField("appId", "100");
        setField("appSecret", "secret");
        when(cookieService.findById(anyLong(), anyString())).thenReturn(Optional.empty());
        SpotlightTokenRefresher.Result r = refresher.refresh(1L, "acme");
        assertFalse(r.ok());
        assertEquals("not_found", r.errorType());
    }

    @Test
    void wrongPlatformWhenNotSpotlight() throws Exception {
        setField("appId", "100");
        setField("appSecret", "secret");
        XhsCookie c = new XhsCookie();
        c.setId(1L);
        c.setPlatform("xhs_pc");
        when(cookieService.findById(anyLong(), anyString())).thenReturn(Optional.of(c));
        SpotlightTokenRefresher.Result r = refresher.refresh(1L, "acme");
        assertFalse(r.ok());
        assertEquals("wrong_platform", r.errorType());
    }

    @Test
    void missingRefreshTokenWhenJsonLacksField() throws Exception {
        setField("appId", "100");
        setField("appSecret", "secret");
        XhsCookie c = new XhsCookie();
        c.setId(1L);
        c.setPlatform("xhs_spotlight");
        when(cookieService.findById(anyLong(), anyString())).thenReturn(Optional.of(c));
        when(cookieService.decryptFor(anyLong(), anyString()))
                .thenReturn(Optional.of("{\"accessToken\":\"x\",\"advertiserId\":\"a\"}"));

        SpotlightTokenRefresher.Result r = refresher.refresh(1L, "acme");
        assertFalse(r.ok());
        assertEquals("missing_refresh_token", r.errorType());
    }

    @Test
    void internalWhenJsonUnparseable() throws Exception {
        setField("appId", "100");
        setField("appSecret", "secret");
        XhsCookie c = new XhsCookie();
        c.setId(1L);
        c.setPlatform("xhs_spotlight");
        when(cookieService.findById(anyLong(), anyString())).thenReturn(Optional.of(c));
        when(cookieService.decryptFor(anyLong(), anyString()))
                .thenReturn(Optional.of("{not json"));
        SpotlightTokenRefresher.Result r = refresher.refresh(1L, "acme");
        assertFalse(r.ok());
        assertEquals("internal", r.errorType());
    }
}
