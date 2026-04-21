package com.yizhaoqi.smartpai.service.skill;

import com.yizhaoqi.smartpai.config.SkillProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxPathResolverTest {

    private SkillProperties props;
    private SkillRegistry registry;

    @BeforeEach
    void setup(@TempDir Path tmp) {
        props = new SkillProperties();
        props.setRoots(List.of(tmp.resolve("skills").toString()));
        SkillProperties.Bash bash = new SkillProperties.Bash();
        bash.setSandboxRoot(tmp.resolve("sandbox").toString());
        bash.setDenyPathPrefixes(List.of("/etc", "C:\\Windows"));
        props.setBash(bash);
        registry = new SkillRegistry(new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void relativePathResolvedIntoSessionSandbox() throws IOException {
        SandboxPathResolver r = new SandboxPathResolver(props, registry);
        Path p = r.resolve("output/data.csv", "sess-1", SandboxPathResolver.Access.WRITE);
        assertTrue(p.toString().contains("sess-1"));
        assertTrue(p.toString().endsWith("data.csv"));
    }

    @Test
    void writeOutsideSandboxIsDenied() {
        SandboxPathResolver r = new SandboxPathResolver(props, registry);
        assertThrows(SandboxPathResolver.PathDeniedException.class,
                () -> r.resolve("/tmp/evil.txt", "sess-1", SandboxPathResolver.Access.WRITE));
    }

    @Test
    void pathTraversalEscapeIsDenied() {
        SandboxPathResolver r = new SandboxPathResolver(props, registry);
        // 相对路径中 ../../ 想穿回 sandbox 上面；normalize 后必然不在 sandboxRoot 里 → WRITE 必 deny
        assertThrows(SandboxPathResolver.PathDeniedException.class,
                () -> r.resolve("../../escape.txt", "sess-1", SandboxPathResolver.Access.WRITE));
    }

    @Test
    void denyPrefixIsHonored() {
        SandboxPathResolver r = new SandboxPathResolver(props, registry);
        assertThrows(SandboxPathResolver.PathDeniedException.class,
                () -> r.resolve("/etc/passwd", "sess-1", SandboxPathResolver.Access.READ));
    }

    @Test
    void sessionSandboxIsCreatedOnDemand() throws IOException {
        SandboxPathResolver r = new SandboxPathResolver(props, registry);
        Path sb = r.sessionSandbox("weird/chars*");
        assertTrue(sb.toString().contains("weird_chars_") || sb.toString().contains("weird_chars")
                || sb.toString().contains("default"));
        // writable
        assertTrue(sb.toFile().exists());
        assertTrue(sb.toFile().isDirectory());
    }

    @Test
    void emptyPathRejected() {
        SandboxPathResolver r = new SandboxPathResolver(props, registry);
        assertThrows(SandboxPathResolver.PathDeniedException.class,
                () -> r.resolve("", "sess-1", SandboxPathResolver.Access.READ));
    }

    @Test
    void accessEnumCoversReadAndWrite() {
        assertEquals(2, SandboxPathResolver.Access.values().length);
    }
}
