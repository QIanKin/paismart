package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.config.SkillProperties;
import com.yizhaoqi.smartpai.service.skill.BashExecutor;
import com.yizhaoqi.smartpai.service.skill.LoadedSkill;
import com.yizhaoqi.smartpai.service.skill.SkillRegistry;
import com.yizhaoqi.smartpai.service.tool.PermissionResult;
import com.yizhaoqi.smartpai.service.tool.Tool;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolInputSchemas;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bash 工具：在沙箱中执行命令。严格受 {@link SkillProperties.Bash} 白/黑名单管控。
 *
 * 典型用法：
 *  1. 纯命令：{@code {"command":"ls -al"}}；
 *  2. 带 skill：{@code {"command":"refresh_xhs_tracks.sh --limit 50","skill":"xhs-tracks"}}
 *     → 会把该 skill 的 scripts 目录追加到 PATH 头部，脚本名可直接调用。
 *
 * 权限：
 *  - 所有登录用户可执行（白名单已经收得很严）；
 *  - 如果命令里出现 sudo / rm 等 deny token，Tool 直接 deny，不进 executor。
 */
@Component
@ConditionalOnProperty(prefix = "skills.bash", name = "enabled", matchIfMissing = true)
public class BashTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(BashTool.class);

    private final BashExecutor executor;
    private final SkillRegistry skillRegistry;
    private final JsonNode schema;

    public BashTool(BashExecutor executor, SkillRegistry skillRegistry) {
        this.executor = executor;
        this.skillRegistry = skillRegistry;
        this.schema = ToolInputSchemas.object()
                .stringProp("command", "要执行的 shell 命令（可管道、重定向，但首个主程序必须在白名单）", true)
                .stringProp("skill", "可选；指定使用哪个 skill 的 scripts/ 目录注入 PATH（便于直接用脚本名调用）", false)
                .integerProp("timeoutSeconds", "可选；单次超时，默认 60s，最长 300s", false)
                .additionalProperties(false)
                .build();
    }

    @Override public String name() { return "bash"; }
    @Override public String description() {
        return "在会话级沙箱目录中执行 shell 命令。严格白名单，禁止破坏性命令，禁止访问系统路径。"
                + "适用：调用 skill scripts、下载文件、调用 CLI 查询数据。";
    }
    @Override public JsonNode inputSchema() { return schema; }

    @Override public boolean isReadOnly(JsonNode input) { return false; }
    @Override public boolean isConcurrencySafe(JsonNode input) { return false; }

    @Override
    public PermissionResult checkPermission(ToolContext ctx, JsonNode input) {
        String cmd = input == null ? null : input.path("command").asText(null);
        if (cmd == null || cmd.isBlank()) {
            return PermissionResult.deny("command 为空");
        }
        return PermissionResult.allow();
    }

    @Override
    public ToolResult call(ToolContext ctx, JsonNode input) {
        String cmd = input.path("command").asText("");
        String skillName = input.has("skill") && !input.get("skill").isNull()
                ? input.get("skill").asText(null) : null;
        int timeout = input.has("timeoutSeconds") ? Math.min(300, input.get("timeoutSeconds").asInt(0)) : 0;

        List<String> extraPath = new ArrayList<>();
        LoadedSkill skill = null;
        if (skillName != null && !skillName.isBlank()) {
            skill = skillRegistry.find(skillName, ctx.orgTag()).orElse(null);
            if (skill == null) {
                return ToolResult.error("skill 不存在或不可见: " + skillName);
            }
            if (skill.rootPath() != null) {
                extraPath.add(skill.rootPath() + java.io.File.separator + "scripts");
            }
        }

        BashExecutor.Request req = BashExecutor.Request.of(cmd, ctx.sessionId());
        req.extraPathEntries = extraPath;
        req.timeoutSeconds = timeout;
        req.cancelled = ctx.cancelled();

        ctx.emitProgress("running", "执行命令: " + cmd, Map.of(
                "skill", skillName == null ? "" : skillName,
                "workDir", "session-" + ctx.sessionId()));
        BashExecutor.Result out = executor.run(req);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exitCode", out.exitCode());
        data.put("stopReason", out.stopReason());
        data.put("stdout", out.stdout());
        data.put("stderr", out.stderr());
        data.put("workDir", out.workDir());
        if (out.error() != null) {
            data.put("error", out.error());
            data.put("errorMessage", out.errorMessage());
        }

        String summary = out.success()
                ? "exit=0, stdout " + out.stdout().length() + " 字符"
                : ("失败 reason=" + out.stopReason()
                        + (out.error() != null ? ", err=" + out.error() : "")
                        + ", exit=" + out.exitCode());
        logger.info("bash tool user={} session={} skill={} success={} summary={}",
                ctx.userId(), ctx.sessionId(), skillName, out.success(), summary);

        if (!out.success()) {
            return new ToolResult(data, summary, Map.of("tool", "bash"), true);
        }
        return ToolResult.of(data, summary, Map.of("tool", "bash"));
    }
}
