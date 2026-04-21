package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.handler.ChatWebSocketHandler;
import com.yizhaoqi.smartpai.handler.XhsLoginWebSocketHandler;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private ChatWebSocketHandler chatWebSocketHandler;

    @Autowired
    private XhsLoginWebSocketHandler xhsLoginWebSocketHandler;

    @Value("${security.allowed-origins:http://localhost:8080}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        registry.addHandler(chatWebSocketHandler, "/chat/{token}")
                .setAllowedOriginPatterns(origins);
        // XHS 扫码登录实时事件推送（二维码 / 扫码状态 / 成功结果）
        registry.addHandler(xhsLoginWebSocketHandler, "/ws/xhs-login/{token}")
                .setAllowedOriginPatterns(origins);
    }
}
