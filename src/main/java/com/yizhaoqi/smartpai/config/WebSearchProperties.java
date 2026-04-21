package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * WebSearchTool 配置。目前支持的 provider：
 *   none    (默认，未配置，工具会优雅报错)
 *   serper  (https://serper.dev, 国内/国外都可用)
 *   tavily  (https://tavily.com, LLM 友好)
 *   bing    (Azure Bing Web Search v7)
 */
@Component
@ConfigurationProperties(prefix = "web-search")
@Data
public class WebSearchProperties {
    /** none / serper / tavily / bing */
    private String provider = "none";
    /** 对应 provider 的 api key */
    private String apiKey;
}
