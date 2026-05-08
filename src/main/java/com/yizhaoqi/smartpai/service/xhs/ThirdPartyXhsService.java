package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.config.XhsThirdPartyProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 第三方小红书数据 / 下载 provider 适配层。
 *
 * <p>分发逻辑：
 * <ul>
 *   <li>配置 {@code smartpai.xhs.third-party.provider=tikhub} 且 {@link TikhubXhsService#configured()}
 *       为真：走 {@code TikhubXhsService}（推荐生产路径）；</li>
 *   <li>其他 provider：走 {@link ExternalJsonApiClient} 通用 HTTP 调用，按
 *       {@code smartpai.xhs.third-party.*} 路径模板构造请求。</li>
 * </ul>
 *
 * <p>无论走哪一支，对外都返回统一的 {@link FetchResult}（status / json / data 三元组），让上层
 * 工具实现保持单一语义。
 */
@Service
public class ThirdPartyXhsService {

    private static final String PROVIDER_TIKHUB = "tikhub";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final XhsThirdPartyProperties props;
    private final TikhubXhsService tikhubService;

    public ThirdPartyXhsService(XhsThirdPartyProperties props,
                                TikhubXhsService tikhubService) {
        this.props = props;
        this.tikhubService = tikhubService;
    }

    /**
     * 是否有任何一种第三方通道可用。tikhub 路径不需要 base-url（自带默认 https://api.tikhub.io）。
     */
    public boolean configured() {
        if (useTikhub()) return tikhubService.configured();
        return props.isEnabled() && props.getBaseUrl() != null && !props.getBaseUrl().isBlank();
    }

    public boolean useTikhub() {
        return PROVIDER_TIKHUB.equalsIgnoreCase(props.getProvider());
    }

    /**
     * 暴露 TikhubXhsService 给需要"原生"调用 TikHub 能力的工具（如 xhs_video_analyze）。
     */
    public TikhubXhsService tikhub() {
        return tikhubService;
    }

    /**
     * 拉笔记详情。tikhub 路径会同时返回直链 + interact + 作者元数据。
     *
     * @param includeRaw  仅对 generic provider 生效，传给上游让它返回原始小红书响应
     */
    public FetchResult noteDetail(String noteId, String url, boolean includeRaw) throws Exception {
        if (useTikhub()) {
            try {
                TikhubXhsService.ApiResult tres = tikhubService.resolveAndFetchNote(url, noteId, null);
                ObjectNode data = MAPPER.createObjectNode();
                data.put("provider", PROVIDER_TIKHUB);
                data.put("action", "note_detail");
                data.set("note", tikhubService.noteToJson(tres.note()));
                if (includeRaw) data.set("raw", tres.data());
                return new FetchResult(true, tres.status(), tres.data(), toMap(data));
            } catch (TikhubXhsService.ApiException ae) {
                ObjectNode data = MAPPER.createObjectNode();
                data.put("provider", PROVIDER_TIKHUB);
                data.put("action", "note_detail");
                data.put("errorType", ae.code());
                data.put("errorMessage", ae.getMessage());
                return new FetchResult(false, mapHttpStatus(ae.code()), null, toMap(data));
            }
        }
        return genericNoteDetail(noteId, url, includeRaw);
    }

    /**
     * 媒体解析 / 下载。
     *
     * <p>tikhub 路径下 mode 含义：
     * <ul>
     *   <li>{@code parse}：仅返回笔记 + 视频流元数据 + 直链；</li>
     *   <li>{@code download} / {@code archive}：返回直链（不在本服务里入库；真正入库走 xhs_video_analyze）；</li>
     * </ul>
     */
    public FetchResult mediaDownload(String url, String noteId, String mode, String quality) throws Exception {
        if (useTikhub()) {
            try {
                TikhubXhsService.ApiResult tres = tikhubService.resolveAndFetchNote(url, noteId, null);
                TikhubXhsService.NoteDetail note = tres.note();
                ObjectNode data = MAPPER.createObjectNode();
                data.put("provider", PROVIDER_TIKHUB);
                data.put("action", "media_download");
                data.put("mode", mode == null || mode.isBlank() ? "parse" : mode);
                data.set("note", tikhubService.noteToJson(note));
                if (note != null && !note.isVideoNote()) {
                    data.put("warn", "not_video_note");
                    data.put("warnMessage", "该笔记没有视频流（图文笔记），imageList 详见 raw");
                    data.set("raw", tres.data());
                    return new FetchResult(true, tres.status(), tres.data(), toMap(data));
                }
                Optional<TikhubXhsService.VideoStream> selected =
                        tikhubService.pickStream(note, quality);
                if (selected.isPresent()) {
                    TikhubXhsService.VideoStream s = selected.get();
                    ObjectNode sel = data.putObject("selectedStream");
                    sel.put("codec", s.codec);
                    sel.put("quality", s.quality);
                    sel.put("width", s.width);
                    sel.put("height", s.height);
                    sel.put("size", s.size);
                    sel.put("durationMs", s.durationMs);
                    sel.put("masterUrl", s.masterUrl);
                    ArrayNode bk = sel.putArray("backupUrls");
                    for (String b : s.backupUrls) bk.add(b);
                }
                return new FetchResult(true, tres.status(), tres.data(), toMap(data));
            } catch (TikhubXhsService.ApiException ae) {
                ObjectNode data = MAPPER.createObjectNode();
                data.put("provider", PROVIDER_TIKHUB);
                data.put("action", "media_download");
                data.put("errorType", ae.code());
                data.put("errorMessage", ae.getMessage());
                return new FetchResult(false, mapHttpStatus(ae.code()), null, toMap(data));
            }
        }
        return genericMediaDownload(url, noteId, mode, quality);
    }

    // ----------------- generic provider 走旧 ExternalJsonApiClient -----------------

    private FetchResult genericNoteDetail(String noteId, String url, boolean includeRaw) throws Exception {
        ExternalJsonApiClient client = new ExternalJsonApiClient(props.getTimeoutSeconds());
        Map<String, Object> query = new LinkedHashMap<>();
        if (noteId != null && !noteId.isBlank()) query.put("note_id", noteId);
        if (url != null && !url.isBlank()) query.put("url", url);
        query.put("include_raw", includeRaw);
        ExternalJsonApiClient.ApiResponse resp = client.get(
                props.getBaseUrl(),
                props.getNoteDetailPath(),
                query,
                props.getApiKeyHeader(),
                props.getApiKey(),
                props.getTimeoutSeconds()
        );
        return wrap("note_detail", resp);
    }

    private FetchResult genericMediaDownload(String url, String noteId, String mode, String quality) throws Exception {
        ExternalJsonApiClient client = new ExternalJsonApiClient(props.getTimeoutSeconds());
        Map<String, Object> body = new LinkedHashMap<>();
        if (url != null && !url.isBlank()) body.put("url", url);
        if (noteId != null && !noteId.isBlank()) body.put("note_id", noteId);
        body.put("mode", mode == null || mode.isBlank() ? "download" : mode);
        body.put("quality", quality == null || quality.isBlank() ? "best" : quality);
        ExternalJsonApiClient.ApiResponse resp = client.postJson(
                props.getBaseUrl(),
                props.getMediaDownloadPath(),
                body,
                props.getApiKeyHeader(),
                props.getApiKey(),
                props.getTimeoutSeconds()
        );
        return wrap("media_download", resp);
    }

    private FetchResult wrap(String action, ExternalJsonApiClient.ApiResponse resp) {
        boolean ok = resp.status() >= 200 && resp.status() < 300;
        Map<String, Object> data = ExternalJsonApiClient.envelope(resp);
        data.put("provider", props.getProvider());
        data.put("action", action);
        return new FetchResult(ok, resp.status(), resp.json(), data);
    }

    private static int mapHttpStatus(String code) {
        if (code == null) return 500;
        return switch (code) {
            case "unauthorized" -> 401;
            case "rate_limited" -> 429;
            case "note_not_found" -> 404;
            case "missing_xsec_token", "missing_note_id", "bad_input" -> 400;
            case "provider_disabled" -> 503;
            default -> 502;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(JsonNode node) {
        try {
            return MAPPER.convertValue(node, Map.class);
        } catch (Exception e) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("raw", node.toString());
            return map;
        }
    }

    public record FetchResult(boolean ok, int status, JsonNode json, Map<String, Object> data) {}
}
