package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.config.UsageQuotaProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmProviderRouterTest {

    @Test
    void shouldNotTreatInlineImageDataUrlAsHugePromptText() throws Exception {
        UsageQuotaService usageQuotaService = new UsageQuotaService(
                Mockito.mock(StringRedisTemplate.class),
                new UsageQuotaProperties()
        );
        LlmProviderRouter router = new LlmProviderRouter(
                new AiProperties(),
                Mockito.mock(RateLimitService.class),
                usageQuotaService,
                Mockito.mock(ModelProviderConfigService.class),
                new ObjectMapper()
        );

        String dataUrl = "data:image/jpeg;base64," + "A".repeat(200_000);
        List<Map<String, Object>> messages = List.of(
                Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", "帮我识别这张图"),
                                Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))
                        )
                )
        );

        Method method = LlmProviderRouter.class.getDeclaredMethod("estimateAgentPromptTokens", List.class);
        method.setAccessible(true);
        int estimated = (int) method.invoke(router, messages);

        assertTrue(estimated < 5000, "图片不应因 data URL 被估算成超大 prompt: " + estimated);
    }
}
