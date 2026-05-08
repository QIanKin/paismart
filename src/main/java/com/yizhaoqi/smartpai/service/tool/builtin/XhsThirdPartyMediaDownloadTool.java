package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.ThirdPartyXhsService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 第三方小红书视频/图片下载入口。
 */
@Component
public class XhsThirdPartyMediaDownloadTool implements Tool {

    private final ThirdPartyXhsService service;
    private final JsonNode schema;

    public XhsThirdPartyMediaDownloadTool(ThirdPartyXhsService service) {
        this.service = service;
        this.schema = ToolInputSchemas.object()
                .stringProp("url", "小红书笔记链接 / 分享链接；和 noteId 至少填一个", false)
                .stringProp("noteId", "小红书笔记 ID；和 url 至少填一个", false)
                .stringProp("xsecToken", "可选；当 url 不含 xsec_token 又是 tikhub provider 时手动指定", false)
                .enumProp("mode", "处理模式：parse=仅解析返回直链；download/archive=同 parse（真实入库请用 xhs_video_analyze）",
                        List.of("parse", "download", "archive"), false)
                .enumProp("quality", "视频清晰度", List.of("best", "1080p", "720p", "480p"), false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_third_party_media_download"; }

    @Override public String description() {
        return "通过第三方服务（默认 TikHub）解析小红书视频/图片直链。返回无水印视频 mp4 直链 + 备份链接 + 元数据；"
                + "Agent 拿到 masterUrl 后可直接 file_read（小文件）或转交给 xhs_video_analyze 做完整下载+ASR+分析。"
                + "比 cookie 直连下载更稳定、零封号风险。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return true; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        if (!service.configured()) {
            return ToolResult.error("provider_not_configured",
                    "第三方小红书下载 provider 未配置：请设置 XHS_THIRD_PARTY_ENABLED=true 与 TikHub key");
        }
        String url = input.path("url").asText("");
        String noteId = input.path("noteId").asText("");
        if (url.isBlank() && noteId.isBlank()) return ToolResult.error("url 或 noteId 至少填一个");
        String mode = input.path("mode").asText("parse");
        String quality = input.path("quality").asText("best");

        ThirdPartyXhsService.FetchResult res = service.mediaDownload(url, noteId, mode, quality);
        if (!res.ok()) {
            String code = String.valueOf(res.data().getOrDefault("errorType", "third_party_failed"));
            String msg = String.valueOf(res.data().getOrDefault("errorMessage",
                    "第三方媒体下载接口失败，HTTP " + res.status()));
            return ToolResult.error(code, msg, res.data());
        }
        Object selected = res.data().get("selectedStream");
        String summary;
        if (selected instanceof java.util.Map<?, ?> sel) {
            summary = "解析成功：" + String.valueOf(sel.get("quality")) + " "
                    + sel.get("width") + "x" + sel.get("height");
        } else {
            summary = "第三方小红书媒体处理成功";
        }
        return ToolResult.of(res.data(), summary);
    }
}
