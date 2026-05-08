package com.yizhaoqi.smartpai.service.asr;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.config.AsrProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用本地 HttpServer 模拟两套 Whisper 兼容 schema：
 * <ul>
 *   <li>{@code asr-webservice}：POST /asr?task=transcribe&language=zh，multipart audio_file</li>
 *   <li>{@code openai-compat}：POST /v1/audio/transcriptions，multipart file+model+language</li>
 * </ul>
 * 验证：mode 切换、multipart 字段名正确、JSON 解析、超大文件拒绝、provider 不匹配时 disabled。
 */
class WhisperCompatibleAsrServiceTest {

    private HttpServer server;
    private AsrProperties props;
    private WhisperCompatibleAsrService service;
    private Path tmpAudio;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        props = new AsrProperties();
        props.setEnabled(true);
        props.setProvider(WhisperCompatibleAsrService.PROVIDER_ID);
        AsrProperties.Whisper w = props.getWhisper();
        w.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        w.setHttpTimeoutSeconds(10);
        w.setLanguage("zh");
        w.setTask("transcribe");
        w.setOutput("json");
        w.setMode("asr-webservice");
        w.setPath("/asr");
        service = new WhisperCompatibleAsrService(props);

        tmpAudio = Files.createTempFile("whisper-test-", ".mp3");
        Files.write(tmpAudio, new byte[]{0x49, 0x44, 0x33, 0x04, 0x00}); // 5 bytes 占位
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.stop(0);
        if (tmpAudio != null) Files.deleteIfExists(tmpAudio);
    }

    @Test
    void disabled_whenProviderMismatch() {
        props.setProvider(DashScopeAsrService.PROVIDER_ID);
        WhisperCompatibleAsrService s = new WhisperCompatibleAsrService(props);
        assertFalse(s.configured());
    }

    @Test
    void disabled_whenBaseUrlBlank() {
        props.getWhisper().setBaseUrl("");
        WhisperCompatibleAsrService s = new WhisperCompatibleAsrService(props);
        assertFalse(s.configured());
    }

    @Test
    void enabled_whenProviderAndUrlSet() {
        assertTrue(service.configured());
    }

    @Test
    void asrWebservice_happyPath() throws Exception {
        AtomicReference<String> queryRef = new AtomicReference<>();
        AtomicReference<String> bodyRef = new AtomicReference<>();
        server.createContext("/asr", ex -> {
            queryRef.set(ex.getRequestURI().getQuery());
            bodyRef.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String resp = "{\"text\":\"你好测试\",\"segments\":[{\"start\":0.0,\"end\":1.5,\"text\":\"你好测试\"}],\"language\":\"zh\"}";
            sendJson(ex, 200, resp);
        });

        AsrService.AsrResult r = service.transcribe(tmpAudio, null);
        assertEquals("你好测试", r.text());
        assertEquals(1, r.sentences().size());
        assertEquals(1500L, r.sentences().get(0).endTime());
        assertEquals("zh", r.detectedLanguages());

        Map<String, String> qs = parseQuery(queryRef.get());
        assertEquals("transcribe", qs.get("task"));
        assertEquals("zh", qs.get("language"));
        assertEquals("json", qs.get("output"));

        // multipart 必须包含 audio_file 字段（asr-webservice schema）
        assertTrue(bodyRef.get().contains("name=\"audio_file\""),
                "asr-webservice 模式 multipart 字段名应为 audio_file");
    }

    @Test
    void openaiCompat_happyPath() throws Exception {
        props.getWhisper().setMode("openai-compat");
        props.getWhisper().setPath("/v1/audio/transcriptions");
        props.getWhisper().setModel("Systran/faster-whisper-base");
        WhisperCompatibleAsrService s = new WhisperCompatibleAsrService(props);

        AtomicReference<String> bodyRef = new AtomicReference<>();
        server.createContext("/v1/audio/transcriptions", ex -> {
            bodyRef.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            sendJson(ex, 200, "{\"text\":\"openai路径ok\"}");
        });

        AsrService.AsrResult r = s.transcribe(tmpAudio, null);
        assertEquals("openai路径ok", r.text());
        // multipart 必须包含 file 字段（openai schema），同时带 model
        assertTrue(bodyRef.get().contains("name=\"file\""),
                "openai-compat 模式 multipart 字段名应为 file");
        assertTrue(bodyRef.get().contains("Systran/faster-whisper-base"));
        assertTrue(bodyRef.get().contains("name=\"model\""));
    }

    @Test
    void server5xx_returnsSubmitFailed() {
        server.createContext("/asr", ex -> sendJson(ex, 500, "{\"error\":\"down\"}"));
        AsrService.AsrException ex = assertThrows(AsrService.AsrException.class,
                () -> service.transcribe(tmpAudio, null));
        assertEquals("submit_failed", ex.code());
    }

    @Test
    void responseMissingText_returnsAsrFailed() {
        server.createContext("/asr", ex -> sendJson(ex, 200, "{\"segments\":[]}"));
        AsrService.AsrException ex = assertThrows(AsrService.AsrException.class,
                () -> service.transcribe(tmpAudio, null));
        assertEquals("asr_failed", ex.code());
    }

    @Test
    void invalidJson_returnsParseError() {
        server.createContext("/asr", ex -> sendJson(ex, 200, "<<not json>>"));
        AsrService.AsrException ex = assertThrows(AsrService.AsrException.class,
                () -> service.transcribe(tmpAudio, null));
        assertEquals("parse_error", ex.code());
    }

    @Test
    void missingLocalFile_returnsBadInput() {
        AsrService.AsrException ex = assertThrows(AsrService.AsrException.class,
                () -> service.transcribe(Path.of("/no/such/file.mp3"), null));
        assertEquals("bad_input", ex.code());
    }

    @Test
    void exceedSizeLimit_returnsBadInput() throws Exception {
        props.getWhisper().setMaxAudioBytes(2L); // 上限 2B，文件 5B 必超
        WhisperCompatibleAsrService s = new WhisperCompatibleAsrService(props);
        AsrService.AsrException ex = assertThrows(AsrService.AsrException.class,
                () -> s.transcribe(tmpAudio, null));
        assertEquals("bad_input", ex.code());
    }

    @Test
    void localMode_configuredWhenScriptFileExists() throws Exception {
        Path fake = Files.createTempFile("fake-whisper-", ".py");
        try {
            props.getWhisper().setMode("local-faster-whisper");
            props.getWhisper().setLocalScriptPath(fake.toString());
            props.getWhisper().setBaseUrl("");
            WhisperCompatibleAsrService s = new WhisperCompatibleAsrService(props);
            assertTrue(s.configured());
        } finally {
            Files.deleteIfExists(fake);
        }
    }

    @Test
    void localMode_notConfiguredWhenScriptMissing() {
        props.getWhisper().setMode("local-faster-whisper");
        props.getWhisper().setLocalScriptPath("/no/such/local_whisper_transcribe.py");
        props.getWhisper().setBaseUrl("");
        WhisperCompatibleAsrService s = new WhisperCompatibleAsrService(props);
        assertFalse(s.configured());
    }

    private static void sendJson(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isBlank()) return m;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
