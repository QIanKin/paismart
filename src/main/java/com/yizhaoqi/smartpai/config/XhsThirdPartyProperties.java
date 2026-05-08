package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 第三方小红书数据/下载服务配置。
 *
 * <p>这层用于接 Rnote / JustOneAPI / 自建 XHS-Downloader API 等外部 provider。
 * 不在代码里固化账号、域名和 key，部署时通过环境变量配置。
 */
@Component
@ConfigurationProperties(prefix = "smartpai.xhs.third-party")
@Data
public class XhsThirdPartyProperties {

    private boolean enabled = false;
    private String provider = "generic";
    private String baseUrl = "";
    private String apiKey = "";
    private String apiKeyHeader = "X-API-Key";
    private int timeoutSeconds = 30;

    /** 笔记详情接口路径。默认按常见 provider 风格：GET {baseUrl}/api/v1/crawler/note */
    private String noteDetailPath = "/api/v1/crawler/note";

    /** 媒体下载/解析接口路径。默认：POST {baseUrl}/api/v1/crawler/note/media */
    private String mediaDownloadPath = "/api/v1/crawler/note/media";
}
