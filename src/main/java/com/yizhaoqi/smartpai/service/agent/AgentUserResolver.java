package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * JWT 携带的 userId 可能是数值（User.id）也可能是 username，此类做统一解析。
 * 对照 UserService.resolveUser 的实现，提取到 agent 模块避免循环依赖。
 */
@Component
public class AgentUserResolver {

    private final UserRepository userRepository;

    public AgentUserResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User resolve(String userIdOrUsername) {
        if (userIdOrUsername == null || userIdOrUsername.isBlank()) {
            throw new IllegalArgumentException("userId 为空");
        }
        try {
            Long id = Long.parseLong(userIdOrUsername);
            return userRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userIdOrUsername));
        } catch (NumberFormatException ignore) {
            return userRepository.findByUsername(userIdOrUsername)
                    .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userIdOrUsername));
        }
    }
}
