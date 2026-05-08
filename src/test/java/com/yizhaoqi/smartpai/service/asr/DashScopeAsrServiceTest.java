package com.yizhaoqi.smartpai.service.asr;

import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.config.AsrProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用本地 HttpServer mock DashScope 三个端点：
 *   POST /api/v1/services/audio/asr/transcription   提交任务
 *   GET  /api/v1/tasks/{task_id}                    轮询
 *   GET  /transcripts/xxx.json                      最终结果
 *
 * 覆盖：成功 → SUCCEEDED，第一次轮询 RUNNING 第二次 SUCCEEDED；以及 FAILED 终态。
 */
class DashScopeAsrServiceTest {

    private HttpServer server;
    private AsrProperties props;
    private DashScopeAsrService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        props = new AsrProperties();
        props.setEnabled(true);
        props.setProvider(DashScopeAsrService.PROVIDER_ID);
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setApiKey("dashscope-key");
        props.setPollIntervalSeconds(1);
        props.setMaxPollSeconds(8);
        props.setHttpTimeoutSeconds(5);
        service = new DashScopeAsrService(props, "fallback-key");
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void notConfigured_whenDisabled() {
        props.setEnabled(false);
        DashScopeAsrService s = new DashScopeAsrService(props, "fallback-key");
        assertFalse(s.configured());
    }

    @Test
    void notConfigured_whenProviderMismatch() {
        props.setProvider(WhisperCompatibleAsrService.PROVIDER_ID);
        DashScopeAsrService s = new DashScopeAsrService(props, "fallback-key");
        assertFalse(s.configured());
    }

    @Test
    void configured_fallsBackToEmbeddingKey_whenAsrKeyBlank() {
        props.setApiKey("");
        DashScopeAsrService s = new DashScopeAsrService(props, "fallback-key");
        assertTrue(s.configured());
    }

    @Test
    void transcribe_happyPath_pollOnceThenSucceeded() throws Exception {
        AtomicInteger pollCnt = new AtomicInteger();
        server.createContext("/api/v1/services/audio/asr/transcription", ex -> {
            sendJson(ex, 200, "{\"output\":{\"task_id\":\"T-1\",\"task_status\":\"PENDING\"}}");
        });
        server.createContext("/api/v1/tasks/T-1", ex -> {
            int n = pollCnt.incrementAndGet();
            String body = n < 2
                    ? "{\"output\":{\"task_id\":\"T-1\",\"task_status\":\"RUNNING\"}}"
                    : ("{\"output\":{\"task_id\":\"T-1\",\"task_status\":\"SUCCEEDED\","
                       + "\"results\":[{\"transcription_url\":\"http://127.0.0.1:" + server.getAddress().getPort() + "/transcripts/x.json\","
                       + "\"subtask_status\":\"SUCCEEDED\"}]}}");
            sendJson(ex, 200, body);
        });
        server.createContext("/transcripts/x.json", ex -> {
            String body = """
                    {
                      "file_url":"https://x.mp3",
                      "properties":{"original_duration_in_milliseconds": 12345},
                      "transcripts":[{
                        "channel_id":0,
                        "content_duration_in_milliseconds":12345,
                        "text":"你好世界",
                        "sentences":[{"begin_time":0,"end_time":1000,"text":"你好世界"}]
                      }]
                    }
                    """;
            sendJson(ex, 200, body);
        });

        AsrService.AsrResult r = service.transcribe(null, "http://example.com/a.mp3");
        assertEquals("你好世界", r.text());
        assertEquals(1, r.sentences().size());
        assertEquals(12345L, r.durationMs());
        assertTrue(pollCnt.get() >= 2);
    }

    @Test
    void transcribe_failsWhenSubmitReturns5xx() throws Exception {
        server.createContext("/api/v1/services/audio/asr/transcription", ex -> {
            sendJson(ex, 500, "{\"error\":\"down\"}");
        });
        AsrService.AsrException ex = assertThrows(AsrService.AsrException.class,
                () -> service.transcribe(null, "http://example.com/a.mp3"));
        assertEquals("submit_failed", ex.code());
    }

    @Test
    void transcribe_failsWhenTaskFailed() throws Exception {
        server.createContext("/api/v1/services/audio/asr/transcription", ex -> {
            sendJson(ex, 200, "{\"output\":{\"task_id\":\"T-2\",\"task_status\":\"PENDING\"}}");
        });
        server.createContext("/api/v1/tasks/T-2", ex -> {
            sendJson(ex, 200, "{\"output\":{\"task_id\":\"T-2\",\"task_status\":\"FAILED\",\"message\":\"boom\"}}");
        });
        AsrService.AsrException ex = assertThrows(AsrService.AsrException.class,
                () -> service.transcribe(null, "http://example.com/a.mp3"));
        assertEquals("asr_failed", ex.code());
    }

    @Test
    void transcribe_timesOutWhenAlwaysPending() throws Exception {
        props.setMaxPollSeconds(2);
        DashScopeAsrService s = new DashScopeAsrService(props, "fallback-key");
        server.createContext("/api/v1/services/audio/asr/transcription", ex -> {
            sendJson(ex, 200, "{\"output\":{\"task_id\":\"T-3\",\"task_status\":\"PENDING\"}}");
        });
        server.createContext("/api/v1/tasks/T-3", ex -> {
            sendJson(ex, 200, "{\"output\":{\"task_id\":\"T-3\",\"task_status\":\"RUNNING\"}}");
        });
        AsrService.AsrException ex = assertThrows(AsrService.AsrException.class,
                () -> s.transcribe(null, "http://example.com/a.mp3"));
        assertEquals("asr_timeout", ex.code());
    }

    @Test
    void transcribe_failsWhenPublicUrlBlank() {
        AsrService.AsrException ex = assertThrows(AsrService.AsrException.class,
                () -> service.transcribe(null, ""));
        assertEquals("bad_input", ex.code());
    }

    private static void sendJson(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
