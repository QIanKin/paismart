package com.yizhaoqi.smartpai.service.asr;

import com.yizhaoqi.smartpai.config.AsrProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AsrDispatcher 路由 + 准入测试。
 */
class AsrDispatcherTest {

    @Test
    void routesToConfiguredProvider() throws Exception {
        AsrProperties props = new AsrProperties();
        props.setEnabled(true);
        props.setProvider(WhisperCompatibleAsrService.PROVIDER_ID);

        AsrService dashscope = mock(AsrService.class);
        when(dashscope.provider()).thenReturn(DashScopeAsrService.PROVIDER_ID);
        when(dashscope.configured()).thenReturn(false);

        AsrService whisper = mock(AsrService.class);
        when(whisper.provider()).thenReturn(WhisperCompatibleAsrService.PROVIDER_ID);
        when(whisper.configured()).thenReturn(true);
        AsrService.AsrResult fake = new AsrService.AsrResult("hi", List.of(), 0, "zh", null);
        when(whisper.transcribe(any(), any())).thenReturn(fake);

        AsrDispatcher dispatcher = new AsrDispatcher(props, List.of(dashscope, whisper));
        assertEquals(WhisperCompatibleAsrService.PROVIDER_ID, dispatcher.activeProvider());
        assertTrue(dispatcher.configured());

        Path p = Path.of("/tmp/x.mp3");
        AsrService.AsrResult r = dispatcher.transcribe(p, "http://m/x.mp3");
        assertEquals("hi", r.text());
        verify(whisper).transcribe(eq(p), eq("http://m/x.mp3"));
        // 注意：构造 dispatcher 时会调 dashscope.provider() 做注册，所以不能 verifyNoInteractions，
        // 仅断言 transcribe / configured 没被走过。
        verify(dashscope, never()).transcribe(any(), any());
        verify(dashscope, never()).configured();
    }

    @Test
    void disabledFlag_throwsProviderDisabled() {
        AsrProperties props = new AsrProperties();
        props.setEnabled(false);
        props.setProvider(WhisperCompatibleAsrService.PROVIDER_ID);

        AsrService whisper = mock(AsrService.class);
        when(whisper.provider()).thenReturn(WhisperCompatibleAsrService.PROVIDER_ID);

        AsrDispatcher dispatcher = new AsrDispatcher(props, List.of(whisper));
        AsrService.AsrException ex = assertThrows(AsrService.AsrException.class,
                () -> dispatcher.transcribe(null, "http://m/x.mp3"));
        assertEquals("provider_disabled", ex.code());
    }

    @Test
    void unknownProvider_throwsProviderDisabled() {
        AsrProperties props = new AsrProperties();
        props.setEnabled(true);
        props.setProvider("unknown-provider");

        AsrService whisper = mock(AsrService.class);
        when(whisper.provider()).thenReturn(WhisperCompatibleAsrService.PROVIDER_ID);

        AsrDispatcher dispatcher = new AsrDispatcher(props, List.of(whisper));
        assertFalse(dispatcher.configured());
        AsrService.AsrException ex = assertThrows(AsrService.AsrException.class,
                () -> dispatcher.transcribe(null, "http://m/x.mp3"));
        assertEquals("provider_disabled", ex.code());
    }

    @Test
    void notConfiguredImpl_throwsProviderDisabled() {
        AsrProperties props = new AsrProperties();
        props.setEnabled(true);
        props.setProvider(WhisperCompatibleAsrService.PROVIDER_ID);

        AsrService whisper = mock(AsrService.class);
        when(whisper.provider()).thenReturn(WhisperCompatibleAsrService.PROVIDER_ID);
        when(whisper.configured()).thenReturn(false);

        AsrDispatcher dispatcher = new AsrDispatcher(props, List.of(whisper));
        assertFalse(dispatcher.configured());
        AsrService.AsrException ex = assertThrows(AsrService.AsrException.class,
                () -> dispatcher.transcribe(null, "http://m/x.mp3"));
        assertEquals("provider_disabled", ex.code());
    }
}
