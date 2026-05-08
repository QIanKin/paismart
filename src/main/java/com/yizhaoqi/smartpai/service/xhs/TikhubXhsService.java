package com.yizhaoqi.smartpai.service.xhs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.config.TikhubProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TikHub 小红书 API 适配层。
 *
 * <p>提供：分享链接补全 + 笔记详情拉取 + 无水印视频直链下载。整个链路 <b>不消耗自家
 * cookie 池</b>，是 cookie+yt-dlp 旧链路的安全替代。
 *
 * <p>错误归一化：
 * <ul>
 *   <li>{@code provider_disabled}：smartpai.xhs.tikhub.enabled=false 或 apiKey 缺失</li>
 *   <li>{@code provider_failed}：TikHub 上游 4xx/5xx</li>
 *   <li>{@code unauthorized}：401/403</li>
 *   <li>{@code rate_limited}：429</li>
 *   <li>{@code note_not_found}：detail.code in (404)</li>
 *   <li>{@code missing_xsec_token}：调 fetch_note_detail 但拿不到 xsec_token</li>
 *   <li>{@code not_video_note}：成功但 stream 列表为空（图文）</li>
 *   <li>{@code cdn_unreachable}：master + 全部 backup 链接 4xx/5xx</li>
 *   <li>{@code too_large}：视频字节数超过 {@code maxVideoBytes}</li>
 *   <li>{@code download_timeout}：超过 {@code downloadTimeoutSeconds}</li>
 * </ul>
 */
@Service
public class TikhubXhsService {

