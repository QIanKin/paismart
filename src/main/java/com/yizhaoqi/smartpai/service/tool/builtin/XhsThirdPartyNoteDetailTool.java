package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.ThirdPartyXhsService;
import com.yizhaoqi.smartpai.service.xhs.XhsPostLocatorService;
import org.springframework.stereotype.Component;

/**
 * 通过第三方 provider 获取小红书笔记详情，避免直接用 cookie 撞 Web API。
 */
@Component
public class XhsThirdPartyNoteDetailTool implements Tool {

    private final ThirdPartyXhsService service;
    private final XhsPostLocatorService postLocatorService;
    private final JsonNode schema;

    public XhsThirdPartyNoteDetailTool(ThirdPartyXhsService service,
                                       XhsPostLocatorService postLocatorService) {
        this.service = service;
        this.postLocatorService = postLocatorService;
        this.schema = ToolInputSchemas.object()
                .stringProp("noteId", "小红书笔记 ID；和 url 至少填一个", false)
                .stringProp("url", "小红书笔记链接 / 分享链接；和 noteId 至少填一个", false)
                .booleanProp("includeRaw", "是否同时返回 TikHub 原始响应，默认 false", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_third_party_note_detail"; }

    @Override public String description() {
        return "通过第三方服务（默认 TikHub）获取小红书公开笔记详情：标题/描述/作者/互动数据/IP 属地/视频流元数据。"
                + "替代 cookie 直连抓取，反爬零风险；视频笔记还会返回无水印 mp4 直链。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        if (!service.configured()) {
            return ToolResult.error("provider_not_configured",
                    "第三方小红书 provider 未配置：请设置 XHS_THIRD_PARTY_ENABLED=true 与 TikHub key");
        }
        String noteId = input.path("noteId").asText("");
        String url = input.path("url").asText("");
        if (noteId.isBlank() && url.isBlank()) return ToolResult.error("noteId 或 url 至少填一个");
        if (url.isBlank() && !noteId.isBlank()) {
            url = postLocatorService.findLinkByNoteId(noteId).orElse("");
        }
        boolean includeRaw = input.path("includeRaw").asBoolean(false);

        ThirdPartyXhsService.FetchResult res = service.noteDetail(noteId, url, includeRaw);
        if (!res.ok()) {
            String code = String.valueOf(res.data().getOrDefault("errorType", "third_party_failed"));
            String msg = String.valueOf(res.data().getOrDefault("errorMessage",
                    "第三方笔记详情接口失败，HTTP " + res.status()));
            return ToolResult.error(code, msg, res.data());
        }
        Object note = res.data().get("note");
        String title = "";
        if (note instanceof java.util.Map<?, ?> n) {
            Object t = n.get("title");
            if (t != null) title = String.valueOf(t);
        }
        return ToolResult.of(res.data(),
                title.isBlank() ? "第三方笔记详情获取成功" : "笔记详情：" + title);
    }
}
