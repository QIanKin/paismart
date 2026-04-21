package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.XhsSkillRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * xhs_download_video：下载一条小红书视频（可选 full 模式产出音频/字幕/脚本）。
 *
 * <p>底层走 skill {@code xhs-downloader}，cookie 由 {@link XhsSkillRunner} 从公司共享池自动注入。
 * agent 拿到返回后可以继续 {@code read_file(transcript.txt)} 做爆款结构分析。
 */
@Component
public class XhsDownloadVideoTool implements Tool {

    private final XhsSkillRunner runner;
    private final JsonNode schema;

    public XhsDownloadVideoTool(XhsSkillRunner runner) {
        this.runner = runner;
        this.schema = ToolInputSchemas.object()
                .stringProp("url", "小红书链接（explore / discovery / xhslink）", true)
                .enumProp("mode",
                        "产出模式：basic 只下视频；full 视频+音频+字幕+脚本；summary=full+写 meta 供 LLM 汇总",
                        List.of("basic", "full", "summary"), false)
                .enumProp("quality", "视频清晰度",
                        List.of("best", "1080p", "720p", "480p"), false)
                .booleanProp("audioOnly", "仅下音频（mp3）", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_download_video"; }

    @Override public String description() {
        return "下载小红书视频 / 图文。full 模式会额外产出音频 mp3、字幕 vtt、纯文本脚本 transcript.txt，"
                + "用于爆款结构逆向、证据留存、二创参考。";
    }

    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return false; }

    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String orgTag = ctx.orgTag();
        if (orgTag == null || orgTag.isBlank()) return ToolResult.error("当前上下文缺少 orgTag");
        String url = input.path("url").asText("");
        if (url.isBlank()) return ToolResult.error("url 必填");

        String mode = input.hasNonNull("mode") ? input.get("mode").asText("basic") : "basic";
        String quality = input.hasNonNull("quality") ? input.get("quality").asText("best") : "best";
        boolean audioOnly = input.path("audioOnly").asBoolean(false);

        XhsSkillRunner.RunRequest req = new XhsSkillRunner.RunRequest();
        req.orgTag = orgTag;
        req.sessionId = ctx.sessionId();
        req.skillName = "xhs-downloader";
        req.scriptRelative = "scripts/download_xhs.py";
        req.cookiePlatform = "xhs_pc";
        req.extraArgs.add("--url");
        req.extraArgs.add(url);
        req.extraArgs.add("--mode");
        req.extraArgs.add(mode);
        if (!"best".equals(quality)) {
            req.extraArgs.add("--quality");
            req.extraArgs.add(quality);
        }
        if (audioOnly) {
            req.extraArgs.add("--audio-only");
        }
        // full / summary 可能走 whisper / ffmpeg，给 20 分钟上限
        req.timeoutSeconds = "basic".equals(mode) ? 300 : 1200;
        req.cancelled = ctx.cancelled();

        XhsSkillRunner.RunResult res = runner.run(req);
        if (!res.ok()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("errorType", res.errorType());
            detail.put("url", url);
            return ToolResult.error("xhs-downloader 执行失败: " + res.errorMessage(), detail);
        }
        JsonNode payload = res.payload();
        String title = payload.path("title").asText("untitled");
        String uploader = payload.path("uploader").asText("");
        int filesCount = payload.path("files").size();
        return ToolResult.of(payload,
                String.format("下载成功: %s (@%s)，产出 %d 个文件", title, uploader, filesCount));
    }
}
