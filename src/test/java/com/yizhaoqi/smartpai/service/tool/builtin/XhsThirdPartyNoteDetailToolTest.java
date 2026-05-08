package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.ThirdPartyXhsService;
import com.yizhaoqi.smartpai.service.xhs.XhsPostLocatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class XhsThirdPartyNoteDetailToolTest {

    private ThirdPartyXhsService thirdPartyXhsService;
    private XhsPostLocatorService postLocatorService;
    private XhsThirdPartyNoteDetailTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        thirdPartyXhsService = mock(ThirdPartyXhsService.class);
        postLocatorService = mock(XhsPostLocatorService.class);
        tool = new XhsThirdPartyNoteDetailTool(thirdPartyXhsService, postLocatorService);
    }

    private ToolContext ctx() {
        return ToolContext.builder().userId("u1").orgTag("acme").role("user").build();
    }

    @Test
    void noteIdOnly_willResolveUrlFromLocator() throws Exception {
        when(thirdPartyXhsService.configured()).thenReturn(true);
        when(postLocatorService.findLinkByNoteId("note-1"))
                .thenReturn(Optional.of("https://www.xiaohongshu.com/explore/note-1?xsec_token=XT-1"));

        Map<String, Object> note = new LinkedHashMap<>();
        note.put("title", "测试笔记");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("note", note);

        when(thirdPartyXhsService.noteDetail(
                "note-1",
                "https://www.xiaohongshu.com/explore/note-1?xsec_token=XT-1",
                false
        )).thenReturn(new ThirdPartyXhsService.FetchResult(true, 200, mapper.createObjectNode(), data));

        ObjectNode in = mapper.createObjectNode();
        in.put("noteId", "note-1");

        ToolResult r = tool.call(ctx(), in);

        assertTrue(!r.isError());
        assertEquals("笔记详情：测试笔记", r.summary());
        verify(postLocatorService).findLinkByNoteId("note-1");
        verify(thirdPartyXhsService).noteDetail(
                "note-1",
                "https://www.xiaohongshu.com/explore/note-1?xsec_token=XT-1",
                false
        );
    }
}
