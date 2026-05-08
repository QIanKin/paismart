package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.config.AiVisionProperties;
import com.yizhaoqi.smartpai.config.TikhubProperties;
import com.yizhaoqi.smartpai.service.LlmSyncCompletionService;
import com.yizhaoqi.smartpai.service.agent.AgentAssetService;
import com.yizhaoqi.smartpai.service.asr.AsrDispatcher;
import com.yizhaoqi.smartpai.service.asr.AsrService;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.TikhubXhsService;
import com.yizhaoqi.smartpai.service.xhs.XhsPostLocatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 一站式小红书视频拆解工具：解析 + 下载 + ASR + LLM 爆款拆解。
 *
 * <p>工作流：
 * <ol>
 *   <li>输入 url / noteId（可选 xsecToken），调 {@link TikhubXhsService} 拿无水印直链；</li>
 *   <li>下载 mp4 到沙箱临时目录 → {@link AgentAssetService#uploadVideoAsset} 入 MinIO；</li>
 *   <li>ffmpeg 抽 mp3 → {@link AgentAssetService#uploadAudioAsset} 入 MinIO；</li>
 *   <li>调 {@link AsrDispatcher#transcribe} 拿 transcript：本地 path 给 Whisper、MinIO URL 给 DashScope；</li>
 *   <li>把 transcript 文本入 MinIO（让 Agent 后续可以 file_read）；</li>
 *   <li>调 {@link LlmSyncCompletionService} 拆解爆款结构（开头钩子 / 选题 / 节奏 / 卖点 / CTA / 复用模板）。</li>
 * </ol>
 *
 * <p>每一步都做了 try/catch + 错误码归一化。中途某步失败时（比如 ASR），上一步的产物（视频/音频）
 * 已经入库可用，工具会带 {@code partial=true} 与 {@code errorType} 返回，便于 Agent 决定后续动作。
 *
 * <p>不在本工具里持久化 ChatMessageAttachment——上层 Agent 拿到 ToolResult 后自己决定是否
 * 把视频/transcript 作为附件存进对话历史。
 */
@Component
public class XhsVideoAnalyzeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(XhsVideoAnalyzeTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TRANSCRIPT_PREVIEW_CHARS = 500;

    private static final String SYSTEM_PROMPT = """
            你是 PaiSmart 的小红书爆款视频拆解分析师。基于给定的视频元信息、音频转写文本以及（若有）视频关键帧画面，
            按下面 JSON 模板输出结构化报告，必须严格输出可解析 JSON（最外层一个对象），不要带任何前后说明文字、
            不要 Markdown 代码块。字段语义：
            
            {
              "summary": "一句话讲清这条视频在做什么",
              "hook": {
                "first_3s": "开头 3 秒里说了/演了什么",
                "type": "悬念/反转/痛点/冲突/直接利益/其他",
                "score": 1-5
              },
              "topic": {
                "angle": "选题切入角度",
                "audience": "目标用户画像（性别/年龄段/兴趣）",
                "trend_alignment": "是否踩中近期趋势 + 简短理由"
              },
              "rhythm": {
                "structure": "起承转合 / 列表式 / 故事化 / 对比 等",
                "key_beats": ["关键节奏点1", "关键节奏点2", "..."],
                "info_density_per_min": "高/中/低 + 简评"
              },
              "visual": {
                "scene_summary": "若有画面输入：开头/中段/结尾各发生了什么",
                "on_screen_text": ["画面里的关键文字（OCR）"],
                "shot_style": "横屏/竖屏 / 真人出镜/产品特写/字幕轰炸 等",
                "color_mood": "整体配色与氛围（例如：高对比/暖色/冷色/极简）"
              },
              "selling_points": ["卖点1", "卖点2", "..."],
              "cta": "结尾引导动作（关注/收藏/评论/购买/...）",
              "reusable_template": {
                "outline": ["模板段落1", "模板段落2", "..."],
                "diy_tips": ["改写建议1", "改写建议2"]
              },
              "rewrites": [
                {"angle": "改写角度1", "title": "改写后标题", "outline": "120 字内改写大纲"}
              ]
            }
            
            约束：
            - 改写部分至少给 2 个新角度；
            - 没有画面输入时，{@code visual} 留 {} 即可；有画面输入时务必填，并把"屏幕上能看到的字"全部塞进 on_screen_text；
            - 当 transcript 缺失或太短时，summary/hook 仍然要基于标题/描述/画面推断，并在 summary 末尾加 (基于元信息推断)；
            - 不要捏造数据，遇到无法判断的字段写 "未知"。
            """;

    private final TikhubXhsService tikhubService;
    private final AsrDispatcher asrDispatcher;
    private final AgentAssetService agentAssetService;
    private final LlmSyncCompletionService llmService;
    private final TikhubProperties tikhubProps;
    private final AiVisionProperties visionProps;
    private final XhsPostLocatorService postLocatorService;
    private final JsonNode schema;

    public XhsVideoAnalyzeTool(TikhubXhsService tikhubService,
                               AsrDispatcher asrDispatcher,
                               AgentAssetService agentAssetService,
                               LlmSyncCompletionService llmService,
                               TikhubProperties tikhubProps,
                               AiVisionProperties visionProps,
                               XhsPostLocatorService postLocatorService) {
        this.tikhubService = tikhubService;
        this.asrDispatcher = asrDispatcher;
        this.agentAssetService = agentAssetService;
        this.llmService = llmService;
        this.tikhubProps = tikhubProps;
        this.visionProps = visionProps;
        this.postLocatorService = postLocatorService;
        this.schema = ToolInputSchemas.object()
                .stringProp("url", "小红书视频链接 / 分享链接（含 xsec_token 最佳）", false)
                .stringProp("noteId", "小红书 note id；和 url 至少给一个", false)
                .stringProp("xsecToken", "可选；显式 xsec_token", false)
                .enumProp("quality", "下载清晰度", List.of("best", "1080p", "720p", "480p"), false)
                .booleanProp("downloadOnly", "true=只下载视频不做 ASR/LLM；默认 false", false)
                .booleanProp("withAsr", "默认 true；false 跳过 ASR + 报告，仅留视频", false)
                .booleanProp("withReport", "默认 true；false 跳过 LLM 报告（保留 transcript）", false)
                .booleanProp("withVision", "默认 true；是否对关键帧做视觉 LLM 分析（含画面 OCR / 镜头识别）。"
                        + "需要 ai.vision.enabled=true 且当前 LLM model 支持视觉，或显式配置 ai.vision.model", false)
                .integerProp("maxDurationSec", "视频最长允许秒数（超过则拒绝下载，避免 ASR 长任务）", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "xhs_video_analyze"; }

    @Override public String description() {
        return "小红书爆款视频一站式拆解：用 TikHub 解析无水印直链 → 下载 → 抽音频 → 本地 Whisper / 云端 DashScope ASR 转写 → "
                + "LLM 输出结构化爆款分析报告（开头钩子/选题/节奏/卖点/CTA/可复用模板/改写建议）。"
                + "全程零 cookie、零封号风险。视频与 transcript 自动落 MinIO，可在聊天里直接播放。";
    }

    @Override public JsonNode inputSchema() { return schema; }
    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isDestructive(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return true; }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) throws Exception {
        // ---------- 0. 入参 + 配置校验 ----------
        if (!tikhubService.configured()) {
            return ToolResult.error("provider_disabled",
                    "TikHub provider 未启用：请先在 .env 配 XHS_TIKHUB_ENABLED=true 与 XHS_TIKHUB_API_KEY");
        }
        String url = input.path("url").asText("");
        String noteId = input.path("noteId").asText("");
        String xsecToken = input.path("xsecToken").asText("");
        if (url.isBlank() && noteId.isBlank()) {
            return ToolResult.error("bad_input", "url 或 noteId 至少给一个");
        }
        if (url.isBlank() && !noteId.isBlank()) {
            url = postLocatorService.findLinkByNoteId(noteId).orElse("");
        }
        String quality = input.path("quality").asText(tikhubProps.getDefaultQuality());
        boolean downloadOnly = input.path("downloadOnly").asBoolean(false);
        boolean withAsr = !downloadOnly && input.path("withAsr").asBoolean(true);
        boolean withReport = withAsr && input.path("withReport").asBoolean(true);
        // 视觉路径独立于 ASR：即便 ASR 关了，仍然可以走画面 + 元信息 + OCR
        boolean withVision = !downloadOnly
                && visionProps.isEnabled()
                && input.path("withVision").asBoolean(true);
        int maxDurationSec = input.path("maxDurationSec").asInt(600);

        String userId = ctx == null ? "anonymous" : safe(ctx.userId(), "anonymous");
        String orgTag = ctx == null ? "default" : safe(ctx.orgTag(), "default");

        // ---------- 1. TikHub 解析 ----------
        TikhubXhsService.NoteDetail note;
        try {
            ctx.emitProgress("step", "调用 TikHub 解析笔记...", null);
            TikhubXhsService.ApiResult res = tikhubService.resolveAndFetchNote(url, noteId, xsecToken);
            note = res.note();
        } catch (TikhubXhsService.ApiException ae) {
            log.warn("TikHub 解析失败 url={} noteId={} code={} msg={}", url, noteId, ae.code(), ae.getMessage());
            return ToolResult.error(ae.code(), ae.getMessage());
        }
        if (note == null || !note.isVideoNote()) {
            return ToolResult.error("not_video_note",
                    "该笔记不是视频笔记或没有可下载的视频流");
        }
        if (note.durationSec > 0 && note.durationSec > maxDurationSec) {
            return ToolResult.error("too_long",
                    "视频时长 " + note.durationSec + "s 超过 maxDurationSec=" + maxDurationSec);
        }
        Optional<TikhubXhsService.VideoStream> picked = tikhubService.pickStream(note, quality);
        if (picked.isEmpty()) {
            return ToolResult.error("not_video_note", "未挑到合适视频流");
        }
        TikhubXhsService.VideoStream stream = picked.get();
        ctx.emitProgress("step", "已选定视频流 " + stream.quality + " " + stream.width + "x" + stream.height, null);

        // ---------- 2. 下载视频到沙箱 → 入 MinIO ----------
        Path workDir = null;
        Path videoPath = null;
        Path audioPath = null;
        try {
            workDir = Files.createTempDirectory("xhs-analyze-");
            videoPath = workDir.resolve(note.noteId + ".mp4");
            ctx.emitProgress("step", "下载视频中...", null);
            long size;
            try {
                size = tikhubService.downloadStreamTo(stream, videoPath);
            } catch (TikhubXhsService.ApiException ae) {
                return ToolResult.error(ae.code(), ae.getMessage());
            }

            AgentAssetService.StoredAsset videoAsset;
            try {
                videoAsset = agentAssetService.uploadVideoAsset(videoPath, orgTag, userId,
                        "video/mp4", note.noteId + ".mp4");
            } catch (Exception e) {
                return ToolResult.error("video_upload_failed",
                        "视频入 MinIO 失败：" + e.getMessage());
            }
            ctx.emitProgress("step",
                    "视频已入库 " + (size / 1024) + "KB", Map.of("objectKey", videoAsset.objectKey()));

            // ---------- 3. downloadOnly 提前返回 ----------
            if (downloadOnly) {
                return buildResult(note, stream, videoAsset, null, null, null,
                        false, null, null, null);
            }

            // ---------- 4. ffmpeg 抽 mp3 → 入 MinIO ----------
            audioPath = workDir.resolve(note.noteId + ".mp3");
            ctx.emitProgress("step", "ffmpeg 抽取音频...", null);
            try {
                runFfmpegExtractAudio(videoPath, audioPath);
            } catch (Exception fe) {
                log.warn("ffmpeg 抽音频失败 noteId={} err={}", note.noteId, fe.getMessage());
                // 视频已入库，partial 返回
                return buildResult(note, stream, videoAsset, null, null, null,
                        true, "ffmpeg_failed", "抽音频失败: " + fe.getMessage(), null);
            }

            AgentAssetService.StoredAsset audioAsset;
            try {
                audioAsset = agentAssetService.uploadAudioAsset(audioPath, orgTag, userId,
                        "audio/mpeg", note.noteId + ".mp3");
            } catch (Exception e) {
                return buildResult(note, stream, videoAsset, null, null, null,
                        true, "audio_upload_failed", e.getMessage(), null);
            }
            ctx.emitProgress("step", "音频已入库", Map.of("objectKey", audioAsset.objectKey()));

            if (!withAsr) {
                return buildResult(note, stream, videoAsset, audioAsset, null, null,
                        false, null, null, null);
            }

            // ---------- 5. ASR（按 provider 选 Whisper / DashScope）----------
            AsrService.AsrResult asr;
            if (!asrDispatcher.configured()) {
                return buildResult(note, stream, videoAsset, audioAsset, null, null,
                        true, "asr_disabled",
                        "ASR provider=" + asrDispatcher.activeProvider()
                                + " 未配置；视频与音频已落 MinIO 备查。", null);
            }
            try {
                ctx.emitProgress("step",
                        "ASR 转写中（provider=" + asrDispatcher.activeProvider() + "）...", null);
                asr = asrDispatcher.transcribe(audioPath, audioAsset.url());
            } catch (AsrService.AsrException ae) {
                log.warn("ASR 失败 noteId={} provider={} code={} err={}",
                        note.noteId, asrDispatcher.activeProvider(), ae.code(), ae.getMessage());
                return buildResult(note, stream, videoAsset, audioAsset, null, null,
                        true, ae.code(), "ASR 失败: " + ae.getMessage(), null);
            }

            String transcriptText = asr.text() == null ? "" : asr.text();
            AgentAssetService.StoredAsset transcriptAsset;
            try {
                transcriptAsset = agentAssetService.uploadTranscriptAsset(transcriptText,
                        orgTag, userId, note.noteId + "-transcript.txt");
            } catch (Exception e) {
                return buildResult(note, stream, videoAsset, audioAsset, null, null,
                        true, "transcript_upload_failed", e.getMessage(), null);
            }
            ctx.emitProgress("step", "transcript 已入库 " + transcriptText.length() + " 字", null);

            // ---------- 5.5 视觉关键帧抽取（即便 LLM 报告关了也算，方便单独"画面 OCR"场景）----------
            List<String> frameDataUrls = List.of();
            ArrayNode framesArr = null;
            if (withVision) {
                try {
                    ctx.emitProgress("step",
                            "ffmpeg 抽 " + visionProps.getFrameCount() + " 张关键帧...", null);
                    List<Path> framePaths = extractKeyFrames(videoPath, workDir,
                            visionProps.getFrameCount(), visionProps.getFrameMaxEdge(),
                            note.durationSec);
                    frameDataUrls = framesToDataUrls(framePaths);
                    framesArr = MAPPER.createArrayNode();
                    for (Path fp : framePaths) {
                        ObjectNode f = framesArr.addObject();
                        f.put("fileName", fp.getFileName().toString());
                        try {
                            f.put("size", Files.size(fp));
                        } catch (IOException ignored) { /* size 拿不到不致命 */ }
                    }
                    ctx.emitProgress("step", "已得到 " + frameDataUrls.size() + " 张关键帧", null);
                } catch (Exception fe) {
                    log.warn("关键帧抽取失败 noteId={} err={}", note.noteId, fe.getMessage());
                    // 抽帧失败不阻断 ASR 报告路径，只记日志
                }
            }

            // ---------- 6. LLM 拆解（多模态优先：传 transcript + 关键帧）----------
            ObjectNode report = null;
            String reportError = null;
            if (withReport) {
                try {
                    String userPrompt = buildAnalysisPrompt(note, stream, transcriptText);
                    String text;
                    if (!frameDataUrls.isEmpty()) {
                        ctx.emitProgress("step",
                                "调用视觉 LLM 拆解（" + frameDataUrls.size() + " 帧 + transcript）...", null);
                        text = llmService.completeMultimodal(SYSTEM_PROMPT, userPrompt, frameDataUrls,
                                visionProps.getModel(), 1800, 120);
                    } else {
                        ctx.emitProgress("step", "调用 LLM 拆解爆款结构（仅文本）...", null);
                        text = llmService.complete(SYSTEM_PROMPT, userPrompt, 1800, 90);
                    }
                    report = parseReportJson(text);
                    if (report == null) {
                        reportError = "LLM 输出无法解析为 JSON，已保留原文到 reportRaw";
                    }
                } catch (LlmSyncCompletionService.LlmException le) {
                    log.warn("LLM 拆解失败 noteId={} code={} err={}",
                            note.noteId, le.code(), le.getMessage());
                    reportError = "LLM 拆解失败: " + le.getMessage();
                }
            }
            final ArrayNode framesArrFinal = framesArr;

            return buildResult(note, stream, videoAsset, audioAsset, transcriptAsset, asr,
                    reportError != null, reportError != null ? "report_failed" : null,
                    reportError, report, framesArrFinal);

        } finally {
            cleanup(workDir);
        }
    }

    // -------------------- helpers --------------------

    /**
     * 在视频时长内均匀抽 {@code count} 张关键帧，缩放到最长边 ≤ {@code maxEdge} 后存为 jpg。
     *
     * <p>策略：
     * <ul>
     *   <li>已知 durationSec → 用 ffmpeg {@code -ss + -frames:v 1} 命中 i 帧附近，干净又快；</li>
     *   <li>不知道时长（durationSec ≤ 0）→ 退化用 {@code fps} filter 均匀抽，过滤前 N 张。</li>
     * </ul>
     */
    private List<Path> extractKeyFrames(Path videoPath, Path workDir, int count, int maxEdge,
                                        long durationSec) throws IOException, InterruptedException {
        if (count <= 0) return List.of();
        List<Path> out = new ArrayList<>(count);
        if (durationSec > 1) {
            // 跨度均匀采样：避开开头黑场（从 5% 开始）
            double startRatio = 0.05;
            double endRatio = 0.95;
            for (int i = 0; i < count; i++) {
                double ratio = startRatio + (endRatio - startRatio) * (count == 1 ? 0.5 : (double) i / (count - 1));
                double tsSec = Math.max(0.5, durationSec * ratio);
                Path framePath = workDir.resolve(String.format("frame-%02d.jpg", i + 1));
                runFfmpegOneFrame(videoPath, framePath, tsSec, maxEdge);
                if (Files.exists(framePath) && Files.size(framePath) > 0) {
                    out.add(framePath);
                }
            }
            return out;
        }

        // duration 未知：用 fps filter 一次性抽，再挑前 count 张
        Path framePattern = workDir.resolve("frame-%02d.jpg");
        ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-y",
                "-i", videoPath.toString(),
                "-vf", "fps=1/3,scale='if(gt(iw,ih)," + maxEdge + ",-2)':'if(gt(iw,ih),-2," + maxEdge + ")'",
                "-frames:v", String.valueOf(count + 2),
                "-q:v", "5",
                framePattern.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("ffmpeg 抽帧超时 120s");
        }
        try (var walk = Files.list(workDir)) {
            walk.filter(pp -> pp.getFileName().toString().startsWith("frame-")
                    && pp.getFileName().toString().endsWith(".jpg"))
                    .sorted()
                    .limit(count)
                    .forEach(out::add);
        }
        return out;
    }

    private void runFfmpegOneFrame(Path videoPath, Path framePath, double seekSec, int maxEdge)
            throws IOException, InterruptedException {
        // -ss 在 -i 前用 keyframe 加速；-frames:v 1 只要一张；scale 保宽高比、最长边 maxEdge
        ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-y",
                "-ss", String.format(java.util.Locale.ROOT, "%.2f", seekSec),
                "-i", videoPath.toString(),
                "-frames:v", "1",
                "-vf", "scale='if(gt(iw,ih)," + maxEdge + ",-2)':'if(gt(iw,ih),-2," + maxEdge + ")'",
                "-q:v", "5",
                framePath.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("ffmpeg 抽单帧超时 30s @t=" + seekSec);
        }
        // 不是所有视频都能精确 seek，失败时单张 silently 丢就好（外面会跳过空文件）
    }

    /** 把抽出来的帧编码成 data:image/jpeg;base64,... 内嵌到 LLM 多模态请求里。 */
    private static List<String> framesToDataUrls(List<Path> frames) {
        if (frames == null || frames.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(frames.size());
        for (Path f : frames) {
            try {
                byte[] bytes = Files.readAllBytes(f);
                if (bytes.length == 0) continue;
                String b64 = Base64.getEncoder().encodeToString(bytes);
                out.add("data:image/jpeg;base64," + b64);
            } catch (IOException e) {
                log.warn("读取关键帧失败 path={} err={}", f, e.getMessage());
            }
        }
        return out;
    }

    private void runFfmpegExtractAudio(Path videoPath, Path audioPath) throws IOException, InterruptedException {
        // -y 覆盖；-vn 不要视频；-ac 1 单声道（节省 ASR token）；-ar 16000 16kHz；-q:a 4 中等比特率
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y", "-i", videoPath.toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-q:a", "4",
                audioPath.toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean done = p.waitFor(120, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IOException("ffmpeg 抽音频超时 120s");
        }
        if (p.exitValue() != 0) {
            String stderr;
            try (var is = p.getInputStream()) {
                stderr = new String(is.readAllBytes()).trim();
            }
            throw new IOException("ffmpeg 退出码 " + p.exitValue() + " 输出: "
                    + (stderr.length() > 240 ? stderr.substring(0, 240) + "..." : stderr));
        }
    }

    private static String buildAnalysisPrompt(TikhubXhsService.NoteDetail note,
                                              TikhubXhsService.VideoStream stream,
                                              String transcriptText) {
        StringBuilder sb = new StringBuilder();
        sb.append("【小红书视频元信息】\n");
        sb.append("note_id: ").append(note.noteId).append('\n');
        sb.append("title: ").append(note.title).append('\n');
        if (!note.desc.isBlank()) sb.append("desc: ").append(note.desc).append('\n');
        sb.append("author: ").append(note.authorName)
                .append(" (id=").append(note.authorId).append(")\n");
        if (note.durationSec > 0) sb.append("duration_sec: ").append(note.durationSec).append('\n');
        if (!note.ipLocation.isBlank()) sb.append("ip_location: ").append(note.ipLocation).append('\n');
        sb.append("interact: liked=").append(note.likedCount)
                .append(" collected=").append(note.collectedCount)
                .append(" comments=").append(note.commentCount)
                .append(" shares=").append(note.shareCount).append('\n');
        if (stream != null) {
            sb.append("video_stream: ").append(stream.quality)
                    .append(' ').append(stream.width).append('x').append(stream.height)
                    .append(" codec=").append(stream.codec)
                    .append(" bitrate=").append(stream.avgBitrate).append('\n');
        }
        sb.append("\n【音频转写 transcript】\n");
        if (transcriptText == null || transcriptText.isBlank()) {
            sb.append("(无 transcript，请基于元信息推断)\n");
        } else {
            // 长 transcript 截断，控制 prompt 长度（按字符 ~ 4 chars/token）
            int max = 6000;
            if (transcriptText.length() > max) {
                sb.append(transcriptText, 0, max).append("\n...(truncated)...");
            } else {
                sb.append(transcriptText);
            }
        }
        sb.append("\n\n请直接输出 JSON 报告。");
        return sb.toString();
    }

    private static ObjectNode parseReportJson(String text) {
        if (text == null || text.isBlank()) return null;
        // LLM 偶尔会包 ```json ... ``` 围栏，先剥
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNl = trimmed.indexOf('\n');
            if (firstNl > 0) trimmed = trimmed.substring(firstNl + 1);
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence > 0) trimmed = trimmed.substring(0, lastFence);
            trimmed = trimmed.trim();
        }
        try {
            JsonNode parsed = MAPPER.readTree(trimmed);
            if (parsed instanceof ObjectNode on) return on;
        } catch (Exception ignored) {
        }
        return null;
    }

    private ToolResult buildResult(TikhubXhsService.NoteDetail note,
                                   TikhubXhsService.VideoStream stream,
                                   AgentAssetService.StoredAsset videoAsset,
                                   AgentAssetService.StoredAsset audioAsset,
                                   AgentAssetService.StoredAsset transcriptAsset,
                                   AsrService.AsrResult asr,
                                   boolean partial,
                                   String errorType,
                                   String errorMessage,
                                   ObjectNode report) {
        return buildResult(note, stream, videoAsset, audioAsset, transcriptAsset, asr,
                partial, errorType, errorMessage, report, null);
    }

    private ToolResult buildResult(TikhubXhsService.NoteDetail note,
                                   TikhubXhsService.VideoStream stream,
                                   AgentAssetService.StoredAsset videoAsset,
                                   AgentAssetService.StoredAsset audioAsset,
                                   AgentAssetService.StoredAsset transcriptAsset,
                                   AsrService.AsrResult asr,
                                   boolean partial,
                                   String errorType,
                                   String errorMessage,
                                   ObjectNode report,
                                   ArrayNode visionFrames) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("noteId", note.noteId);
        root.put("title", note.title);
        root.put("authorName", note.authorName);
        root.put("durationSec", note.durationSec);
        root.put("partial", partial);
        if (errorType != null) root.put("errorType", errorType);
        if (errorMessage != null) root.put("errorMessage", errorMessage);

        ObjectNode video = root.putObject("video");
        if (videoAsset != null) {
            video.put("objectKey", videoAsset.objectKey());
            video.put("url", videoAsset.url());
            video.put("size", videoAsset.size());
            video.put("mimeType", videoAsset.mimeType());
            video.put("fileName", videoAsset.fileName());
        }
        if (stream != null) {
            ObjectNode meta = video.putObject("meta");
            meta.put("quality", stream.quality);
            meta.put("width", stream.width);
            meta.put("height", stream.height);
            meta.put("codec", stream.codec);
            meta.put("avgBitrate", stream.avgBitrate);
        }

        if (audioAsset != null) {
            ObjectNode audio = root.putObject("audio");
            audio.put("objectKey", audioAsset.objectKey());
            audio.put("url", audioAsset.url());
            audio.put("size", audioAsset.size());
            audio.put("mimeType", audioAsset.mimeType());
        }

        if (transcriptAsset != null) {
            ObjectNode tr = root.putObject("transcript");
            tr.put("objectKey", transcriptAsset.objectKey());
            tr.put("url", transcriptAsset.url());
            tr.put("size", transcriptAsset.size());
        }
        if (asr != null) {
            ObjectNode asrNode = root.putObject("asr");
            asrNode.put("provider", asrDispatcher.activeProvider());
            asrNode.put("durationMs", asr.durationMs());
            if (asr.detectedLanguages() != null) asrNode.put("languages", asr.detectedLanguages());
            if (asr.taskId() != null) asrNode.put("taskId", asr.taskId());
            String text = asr.text() == null ? "" : asr.text();
            asrNode.put("preview", text.length() > TRANSCRIPT_PREVIEW_CHARS
                    ? text.substring(0, TRANSCRIPT_PREVIEW_CHARS) + "..." : text);
            asrNode.put("totalChars", text.length());
        }
        if (report != null) {
            root.set("report", report);
        }
        if (visionFrames != null && !visionFrames.isEmpty()) {
            root.set("visionFrames", visionFrames);
        }

        ObjectNode meta = root.putObject("meta");
        meta.put("noteUrl", "https://www.xiaohongshu.com/explore/" + note.noteId);
        meta.put("authorId", note.authorId);
        meta.put("ipLocation", note.ipLocation);
        meta.put("likedCount", note.likedCount);
        meta.put("collectedCount", note.collectedCount);
        meta.put("commentCount", note.commentCount);
        meta.put("shareCount", note.shareCount);

        Map<String, Object> uiMeta = new LinkedHashMap<>();
        uiMeta.put("noteId", note.noteId);
        uiMeta.put("title", note.title);
        uiMeta.put("videoUrl", videoAsset != null ? videoAsset.url() : null);
        uiMeta.put("transcriptUrl", transcriptAsset != null ? transcriptAsset.url() : null);
        if (errorType != null) uiMeta.put("errorCode", errorType);

        String summary;
        if (partial && errorType != null) {
            summary = "视频拆解部分完成（" + errorType + "）：" + note.title;
        } else if (report != null) {
            summary = "视频拆解完成：" + note.title;
        } else if (transcriptAsset != null) {
            summary = "视频 + transcript 已入库：" + note.title;
        } else {
            summary = "视频已下载：" + note.title;
        }
        return ToolResult.of(root, summary, uiMeta);
    }

    private static void cleanup(Path workDir) {
        if (workDir == null) return;
        try (var walk = Files.walk(workDir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private static String safe(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }
}
