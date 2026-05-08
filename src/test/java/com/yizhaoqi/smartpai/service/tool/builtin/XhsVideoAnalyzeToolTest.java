package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.config.TikhubProperties;
import com.yizhaoqi.smartpai.service.LlmSyncCompletionService;
import com.yizhaoqi.smartpai.service.agent.AgentAssetService;
import com.yizhaoqi.smartpai.service.asr.AsrDispatcher;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.TikhubXhsService;
import com.yizhaoqi.smartpai.service.xhs.XhsPostLocatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * XhsVideoAnalyzeTool 端到端覆盖测试。
 *
 * <p>验证：
 *   - provider 未启用时返回 provider_disabled
 *   - 输入校验：url + noteId 都缺
 *   - TikHub 解析失败 (ApiException) 时透传 errorCode
 *   - 非视频笔记 → not_video_note
 *   - 视频时长超过 maxDurationSec → too_long
 *
 * 真实 happy path 涉及 ffmpeg / Whisper / DashScope，由 acceptance/probe-tikhub.sh 兜底。
 */
class XhsVideoAnalyzeToolTest {

    private TikhubXhsService tikhub;
    private AsrDispatcher asrDispatcher;
    private AgentAssetService asset;
    private LlmSyncCompletionService llm;
    private TikhubProperties props;
    private XhsPostLocatorService postLocatorService;
    private XhsVideoAnalyzeTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tikhub = mock(TikhubXhsService.class);
        asrDispatcher = mock(AsrDispatcher.class);
        asset = mock(AgentAssetService.class);
        llm = mock(LlmSyncCompletionService.class);
        postLocatorService = mock(XhsPostLocatorService.class);
        props = new TikhubProperties();
        props.setDefaultQuality("best");
        com.yizhaoqi.smartpai.config.AiVisionProperties vision = new com.yizhaoqi.smartpai.config.AiVisionProperties();
        vision.setEnabled(false); // 单元测试默认不走 vision，避免依赖 ffmpeg
        tool = new XhsVideoAnalyzeTool(tikhub, asrDispatcher, asset, llm, props, vision, postLocatorService);
    }

    private ToolContext ctx() {
        return ToolContext.builder().userId("u1").orgTag("acme").role("user").build();
    }

    @Test
    void schemaIsObject() {
        assertEquals("object", tool.inputSchema().get("type").asText());
        assertTrue(tool.inputSchema().get("properties").has("url"));
        assertTrue(tool.inputSchema().get("properties").has("noteId"));
    }

    @Test
    void providerDisabled_returnsProviderDisabled() throws Exception {
        when(tikhub.configured()).thenReturn(false);
        ObjectNode in = mapper.createObjectNode();
        in.put("url", "https://share/x");

        ToolResult r = tool.call(ctx(), in);
        assertTrue(r.isError());
        assertEquals("provider_disabled", r.meta().get("errorCode"));
    }

    @Test
    void missingUrlAndNoteId_returnsBadInput() throws Exception {
        when(tikhub.configured()).thenReturn(true);
        ToolResult r = tool.call(ctx(), mapper.createObjectNode());
        assertTrue(r.isError());
        assertEquals("bad_input", r.meta().get("errorCode"));
    }

    @Test
    void tikhubApiException_isPropagated() throws Exception {
        when(tikhub.configured()).thenReturn(true);
        when(tikhub.resolveAndFetchNote(anyString(), anyString(), anyString()))
                .thenThrow(new TikhubXhsService.ApiException("note_not_found", "找不到笔记"));
        ObjectNode in = mapper.createObjectNode();
        in.put("noteId", "abc");

        ToolResult r = tool.call(ctx(), in);
        assertTrue(r.isError());
        assertEquals("note_not_found", r.meta().get("errorCode"));
    }

    @Test
    void noteIdOnly_willResolveUrlFromLocator() throws Exception {
        when(tikhub.configured()).thenReturn(true);
        when(postLocatorService.findLinkByNoteId("abc"))
                .thenReturn(Optional.of("https://www.xiaohongshu.com/explore/abc?xsec_token=XT"));

        TikhubXhsService.NoteDetail empty = new TikhubXhsService.NoteDetail();
        empty.noteId = "abc";
        empty.title = "图文笔记";
        TikhubXhsService.ApiResult res = new TikhubXhsService.ApiResult(200, mapper.createObjectNode());
        injectNote(res, empty);
        when(tikhub.resolveAndFetchNote(anyString(), anyString(), anyString())).thenReturn(res);

        ObjectNode in = mapper.createObjectNode();
        in.put("noteId", "abc");

        ToolResult r = tool.call(ctx(), in);
        assertTrue(r.isError());
        assertEquals("not_video_note", r.meta().get("errorCode"));
        verify(postLocatorService).findLinkByNoteId("abc");
        verify(tikhub).resolveAndFetchNote("https://www.xiaohongshu.com/explore/abc?xsec_token=XT", "abc", "");
    }

    @Test
    void notVideoNote_returnsNotVideoNote() throws Exception {
        when(tikhub.configured()).thenReturn(true);
        TikhubXhsService.NoteDetail empty = new TikhubXhsService.NoteDetail();
        empty.noteId = "abc";
        empty.title = "图文笔记";
        // streams 为空
        TikhubXhsService.ApiResult res = new TikhubXhsService.ApiResult(200, mapper.createObjectNode());
        // 反射写 note：用真实 service 的对应字段
        injectNote(res, empty);
        when(tikhub.resolveAndFetchNote(anyString(), anyString(), anyString())).thenReturn(res);

        ObjectNode in = mapper.createObjectNode();
        in.put("noteId", "abc");

        ToolResult r = tool.call(ctx(), in);
        assertTrue(r.isError());
        assertEquals("not_video_note", r.meta().get("errorCode"));
    }

    @Test
    void durationTooLong_returnsTooLong() throws Exception {
        when(tikhub.configured()).thenReturn(true);
        TikhubXhsService.NoteDetail note = new TikhubXhsService.NoteDetail();
        note.noteId = "abc";
        note.title = "超长视频";
        note.durationSec = 1200;
        TikhubXhsService.VideoStream s = new TikhubXhsService.VideoStream();
        s.masterUrl = "http://cdn/x.mp4";
        s.height = 1920;
        note.streams = List.of(s);
        TikhubXhsService.ApiResult res = new TikhubXhsService.ApiResult(200, mapper.createObjectNode());
        injectNote(res, note);
        when(tikhub.resolveAndFetchNote(anyString(), anyString(), anyString())).thenReturn(res);
        when(tikhub.pickStream(any(), anyString())).thenReturn(Optional.of(s));

        ObjectNode in = mapper.createObjectNode();
        in.put("noteId", "abc");
        in.put("maxDurationSec", 600);

        ToolResult r = tool.call(ctx(), in);
        assertTrue(r.isError());
        assertEquals("too_long", r.meta().get("errorCode"));
    }

    /**
     * ApiResult.note 是 package-private 的私有字段，测试里通过反射写入。
     * 这避免给 production 代码加一个仅供测试的 setter。
     */
    private static void injectNote(TikhubXhsService.ApiResult res, TikhubXhsService.NoteDetail note) {
        try {
            java.lang.reflect.Field f = TikhubXhsService.ApiResult.class.getDeclaredField("note");
            f.setAccessible(true);
            f.set(res, note);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