    private static final Logger log = LoggerFactory.getLogger(TikhubXhsService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern NOTE_ID_PATTERN = Pattern.compile(
            "(?:explore|discovery/item)/([0-9a-fA-F]{16,})|/(?:item|notes)/([0-9a-fA-F]{16,})");
    private static final Pattern XSEC_TOKEN_PATTERN = Pattern.compile(
            "[?&]xsec_token=([^&#]+)");

    private final TikhubProperties props;
    private final HttpClient httpClient;

    public TikhubXhsService(TikhubProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, props.getTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 当前 TikHub 是否可用（开关 + 密钥都齐）。
     */
    public boolean configured() {
        return props.isEnabled()
                && props.getBaseUrl() != null && !props.getBaseUrl().isBlank()
                && props.getApiKey() != null && !props.getApiKey().isBlank();
    }

    /**
     * 用一段 url / noteId 解析出 (noteId, xsecToken)。
     * 优先级：url 自带 xsec_token > 调 extract_share_info。
     *
     * @param url       小红书页面/分享链接，可空
     * @param noteId    显式 note id，可空（与 url 至少一个）
     * @param xsecToken 显式 xsec_token，可空
     * @return resolveResult；当 noteId 拿不到时抛 ApiException(missing_note_id)
     */
    public NoteIdentity resolveIdentity(String url, String noteId, String xsecToken) throws ApiException {
        String nid = noteId == null ? "" : noteId.trim();
        String token = xsecToken == null ? "" : xsecToken.trim();
        // 1) url 直接抠
        if (url != null && !url.isBlank()) {
            if (nid.isBlank()) {
                Matcher m = NOTE_ID_PATTERN.matcher(url);
                if (m.find()) {
                    nid = m.group(1) != null ? m.group(1) : m.group(2);
                }
            }
            if (token.isBlank()) {
                Matcher m = XSEC_TOKEN_PATTERN.matcher(url);
                if (m.find()) {
                    token = urlDecode(m.group(1));
                }
            }
        }
        // 2) 仍缺 token —— 调 extract_share_info
        if (!nid.isBlank() && token.isBlank() && url != null && !url.isBlank()) {
            try {
                ApiResult share = extractShareInfo(url);
                JsonNode data = share.data().path("data");
                if (data.has("note_id") && nid.isBlank()) nid = data.path("note_id").asText("");
                if (data.has("xsec_token")) token = data.path("xsec_token").asText("");
            } catch (ApiException e) {
                log.warn("extract_share_info 失败 url={} err={} fallback to provided values", url, e.getMessage());
            }
        }
        if (nid.isBlank()) {
            throw new ApiException("missing_note_id", "无法从 url/noteId 解析出 note_id");
        }
        return new NoteIdentity(nid, token);
    }

    /**
     * 从分享链接拉 note_id + xsec_token。
     * 对应：GET /api/v1/xiaohongshu/app/extract_share_info?share_link=...
     */
    public ApiResult extractShareInfo(String shareLink) throws ApiException {
        if (!configured()) throw new ApiException("provider_disabled", "TikHub provider 未启用或 API Key 缺失");
        if (shareLink == null || shareLink.isBlank()) {
            throw new ApiException("bad_input", "share_link 必填");
        }
        String url = joinUrl(props.getBaseUrl(), props.getExtractSharePath())
                + "?share_link=" + urlEncode(shareLink);
        return getJson(url);
    }

    /**
     * 拉笔记详情。优先 web_v3/fetch_note_detail（必须 note_id + xsec_token）。
     * 返回：原始 TikHub 响应 + {@code noteCard} 解析后的 {@link NoteDetail} (在 ApiResult.note() 中)。
     */
    public ApiResult fetchNoteDetail(String noteId, String xsecToken) throws ApiException {
        if (!configured()) throw new ApiException("provider_disabled", "TikHub provider 未启用或 API Key 缺失");
        if (noteId == null || noteId.isBlank()) throw new ApiException("bad_input", "note_id 必填");
        if (xsecToken == null || xsecToken.isBlank()) {
            throw new ApiException("missing_xsec_token",
                    "web_v3/fetch_note_detail 必须传 xsec_token；可先调 extract_share_info 取得");
        }
        String url = joinUrl(props.getBaseUrl(), props.getFetchNoteDetailPath())
                + "?note_id=" + urlEncode(noteId)
                + "&xsec_token=" + urlEncode(xsecToken);
        ApiResult resp = getJson(url);
        // 解析 noteCard
        JsonNode items = resp.data().path("data").path("data").path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new ApiException("note_not_found", "TikHub 返回 items 为空");
        }
        JsonNode noteCard = items.get(0).path("noteCard");
        NoteDetail detail = parseNoteDetail(noteCard, noteId);
        resp.note = detail;
        return resp;
    }

    /**
     * 一站式：解析身份 → fetch_note_detail。
     */
    public ApiResult resolveAndFetchNote(String url, String noteId, String xsecToken) throws ApiException {
        NoteIdentity id = resolveIdentity(url, noteId, xsecToken);
        return fetchNoteDetail(id.noteId(), id.xsecToken());
    }

    /**
     * 搜索用户（按昵称 / 小红书号关键词）。
     */
    public UserSearchResult searchUsers(String keyword, int page) throws ApiException {
        if (!configured()) throw new ApiException("provider_disabled", "TikHub provider 未启用或 API Key 缺失");
        if (keyword == null || keyword.isBlank()) throw new ApiException("bad_input", "keyword 必填");
        int safePage = Math.max(1, page);
        String url = joinUrl(props.getBaseUrl(), props.getFetchSearchUsersPath())
                + "?keyword=" + urlEncode(keyword.trim())
                + "&page=" + safePage;
        ApiResult resp = getJson(url);
        JsonNode data = resp.data().path("data").path("data");
        UserSearchResult out = new UserSearchResult(resp.status(), resp.data());
        out.hasMore = data.path("hasMore").asBoolean(false);
        out.searchId = data.path("search_id").asText("");
        JsonNode users = data.path("users");
        if (users.isArray()) {
            for (JsonNode user : users) {
                out.users.add(parseUserSummary(user));
            }
        }
        return out;
    }

    /**
     * 拉公开用户资料。
     */
    public UserProfile fetchUserInfo(String userId) throws ApiException {
        if (!configured()) throw new ApiException("provider_disabled", "TikHub provider 未启用或 API Key 缺失");
        if (userId == null || userId.isBlank()) throw new ApiException("bad_input", "user_id 必填");
        String url = joinUrl(props.getBaseUrl(), props.getFetchUserInfoPath())
                + "?user_id=" + urlEncode(userId.trim());
        ApiResult resp = getJson(url);
        JsonNode data = resp.data().path("data").path("data");
        UserProfile profile = parseUserProfile(data);
        profile.raw = resp.data();
        return profile;
    }

    /**
     * 拉用户笔记列表。单次最多 30。
     */
    public UserNotesResult fetchUserNotes(String userId, String cursor, int num) throws ApiException {
        if (!configured()) throw new ApiException("provider_disabled", "TikHub provider 未启用或 API Key 缺失");
        if (userId == null || userId.isBlank()) throw new ApiException("bad_input", "user_id 必填");
        int safeNum = Math.max(1, Math.min(num <= 0 ? 30 : num, 30));
        StringBuilder url = new StringBuilder(joinUrl(props.getBaseUrl(), props.getFetchUserNotesPath()))
                .append("?user_id=").append(urlEncode(userId.trim()))
                .append("&num=").append(safeNum);
        if (cursor != null && !cursor.isBlank()) {
            url.append("&cursor=").append(urlEncode(cursor.trim()));
        }
        ApiResult resp = getJson(url.toString());
        JsonNode data = resp.data().path("data").path("data");
        UserNotesResult out = new UserNotesResult(resp.status(), resp.data());
        out.cursor = data.path("cursor").asText("");
        out.hasMore = data.path("hasMore").asBoolean(false);
        JsonNode notes = data.path("notes");
        if (notes.isArray()) {
            for (JsonNode note : notes) {
                out.notes.add(parseUserNote(note));
            }
        }
        return out;
    }

    /**
     * 搜索笔记。仅做公开搜索，不入库。
     */
    public SearchNotesResult searchNotes(String keyword, int page, String sort, Integer noteType) throws ApiException {
        if (!configured()) throw new ApiException("provider_disabled", "TikHub provider 未启用或 API Key 缺失");
        if (keyword == null || keyword.isBlank()) throw new ApiException("bad_input", "keyword 必填");
        int safePage = Math.max(1, page);
        StringBuilder url = new StringBuilder(joinUrl(props.getBaseUrl(), props.getFetchSearchNotesPath()))
                .append("?keyword=").append(urlEncode(keyword.trim()))
                .append("&page=").append(safePage);
        if (sort != null && !sort.isBlank()) {
            url.append("&sort=").append(urlEncode(sort));
        }
        if (noteType != null) {
            url.append("&note_type=").append(noteType);
        }
        ApiResult resp = getJson(url.toString());
        JsonNode data = resp.data().path("data").path("data");
        SearchNotesResult out = new SearchNotesResult(resp.status(), resp.data());
        out.hasMore = data.path("hasMore").asBoolean(false);
        out.searchId = data.path("search_id").asText("");
        JsonNode items = data.path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                out.notes.add(parseSearchNote(item));
            }
        }
        return out;
    }

    /**
     * 拉笔记一级评论。{@code cursor} 为空表示首页；返回的 {@code cursor} 用于翻页。
     *
     * <p>对应 {@code GET /api/v1/xiaohongshu/web_v3/fetch_note_comments}。
     */
    public CommentsResult fetchNoteComments(String noteId, String xsecToken, String cursor) throws ApiException {
        if (!configured()) throw new ApiException("provider_disabled", "TikHub provider 未启用或 API Key 缺失");
        if (noteId == null || noteId.isBlank()) throw new ApiException("bad_input", "note_id 必填");
        StringBuilder url = new StringBuilder(joinUrl(props.getBaseUrl(), props.getFetchNoteCommentsPath()))
                .append("?note_id=").append(urlEncode(noteId.trim()));
        if (xsecToken != null && !xsecToken.isBlank()) {
            url.append("&xsec_token=").append(urlEncode(xsecToken.trim()));
        }
        if (cursor != null && !cursor.isBlank()) {
            url.append("&cursor=").append(urlEncode(cursor.trim()));
        }
        ApiResult resp = getJson(url.toString());
        return parseCommentsResult(resp, noteId);
    }

    /**
     * 拉某条一级评论的子评论（楼中楼）。
     *
     * <p>对应 {@code GET /api/v1/xiaohongshu/web_v3/fetch_sub_comments}。
     */
    public CommentsResult fetchSubComments(String noteId, String rootCommentId, String cursor) throws ApiException {
        if (!configured()) throw new ApiException("provider_disabled", "TikHub provider 未启用或 API Key 缺失");
        if (noteId == null || noteId.isBlank()) throw new ApiException("bad_input", "note_id 必填");
        if (rootCommentId == null || rootCommentId.isBlank())
            throw new ApiException("bad_input", "root_comment_id 必填");
        StringBuilder url = new StringBuilder(joinUrl(props.getBaseUrl(), props.getFetchSubCommentsPath()))
                .append("?note_id=").append(urlEncode(noteId.trim()))
                .append("&root_comment_id=").append(urlEncode(rootCommentId.trim()));
        if (cursor != null && !cursor.isBlank()) {
            url.append("&cursor=").append(urlEncode(cursor.trim()));
        }
        ApiResult resp = getJson(url.toString());
        return parseCommentsResult(resp, noteId);
    }

    /**
     * 拉小红书热榜（首页 trending 关键词 + 上榜笔记）。
     *
     * <p>对应 {@code GET /api/v1/xiaohongshu/web_v2/fetch_hot_list}。
     */
    public HotListResult fetchHotList() throws ApiException {
        if (!configured()) throw new ApiException("provider_disabled", "TikHub provider 未启用或 API Key 缺失");
        String url = joinUrl(props.getBaseUrl(), props.getFetchHotListPath());
        ApiResult resp = getJson(url);
        HotListResult out = new HotListResult(resp.status(), resp.data());
        JsonNode list = firstArray(resp.data().path("data"), "items", "topics", "list", "data");
        if (list != null && list.isArray()) {
            for (JsonNode item : list) {
                HotListEntry e = new HotListEntry();
                e.title = firstNonBlank(
                        item.path("title").asText(""),
                        item.path("name").asText(""),
                        item.path("keyword").asText(""));
                e.heat = firstNonBlank(
                        item.path("score").asText(""),
                        item.path("heat").asText(""),
                        item.path("hot").asText(""),
                        item.path("count").asText(""));
                e.icon = item.path("icon").asText("");
                e.link = firstNonBlank(item.path("url").asText(""), item.path("link").asText(""));
                e.raw = item;
                if (!e.title.isBlank()) out.entries.add(e);
            }
        }
        return out;
    }

    /**
     * 拉热搜词列表。
     *
     * <p>对应 {@code GET /api/v1/xiaohongshu/web_v3/fetch_trending}。
     */
    public TrendingResult fetchTrending() throws ApiException {
        if (!configured()) throw new ApiException("provider_disabled", "TikHub provider 未启用或 API Key 缺失");
        String url = joinUrl(props.getBaseUrl(), props.getFetchTrendingPath());
        ApiResult resp = getJson(url);
        TrendingResult out = new TrendingResult(resp.status(), resp.data());
        JsonNode arr = firstArray(resp.data().path("data"), "items", "list", "data", "queries");
        if (arr != null && arr.isArray()) {
            for (JsonNode item : arr) {
                TrendingKeyword k = new TrendingKeyword();
                k.keyword = firstNonBlank(
                        item.path("title").asText(""),
                        item.path("keyword").asText(""),
                        item.path("query").asText(""));
                k.heat = firstNonBlank(
                        item.path("score").asText(""),
                        item.path("hot").asText(""),
                        item.path("count").asText(""));
                k.type = item.path("type").asText("");
                k.raw = item;
                if (!k.keyword.isBlank()) out.keywords.add(k);
            }
        }
        return out;
    }

    /**
     * 搜索联想词。
     *
     * <p>对应 {@code GET /api/v1/xiaohongshu/web_v3/fetch_search_suggest}。
     */
    public List<String> fetchSearchSuggest(String keyword) throws ApiException {
        if (!configured()) throw new ApiException("provider_disabled", "TikHub provider 未启用或 API Key 缺失");
        if (keyword == null || keyword.isBlank()) throw new ApiException("bad_input", "keyword 必填");
        String url = joinUrl(props.getBaseUrl(), props.getFetchSearchSuggestPath())
                + "?keyword=" + urlEncode(keyword.trim());
        ApiResult resp = getJson(url);
        List<String> suggestions = new ArrayList<>();
        JsonNode arr = firstArray(resp.data().path("data"), "items", "suggest", "list");
        if (arr != null && arr.isArray()) {
            for (JsonNode it : arr) {
                String text = firstNonBlank(it.path("title").asText(""), it.path("text").asText(""), it.asText(""));
                if (!text.isBlank()) suggestions.add(text);
            }
        }
        return suggestions;
    }

    private CommentsResult parseCommentsResult(ApiResult resp, String noteId) {
        CommentsResult out = new CommentsResult(resp.status(), resp.data());
        JsonNode data = resp.data().path("data");
        out.cursor = firstNonBlank(data.path("cursor").asText(""), data.path("nextCursor").asText(""));
        out.hasMore = data.path("hasMore").asBoolean(data.path("has_more").asBoolean(false));
        JsonNode arr = firstArray(data, "comments", "items", "list", "data");
        if (arr == null || !arr.isArray()) return out;
        for (JsonNode c : arr) {
            CommentItem item = new CommentItem();
            item.commentId = firstNonBlank(c.path("id").asText(""), c.path("commentId").asText(""));
            item.noteId = noteId;
            item.content = c.path("content").asText("");
            item.likeCount = c.path("likeCount").asLong(c.path("likedCount").asLong(0));
            item.subCount = c.path("subCommentCount").asLong(c.path("sub_comment_count").asLong(0));
            item.createdAt = c.path("createTime").asText(c.path("create_time").asText(""));
            item.ipLocation = c.path("ipLocation").asText(c.path("ip_location").asText(""));
            JsonNode user = c.path("user");
            if (user.isObject()) {
                item.userId = firstNonBlank(user.path("id").asText(""), user.path("userId").asText(""));
                item.userName = firstNonBlank(user.path("nickname").asText(""), user.path("name").asText(""));
                item.userAvatar = user.path("image").asText(user.path("avatar").asText(""));
            }
            item.raw = c;
            out.comments.add(item);
        }
        return out;
    }

    private static JsonNode firstArray(JsonNode root, String... keys) {
        if (root == null) return null;
        for (String k : keys) {
            JsonNode n = root.path(k);
            if (n.isArray()) return n;
        }
        return null;
    }

    /**
     * 从一组解析出的视频流里挑一条匹配清晰度的。
     *
     * @param quality best / 1080p / 720p / 480p
     */
    public Optional<VideoStream> pickStream(NoteDetail detail, String quality) {
        if (detail == null || detail.streams.isEmpty()) return Optional.empty();
        String q = quality == null || quality.isBlank() ? props.getDefaultQuality() : quality.toLowerCase();
        if ("best".equals(q)) {
            return Optional.of(detail.streams.get(0));
        }
        int targetH;
        try {
            targetH = Integer.parseInt(q.replace("p", "").trim());
        } catch (NumberFormatException nfe) {
            return Optional.of(detail.streams.get(0));
        }
        VideoStream lowestUnder = null;
        VideoStream lowestOver = null;
        for (VideoStream s : detail.streams) {
            if (s.height <= 0) continue;
            if (s.height <= targetH) {
                if (lowestUnder == null || s.height > lowestUnder.height) lowestUnder = s;
            } else {
                if (lowestOver == null || s.height < lowestOver.height) lowestOver = s;
            }
        }
        if (lowestUnder != null) return Optional.of(lowestUnder);
        if (lowestOver != null) return Optional.of(lowestOver);
        return Optional.of(detail.streams.get(0));
    }

    /**
     * 下载视频流到本地 mp4 文件。先试 masterUrl，失败逐个尝试 backupUrls。
     *
     * @return 下载成功后的文件大小（字节）
     */
    public long downloadStreamTo(VideoStream stream, Path target) throws ApiException {
        if (stream == null) throw new ApiException("bad_input", "stream 为空");
        if (stream.size > 0 && stream.size > props.getMaxVideoBytes()) {
            throw new ApiException("too_large",
                    "视频字节数 " + stream.size + " 超过上限 " + props.getMaxVideoBytes());
        }
        List<String> candidates = new ArrayList<>();
        if (stream.masterUrl != null && !stream.masterUrl.isBlank()) candidates.add(stream.masterUrl);
        if (stream.backupUrls != null) candidates.addAll(stream.backupUrls);
        if (candidates.isEmpty()) {
            throw new ApiException("cdn_unreachable", "stream 没有 masterUrl/backupUrls");
        }

        try {
            Files.createDirectories(target.getParent());
        } catch (IOException ioe) {
            throw new ApiException("io_failed", "创建目标目录失败: " + ioe.getMessage());
        }

        ApiException lastErr = null;
        for (String url : candidates) {
            try {
                long size = downloadOne(url, target);
                if (size > props.getMaxVideoBytes()) {
                    Files.deleteIfExists(target);
                    throw new ApiException("too_large",
                            "实际下载字节数 " + size + " 超过上限 " + props.getMaxVideoBytes());
                }
                return size;
            } catch (ApiException ae) {
                lastErr = ae;
                log.warn("Tikhub 下载失败 url={} err={}", url, ae.getMessage());
            } catch (IOException ioe) {
                lastErr = new ApiException("io_failed", ioe.getMessage());
                log.warn("Tikhub 下载 IO 失败 url={} err={}", url, ioe.getMessage());
            }
        }
        throw lastErr != null ? lastErr : new ApiException("cdn_unreachable", "全部直链均无法下载");
    }

    private long downloadOne(String url, Path target) throws IOException, ApiException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(props.getDownloadTimeoutSeconds()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) PaiSmart/1.0")
                .header("Accept", "*/*")
                .GET()
                .build();
        HttpResponse<Path> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofFile(target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE));
        } catch (java.net.http.HttpTimeoutException te) {
            throw new ApiException("download_timeout",
                    "下载超时 " + props.getDownloadTimeoutSeconds() + "s");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ApiException("interrupted", ie.getMessage());
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new ApiException("cdn_unreachable",
                    "HTTP " + resp.statusCode() + " 来自 CDN: " + truncate(url, 80));
        }
        return Files.size(target);
    }

    // ----------------- TikHub HTTP helpers -----------------

    private ApiResult getJson(String fullUrl) throws ApiException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(Duration.ofSeconds(props.getTimeoutSeconds()))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + props.getApiKey())
                .GET()
                .build();
        try {
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = resp.statusCode();
            JsonNode body;
            try {
                body = MAPPER.readTree(resp.body());
            } catch (Exception parseErr) {
                throw new ApiException("provider_failed",
                        "TikHub 返回非 JSON HTTP=" + status + " body=" + truncate(resp.body(), 200));
            }
            if (status >= 200 && status < 300) {
                return new ApiResult(status, body);
            }
            String code;
            String message;
            JsonNode detail = body.path("detail");
            if (detail.isObject()) {
                code = mapErrorCode(status, detail.path("code").asInt(status));
                message = firstNonBlank(detail.path("message_zh").asText(""),
                        detail.path("message").asText(""),
                        "HTTP " + status);
            } else {
                code = mapErrorCode(status, status);
                message = firstNonBlank(detail.asText(""), "HTTP " + status);
            }
            throw new ApiException(code, "TikHub " + message + " (HTTP " + status + ")");
        } catch (IOException ioe) {
            throw new ApiException("network", "TikHub IO 错误: " + ioe.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ApiException("interrupted", ie.getMessage());
        }
    }

    private static String mapErrorCode(int httpStatus, int upstreamCode) {
        if (httpStatus == 401 || httpStatus == 403 || upstreamCode == 401 || upstreamCode == 403) return "unauthorized";
        if (httpStatus == 429 || upstreamCode == 429) return "rate_limited";
        if (httpStatus == 404 || upstreamCode == 404) return "note_not_found";
        return "provider_failed";
    }

    // ----------------- 解析 noteCard -----------------

    private NoteDetail parseNoteDetail(JsonNode noteCard, String noteId) {
        NoteDetail d = new NoteDetail();
        d.noteId = firstNonBlank(noteCard.path("noteId").asText(""), noteId);
        d.title = noteCard.path("title").asText("");
        d.desc = noteCard.path("desc").asText("");
        d.type = noteCard.path("type").asText("");
        d.ipLocation = noteCard.path("ipLocation").asText("");
        d.publishedAtMs = noteCard.path("time").asLong(0);
        d.lastUpdateAtMs = noteCard.path("lastUpdateTime").asLong(0);

        JsonNode user = noteCard.path("user");
        d.authorId = user.path("userId").asText("");
        d.authorName = user.path("nickname").asText("");
        d.authorAvatar = user.path("avatar").asText("");

        JsonNode interact = noteCard.path("interactInfo");
        d.likedCount = parseInteractCount(interact, "likedCount");
        d.collectedCount = parseInteractCount(interact, "collectedCount");
        d.commentCount = parseInteractCount(interact, "commentCount");
        d.shareCount = parseInteractCount(interact, "shareCount");

        JsonNode video = noteCard.path("video");
        if (video.isObject()) {
            JsonNode media = video.path("media");
            JsonNode videoMeta = media.path("video");
            d.durationSec = videoMeta.path("duration").asInt(0);
            JsonNode stream = media.path("stream");
            // h264 优先（兼容性最好），h265 次之
            collectStreams(stream.path("h264"), "h264", d.streams);
            collectStreams(stream.path("h265"), "h265", d.streams);
        }
        // cover：取 imageList 第一张
        JsonNode imageList = noteCard.path("imageList");
        if (imageList.isArray() && !imageList.isEmpty()) {
            JsonNode first = imageList.get(0);
            d.coverUrl = firstNonBlank(first.path("urlDefault").asText(""),
                    first.path("url").asText(""));
        }
        return d;
    }

    private static long parseInteractCount(JsonNode interact, String key) {
        JsonNode v = interact.path(key);
        if (v.isMissingNode() || v.isNull()) return 0;
        if (v.isNumber()) return v.asLong();
        // 小红书 interactInfo 经常是字符串 "1.2万"，这里只在纯数字下解析，其他保留 0 让上层显示原文
        try {
            return Long.parseLong(v.asText("").trim());
        } catch (NumberFormatException nfe) {
            return -1L; // -1 表示需要看 raw 字段
        }
    }

    private static void collectStreams(JsonNode arr, String codecHint, List<VideoStream> out) {
        if (!arr.isArray()) return;
        for (JsonNode s : arr) {
            VideoStream vs = new VideoStream();
            vs.codec = firstNonBlank(s.path("videoCodec").asText(""), codecHint);
            vs.quality = s.path("qualityType").asText("");
            vs.format = firstNonBlank(s.path("format").asText(""), "mp4");
            vs.width = s.path("width").asInt(0);
            vs.height = s.path("height").asInt(0);
            vs.size = s.path("size").asLong(0);
            vs.durationMs = s.path("duration").asLong(0);
            vs.avgBitrate = s.path("avgBitrate").asLong(0);
            vs.fps = s.path("fps").asInt(0);
            vs.masterUrl = s.path("masterUrl").asText("");
            vs.backupUrls = new ArrayList<>();
            JsonNode backup = s.path("backupUrls");
            if (backup.isArray()) {
                for (JsonNode b : backup) {
                    String bu = b.asText("");
                    if (!bu.isBlank()) vs.backupUrls.add(bu);
                }
            }
            if (!vs.masterUrl.isBlank() || !vs.backupUrls.isEmpty()) {
                out.add(vs);
            }
        }
    }

    private UserSummary parseUserSummary(JsonNode user) {
        UserSummary u = new UserSummary();
        u.userId = firstNonBlank(user.path("id").asText(""), user.path("userId").asText(""));
        u.redId = firstNonBlank(user.path("redId").asText(""), user.path("subTitle").asText(""));
        u.nickname = firstNonBlank(user.path("name").asText(""), user.path("nickname").asText(""));
        u.avatar = firstNonBlank(user.path("image").asText(""), user.path("avatar").asText(""));
        u.fansText = user.path("fans").asText("");
        u.noteCount = user.path("noteCount").asInt(0);
        u.xsecToken = user.path("xsecToken").asText("");
        u.link = user.path("link").asText("");
        u.updateTime = user.path("updateTime").asText("");
        u.verified = user.path("redOfficialVerified").asBoolean(false)
                || user.path("showRedOfficialVerifyIcon").asBoolean(false);
        u.raw = user;
        return u;
    }

    private UserProfile parseUserProfile(JsonNode data) {
        UserProfile p = new UserProfile();
        p.userId = firstNonBlank(
                data.path("user_id").asText(""),
                data.path("userId").asText(""),
                data.path("basicInfo").path("userid").asText(""));
        p.nickname = firstNonBlank(
                data.path("nickname").asText(""),
                data.path("basicInfo").path("nickname").asText(""));
        p.redId = firstNonBlank(
                data.path("redId").asText(""),
                data.path("basicInfo").path("redId").asText(""));
        p.avatar = firstNonBlank(
                data.path("avatar").asText(""),
                data.path("images").asText(""),
                data.path("basicInfo").path("imageb").asText(""));
        p.desc = firstNonBlank(
                data.path("desc").asText(""),
                data.path("basicInfo").path("desc").asText(""));
        p.ipLocation = firstNonBlank(
                data.path("ipLocation").asText(""),
                data.path("basicInfo").path("ipLocation").asText(""));
        p.fansText = firstNonBlank(
                data.path("fans").asText(""),
                data.path("interactions").path("fans").asText(""));
        p.likesText = firstNonBlank(
                data.path("liked").asText(""),
                data.path("interactions").path("liked").asText(""));
        p.noteCount = parseFlexibleLong(data.path("interactions").path("notes").asText(""));
        if (p.noteCount == null) {
            p.noteCount = parseFlexibleLong(data.path("noteCount").asText(""));
        }
        p.followingText = firstNonBlank(
                data.path("interactions").path("follows").asText(""),
                data.path("following").asText(""));
        p.verified = data.path("redOfficialVerifyType").asInt(0) > 0
                || data.path("showRedOfficialVerifyIcon").asBoolean(false);
        p.rawMap = toLooseMap(data);
        return p;
    }

    private UserNote parseUserNote(JsonNode note) {
        UserNote n = new UserNote();
        n.noteId = firstNonBlank(note.path("noteId").asText(""), note.path("note_id").asText(""));
        n.cursor = firstNonBlank(note.path("cursor").asText(""), n.noteId);
        n.xsecToken = note.path("xsecToken").asText("");
        n.type = note.path("type").asText("");
        n.title = firstNonBlank(note.path("displayTitle").asText(""), note.path("title").asText(""));
        n.coverUrl = firstNonBlank(
                note.path("cover").path("urlDefault").asText(""),
                note.path("cover").path("urlPre").asText(""),
                note.path("cover").path("url").asText(""));
        JsonNode interact = note.path("interactInfo");
        n.likes = parseFlexibleLong(firstNonBlank(
                interact.path("likedCount").asText(""),
                note.path("likedCount").asText("")));
        n.comments = parseFlexibleLong(firstNonBlank(
                interact.path("commentCount").asText(""),
                note.path("commentCount").asText("")));
        n.shares = parseFlexibleLong(firstNonBlank(
                interact.path("shareCount").asText(""),
                note.path("shareCount").asText("")));
        n.collects = parseFlexibleLong(firstNonBlank(
                interact.path("collectedCount").asText(""),
                note.path("collectedCount").asText("")));
        JsonNode user = note.path("user");
        n.authorId = firstNonBlank(user.path("userId").asText(""), user.path("id").asText(""));
        n.authorName = firstNonBlank(user.path("nickname").asText(""), user.path("name").asText(""));
        n.link = buildExploreUrl(n.noteId, n.xsecToken);
        n.raw = note;
        return n;
    }

    private SearchNote parseSearchNote(JsonNode item) {
        SearchNote n = new SearchNote();
        n.noteId = firstNonBlank(item.path("id").asText(""), item.path("noteId").asText(""));
        n.xsecToken = item.path("xsecToken").asText("");
        n.title = firstNonBlank(item.path("title").asText(""), item.path("displayTitle").asText(""));
        n.type = item.path("type").asText("");
        n.coverUrl = firstNonBlank(
                item.path("cover").path("urlDefault").asText(""),
                item.path("cover").path("urlPre").asText(""),
                item.path("cover").path("url").asText(""));
        JsonNode user = item.path("user");
        n.userId = firstNonBlank(user.path("userId").asText(""), user.path("id").asText(""));
        n.userName = firstNonBlank(user.path("nickname").asText(""), user.path("name").asText(""));
        JsonNode interact = item.path("interactInfo");
        n.likes = parseFlexibleLong(firstNonBlank(
                interact.path("likedCount").asText(""),
                item.path("likedCount").asText("")));
        n.comments = parseFlexibleLong(firstNonBlank(
                interact.path("commentCount").asText(""),
                item.path("commentCount").asText("")));
        n.shares = parseFlexibleLong(firstNonBlank(
                interact.path("shareCount").asText(""),
                item.path("shareCount").asText("")));
        n.link = buildExploreUrl(n.noteId, n.xsecToken);
        n.raw = item;
        return n;
    }

    // ----------------- 输出工具方法 -----------------

    /**
     * 把 NoteDetail 转成给 LLM/前端的扁平 JSON。
     */
    public ObjectNode noteToJson(NoteDetail d) {
        ObjectNode root = MAPPER.createObjectNode();
        if (d == null) return root;
        root.put("noteId", d.noteId);
        root.put("title", d.title);
        root.put("desc", d.desc);
        root.put("type", d.type);
        root.put("ipLocation", d.ipLocation);
        root.put("publishedAtMs", d.publishedAtMs);
        root.put("lastUpdateAtMs", d.lastUpdateAtMs);
        root.put("authorId", d.authorId);
        root.put("authorName", d.authorName);
        root.put("authorAvatar", d.authorAvatar);
        root.put("durationSec", d.durationSec);
        root.put("coverUrl", d.coverUrl);
        ObjectNode interact = root.putObject("interact");
        interact.put("likedCount", d.likedCount);
        interact.put("collectedCount", d.collectedCount);
        interact.put("commentCount", d.commentCount);
        interact.put("shareCount", d.shareCount);
        ArrayNode streams = root.putArray("streams");
        for (VideoStream s : d.streams) {
            ObjectNode sn = streams.addObject();
            sn.put("codec", s.codec);
            sn.put("quality", s.quality);
            sn.put("format", s.format);
            sn.put("width", s.width);
            sn.put("height", s.height);
            sn.put("size", s.size);
            sn.put("durationMs", s.durationMs);
            sn.put("avgBitrate", s.avgBitrate);
            sn.put("fps", s.fps);
            sn.put("masterUrl", s.masterUrl);
            ArrayNode bk = sn.putArray("backupUrls");
            for (String b : s.backupUrls) bk.add(b);
        }
        return root;
    }

    // ----------------- 字符串工具 -----------------

    private static String joinUrl(String base, String path) {
        if (base.endsWith("/") && path.startsWith("/")) return base.substring(0, base.length() - 1) + path;
        if (!base.endsWith("/") && !path.startsWith("/")) return base + "/" + path;
        return base + path;
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static String firstNonBlank(String... ss) {
        for (String s : ss) if (s != null && !s.isBlank()) return s;
        return "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String buildExploreUrl(String noteId, String xsecToken) {
        if (noteId == null || noteId.isBlank()) return "";
        if (xsecToken == null || xsecToken.isBlank()) {
            return "https://www.xiaohongshu.com/explore/" + noteId;
        }
        return "https://www.xiaohongshu.com/explore/" + noteId + "?xsec_token=" + urlEncode(xsecToken);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toLooseMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return Map.of();
        try {
            return MAPPER.convertValue(node, Map.class);
        } catch (Exception e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("raw", node.toString());
            return out;
        }
    }

    private static Long parseFlexibleLong(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isBlank()) return null;
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException ignored) {
            // continue
        }
        try {
            if (s.endsWith("万")) {
                double v = Double.parseDouble(s.substring(0, s.length() - 1));
                return Math.round(v * 10_000d);
            }
            if (s.endsWith("千")) {
                double v = Double.parseDouble(s.substring(0, s.length() - 1));
                return Math.round(v * 1_000d);
            }
        } catch (NumberFormatException ignored) {
            // continue
        }
        return null;
    }

    // ----------------- DTO -----------------

    /** TikHub raw HTTP 调用结果。 */
    public static final class ApiResult {
        private final int status;
        private final JsonNode data;
        private NoteDetail note;

        public ApiResult(int status, JsonNode data) {
            this.status = status;
            this.data = data;
        }

        public int status() { return status; }
        public JsonNode data() { return data; }
        public NoteDetail note() { return note; }
    }

    /** TikHub 调用失败的统一异常，工具层据此映射 ToolResult.error(code, msg)。 */
    public static class ApiException extends Exception {
        private final String code;

        public ApiException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }

    /** 解析后的笔记关键字段。 */
    public static final class NoteDetail {
        public String noteId = "";
        public String title = "";
        public String desc = "";
        public String type = "";
        public String ipLocation = "";
        public long publishedAtMs;
        public long lastUpdateAtMs;
        public String authorId = "";
        public String authorName = "";
        public String authorAvatar = "";
        public int durationSec;
        public String coverUrl = "";
        public long likedCount;
        public long collectedCount;
        public long commentCount;
        public long shareCount;
        public List<VideoStream> streams = new ArrayList<>();

        public boolean isVideoNote() {
            return !streams.isEmpty();
        }
    }

    /** 单条视频流（同一笔记可能有 h264/h265 多版本）。 */
    public static final class VideoStream {
        public String codec = "";
        public String quality = "";
        public String format = "";
        public int width;
        public int height;
        public long size;
        public long durationMs;
        public long avgBitrate;
        public int fps;
        public String masterUrl = "";
        public List<String> backupUrls = new ArrayList<>();
    }

    /** noteId + xsecToken 二元组。 */
    public record NoteIdentity(String noteId, String xsecToken) {}

    public static final class UserSummary {
        public String userId = "";
        public String redId = "";
        public String nickname = "";
        public String avatar = "";
        public String fansText = "";
        public int noteCount;
        public String xsecToken = "";
        public String link = "";
        public String updateTime = "";
        public boolean verified;
        public JsonNode raw;
    }

    public static final class UserProfile {
        public String userId = "";
        public String redId = "";
        public String nickname = "";
        public String avatar = "";
        public String desc = "";
        public String ipLocation = "";
        public String fansText = "";
        public String likesText = "";
        public String followingText = "";
        public Long noteCount;
        public boolean verified;
        public JsonNode raw;
        public Map<String, Object> rawMap = Map.of();
    }

    public static final class UserNote {
        public String noteId = "";
        public String cursor = "";
        public String xsecToken = "";
        public String type = "";
        public String title = "";
        public String coverUrl = "";
        public Long likes;
        public Long comments;
        public Long shares;
        public Long collects;
        public String authorId = "";
        public String authorName = "";
        public String link = "";
        public JsonNode raw;
    }

    public static final class SearchNote {
        public String noteId = "";
        public String xsecToken = "";
        public String title = "";
        public String type = "";
        public String coverUrl = "";
        public String userId = "";
        public String userName = "";
        public Long likes;
        public Long comments;
        public Long shares;
        public String link = "";
        public JsonNode raw;
    }

    public static final class UserSearchResult {
        private final int status;
        private final JsonNode raw;
        public boolean hasMore;
        public String searchId = "";
        public List<UserSummary> users = new ArrayList<>();

        public UserSearchResult(int status, JsonNode raw) {
            this.status = status;
            this.raw = raw;
        }

        public int status() { return status; }
        public JsonNode raw() { return raw; }
    }

    public static final class UserNotesResult {
        private final int status;
        private final JsonNode raw;
        public boolean hasMore;
        public String cursor = "";
        public List<UserNote> notes = new ArrayList<>();

        public UserNotesResult(int status, JsonNode raw) {
            this.status = status;
            this.raw = raw;
        }

        public int status() { return status; }
        public JsonNode raw() { return raw; }
    }

    public static final class SearchNotesResult {
        private final int status;
        private final JsonNode raw;
        public boolean hasMore;
        public String searchId = "";
        public List<SearchNote> notes = new ArrayList<>();

        public SearchNotesResult(int status, JsonNode raw) {
            this.status = status;
            this.raw = raw;
        }

        public int status() { return status; }
        public JsonNode raw() { return raw; }
    }

    public static final class CommentItem {
        public String commentId = "";
        public String noteId = "";
        public String content = "";
        public long likeCount;
        public long subCount;
        public String createdAt = "";
        public String ipLocation = "";
        public String userId = "";
        public String userName = "";
        public String userAvatar = "";
        public JsonNode raw;
    }

    public static final class CommentsResult {
        private final int status;
        private final JsonNode raw;
        public boolean hasMore;
        public String cursor = "";
        public List<CommentItem> comments = new ArrayList<>();

        public CommentsResult(int status, JsonNode raw) {
            this.status = status;
            this.raw = raw;
        }

        public int status() { return status; }
        public JsonNode raw() { return raw; }
    }

    public static final class HotListEntry {
        public String title = "";
        public String heat = "";
        public String icon = "";
        public String link = "";
        public JsonNode raw;
    }

    public static final class HotListResult {
        private final int status;
        private final JsonNode raw;
        public List<HotListEntry> entries = new ArrayList<>();

        public HotListResult(int status, JsonNode raw) {
            this.status = status;
            this.raw = raw;
        }

        public int status() { return status; }
        public JsonNode raw() { return raw; }
    }

    public static final class TrendingKeyword {
        public String keyword = "";
        public String heat = "";
        public String type = "";
        public JsonNode raw;
    }

    public static final class TrendingResult {
        private final int status;
        private final JsonNode raw;
        public List<TrendingKeyword> keywords = new ArrayList<>();

        public TrendingResult(int status, JsonNode raw) {
            this.status = status;
            this.raw = raw;
        }

        public int status() { return status; }
        public JsonNode raw() { return raw; }
    }
}
