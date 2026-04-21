package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Skill 子系统配置。通过 application.yml 的 "skills" 前缀覆盖。
 *
 * 默认值：
 *  - 扫描当前 CWD 下的 "./skills" 和 "./skills-bundled"；
 *  - 30 秒轮询一次 mtime；
 *  - 沙箱根目录 "./var/agent-sandbox"；
 *  - bash 启用，默认 60 秒超时，允许常见只读命令。
 *
 * 生产部署建议：把 roots 指向持久卷目录（例如 /data/paismart/skills），防止容器重启丢失 skill。
 */
@Component
@ConfigurationProperties(prefix = "skills")
@Data
public class SkillProperties {

    /** 扫描的 skill 根目录列表。每个子目录必须包含 SKILL.md */
    private List<String> roots = List.of("./skills", "./skills-bundled");

    /** 热重载轮询间隔（秒），<=0 表示不轮询，只启动时加载一次 */
    private int watchIntervalSeconds = 30;

    /** 是否启用 skill 子系统。false → SkillRegistry 为空，相关 tool 返回空列表 */
    private boolean enabled = true;

    private Bash bash = new Bash();

    @Data
    public static class Bash {
        /** 是否启用 BashTool */
        private boolean enabled = true;

        /** 单次命令最长执行秒数 */
        private int timeoutSeconds = 60;

        /** 沙箱根目录，每个会话在其下生成子目录 */
        private String sandboxRoot = "./var/agent-sandbox";

        /** 命令主程序白名单（仅匹配第一个 token；若命令用 sh -c，也只看 sh） */
        private List<String> allowList = List.of(
                "sh", "bash",
                "ls", "cat", "head", "tail", "wc", "tee",
                "grep", "rg", "sed", "awk", "find", "tr",
                "echo", "printf", "cut", "sort", "uniq",
                "jq", "yq",
                "curl", "wget",
                "python", "python3", "node", "deno",
                "git", "gh",
                "ffmpeg", "yt-dlp",
                "date", "which", "env"
        );

        /** 禁止出现在命令里的 token（即便被白名单首词覆盖也会被拒） */
        private List<String> denyTokens = List.of(
                "rm", "dd", "mkfs", "shutdown", "reboot", "halt", "poweroff",
                "chmod", "chown", "mount", "umount", "sudo", "su",
                "kill", "killall", "pkill",
                ":(){", "fork()"
        );

        /** 绝对路径禁写/禁读白名单（Agent 只能在沙箱目录内动手） */
        private List<String> denyPathPrefixes = List.of(
                "/etc", "/usr", "/var/lib", "/root", "/sys", "/proc",
                "C:\\Windows", "C:\\Program Files"
        );
    }
}
