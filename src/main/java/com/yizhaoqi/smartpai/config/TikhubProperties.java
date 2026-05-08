package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * TikHub 第三方小红书数据 / 视频解析 provider 的配置。
 *
 * <p>之所以单独建一份配置（而不是复用通用 {@link XhsThirdPartyProperties}）是因为 TikHub 的
 * URL 命名（{@code /api/v1/xiaohongshu/web_v3/fetch_note_detail}）和参数语义（必传
 * {@code xsec_token}）与一般 generic provider 差异较大，硬塞通用模板会让上层工具难以推断错误源。
 *
 * <p>TikHub 鉴权：{@code Authorization: Bearer <api_key>}（header 前缀）。
 *
 * <p>关键路径（参考 {@code https://api.tikhub.io/openapi.json}）：
 * <ul>
 *   <li>{@code GET /api/v1/xiaohongshu/app/extract_share_info}：从分享链接提取
 *       {@code note_id + xsec_token}；</li>
 *   <li>{@code GET /api/v1/xiaohongshu/web_v3/fetch_note_detail}：拿无水印视频直链
 *       {@code noteCard.video.media.stream.h264[].masterUrl}。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "smartpai.xhs.tikhub")
@Data
public class TikhubProperties {

    /** 是否启用 TikHub provider。生产推荐 true。 */
    private boolean enabled = false;

    /** TikHub Base URL，正常为 {@code https://api.tikhub.io}。 */
    private String baseUrl = "https://api.tikhub.io";

    /** Bearer token；不要写"Bearer "前缀，发请求时自动拼。 */
    private String apiKey = "";

    /** 单次 HTTP 请求的超时秒数（不含视频下载，下载有独立超时）。 */
    private int timeoutSeconds = 30;

    /**
     * 默认下载清晰度。可选：{@code best/1080p/720p/480p}。
     * best = 列表第一条流（一般是 HD）。
     */
    private String defaultQuality = "best";

    /**
     * 单次视频下载允许的最大耗时（秒）。
     * 大视频/弱网下载较慢，给宽点；超过会强制中断避免占资源。
     */
    private int downloadTimeoutSeconds = 300;

    /**
     * 单视频允许的最大字节数（默认 200MB）。超过会拒绝下载并报 {@code too_large}。
     * 受 {@code AgentAssetService} 视频白名单二次校验。
     */
    private long maxVideoBytes = 200L * 1024 * 1024L;

    // ---------- endpoint paths（少量厂家可能微调） ----------

    private String extractSharePath = "/api/v1/xiaohongshu/app/extract_share_info";
    private String fetchNoteDetailPath = "/api/v1/xiaohongshu/web_v3/fetch_note_detail";
    private String fetchSearchUsersPath = "/api/v1/xiaohongshu/web_v3/fetch_search_users";
    private String fetchUserInfoPath = "/api/v1/xiaohongshu/web_v3/fetch_user_info";
    private String fetchUserNotesPath = "/api/v1/xiaohongshu/web_v3/fetch_user_notes";
    private String fetchSearchNotesPath = "/api/v1/xiaohongshu/web_v3/fetch_search_notes";

    /** 一级评论；按 noteId + xsec_token 拉。 */
    private String fetchNoteCommentsPath = "/api/v1/xiaohongshu/web_v3/fetch_note_comments";

    /** 二级（子）评论；按 noteId + rootCommentId 拉。 */
    private String fetchSubCommentsPath = "/api/v1/xiaohongshu/web_v3/fetch_sub_comments";

    /** 小红书首页热榜（关键词排行）。 */
    private String fetchHotListPath = "/api/v1/xiaohongshu/web_v2/fetch_hot_list";

    /** 站内热搜词。 */
    private String fetchTrendingPath = "/api/v1/xiaohongshu/web_v3/fetch_trending";

    /** 搜索联想词，用于关键词扩展。 */
    private String fetchSearchSuggestPath = "/api/v1/xiaohongshu/web_v3/fetch_search_suggest";
}
