package com.yizhaoqi.smartpai.service.skill;

import com.yizhaoqi.smartpai.config.SkillProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * 沙箱路径解析器：所有 FS / Bash 工具访问文件前必须过一遍本类，避免越权。
 *
 * 规则（保守）：
 *  1. 入参路径相对时：根据 sessionId 算出 sandbox/sessions/session-&lt;id&gt;/，挂在它下面；
 *  2. 入参路径绝对时：必须落在 {@link SkillProperties#getRoots}（只读）、
 *     sandboxRoot（读写）、或"注册在 registry 里的 skill 的 rootPath"（只读）之一的子树中；
 *  3. denyPathPrefixes 里的前缀硬拒绝（兜底防御，避免配置疏忽）；
 *  4. 所有校验基于 {@link Path#toRealPath(java.nio.file.LinkOption...)} 或 normalize，
 *     防止 {@code ../} 逃逸与符号链接逃逸。
 *
 * "访问模式" {@link Access}：
 *  - READ：可以读任何被允许的目录（sandbox、skills 根、skill rootPath）
 *  - WRITE：必须是 sandbox 子路径
 */
@Component
public class SandboxPathResolver {

    private final SkillProperties properties;
    private final SkillRegistry skillRegistry;

    public SandboxPathResolver(SkillProperties properties, SkillRegistry skillRegistry) {
        this.properties = properties;
        this.skillRegistry = skillRegistry;
    }

    public Path sandboxRoot() {
        return Paths.get(properties.getBash().getSandboxRoot()).toAbsolutePath().normalize();
    }

    public Path sessionSandbox(String sessionId) throws IOException {
        String safe = safeDirName(sessionId == null ? "default" : sessionId);
        Path p = sandboxRoot().resolve(safe);
        Files.createDirectories(p);
        return p;
    }

    /**
     * 把 userPath 解析为绝对 Path 并校验合法性。
     *
     * @throws PathDeniedException 校验失败
     */
    public Path resolve(String userPath, String sessionId, Access access) throws IOException {
        if (userPath == null || userPath.isBlank()) {
            throw new PathDeniedException("path 为空");
        }
        Path sandbox = sessionSandbox(sessionId);
        Path raw = Paths.get(userPath);
        Path abs = raw.isAbsolute() ? raw.normalize() : sandbox.resolve(raw).normalize();

        // deny prefix 黑名单（跨平台：同时比较原样 + 统一用正斜杠的形式，避免 Windows \ vs POSIX /）
        String absLower = abs.toString().toLowerCase(Locale.ROOT);
        String absSlashed = absLower.replace('\\', '/');
        for (String deny : properties.getBash().getDenyPathPrefixes()) {
            String d = deny.toLowerCase(Locale.ROOT);
            String dSlashed = d.replace('\\', '/');
            if (absLower.startsWith(d) || absSlashed.startsWith(dSlashed)) {
                throw new PathDeniedException("路径落在禁止前缀内: " + deny);
            }
        }

        boolean underSandbox = abs.startsWith(sandboxRoot());
        if (access == Access.WRITE) {
            if (!underSandbox) {
                throw new PathDeniedException("写入必须在沙箱目录内: " + sandboxRoot() + " 而不是 " + abs);
            }
            return abs;
        }

        // READ
        if (underSandbox) return abs;

        // 允许读 skills 根下的内容（SKILL.md、references、scripts 文本）
        for (String root : properties.getRoots()) {
            Path skillsRoot = Paths.get(root).toAbsolutePath().normalize();
            if (abs.startsWith(skillsRoot)) return abs;
        }
        // 允许读已注册 skill 的 rootPath 下
        for (LoadedSkill s : skillRegistry.all()) {
            if (s.rootPath() == null) continue;
            Path r = Paths.get(s.rootPath()).toAbsolutePath().normalize();
            if (abs.startsWith(r)) return abs;
        }

        throw new PathDeniedException("路径不在允许的只读根范围内: " + abs);
    }

    private String safeDirName(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') sb.append(c);
            else sb.append('_');
        }
        return sb.length() == 0 ? "default" : sb.toString();
    }

    public enum Access { READ, WRITE }

    public static final class PathDeniedException extends IOException {
        public PathDeniedException(String message) { super(message); }
    }
}
