package com.yizhaoqi.smartpai.service.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 权限检查返回值。Allow 可以返回一个"被清洗后的 input"——比如把用户传的绝对路径改成沙箱路径。
 * Deny 会被 Runtime 转成 tool_result 的错误回灌给 LLM，让 LLM 看到"为什么不允许"并调整策略。
 */
public sealed interface PermissionResult permits PermissionResult.Allow, PermissionResult.Deny {

    record Allow(JsonNode rewrittenInput) implements PermissionResult {}
    record Deny(String reason) implements PermissionResult {}

    static PermissionResult allow() {
        return new Allow(null);
    }

    static PermissionResult allow(JsonNode rewritten) {
        return new Allow(rewritten);
    }

    static PermissionResult deny(String reason) {
        return new Deny(reason);
    }
}
