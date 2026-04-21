package com.yizhaoqi.smartpai.service.skill;

import com.yizhaoqi.smartpai.config.SkillProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashExecutorTest {

    private BashExecutor newExecutor(Path sandboxRoot) {
        SkillProperties props = new SkillProperties();
        SkillProperties.Bash bash = new SkillProperties.Bash();
        bash.setEnabled(true);
        bash.setTimeoutSeconds(10);
        bash.setSandboxRoot(sandboxRoot.toString());
        bash.setAllowList(List.of("echo", "ls", "sh", "cmd.exe", "cmd"));
        bash.setDenyTokens(List.of("rm", "sudo"));
        bash.setDenyPathPrefixes(List.of("/etc", "C:\\Windows"));
        props.setBash(bash);
        return new BashExecutor(props);
    }

    @Test
    void rejectsEmptyCommand(@TempDir Path tmp) {
        BashExecutor.Result r = newExecutor(tmp).run(BashExecutor.Request.of("", "t1"));
        assertNotNull(r.error());
        assertEquals("empty_command", r.error());
    }

    @Test
    void rejectsCommandOutsideAllowList(@TempDir Path tmp) {
        BashExecutor.Result r = newExecutor(tmp).run(BashExecutor.Request.of("netcat -l 80", "t1"));
        assertEquals("not_allow_listed", r.error());
        assertTrue(r.errorMessage().contains("netcat"));
    }

    @Test
    void rejectsDenyToken(@TempDir Path tmp) {
        // first token 必须合法，否则会先被 "not_allow_listed" 拦截
        BashExecutor.Result r = newExecutor(tmp).run(BashExecutor.Request.of("echo hi && rm -rf /", "t1"));
        assertEquals("deny_token", r.error());
    }

    @Test
    void rejectsDenyPathPrefix(@TempDir Path tmp) {
        BashExecutor.Result r = newExecutor(tmp).run(BashExecutor.Request.of("echo /etc/passwd", "t1"));
        assertEquals("deny_path", r.error());
    }

    @Test
    void rejectsDisabledBash(@TempDir Path tmp) {
        SkillProperties props = new SkillProperties();
        SkillProperties.Bash bash = new SkillProperties.Bash();
        bash.setEnabled(false);
        bash.setSandboxRoot(tmp.toString());
        props.setBash(bash);
        BashExecutor.Result r = new BashExecutor(props).run(BashExecutor.Request.of("echo hi", "t1"));
        assertEquals("bash_disabled", r.error());
    }

    @Test
    void denyTokenDoesNotFireOnSubstring(@TempDir Path tmp) {
        // 'skills-bundled' 含 'kill' 子串；'form_rm.txt' 含 'rm'；修复前 contains() 会误伤
        SkillProperties props = new SkillProperties();
        SkillProperties.Bash bash = new SkillProperties.Bash();
        bash.setEnabled(true);
        bash.setTimeoutSeconds(10);
        bash.setSandboxRoot(tmp.toString());
        bash.setAllowList(List.of("echo"));
        bash.setDenyTokens(List.of("rm", "kill", "su"));
        bash.setDenyPathPrefixes(List.of());
        props.setBash(bash);

        BashExecutor.Result r = new BashExecutor(props).run(
                BashExecutor.Request.of("echo ./skills-bundled/form_user/output.txt", "t1"));
        // 不应该被 deny_token 拦下
        assertFalse("deny_token".equals(r.error()), "deny token 不应命中子串");
    }

    @Test
    void containsWordBoundaryLogic() {
        assertTrue(BashExecutor.containsWord("echo hi && rm -rf /", "rm"));
        assertTrue(BashExecutor.containsWord("sudo ls", "sudo"));
        assertFalse(BashExecutor.containsWord("./skills-bundled/xhs", "kill"));
        assertFalse(BashExecutor.containsWord("/home/user/x", "su"));
        assertFalse(BashExecutor.containsWord("form.txt", "rm"));
        assertTrue(BashExecutor.containsWord("a rm b", "rm"));
    }

    @Test
    void echoHelloActuallyRuns(@TempDir Path tmp) {
        BashExecutor.Result r = newExecutor(tmp).run(BashExecutor.Request.of("echo hello-paismart", "t1"));
        // 允许跨平台：成功 exit 0 或者 cmd 路径上遇到 shell 差异时，至少也不应落在 validation error 上
        assertFalse("bash_disabled".equals(r.error())
                || "not_allow_listed".equals(r.error())
                || "deny_token".equals(r.error())
                || "deny_path".equals(r.error()));
        // 大多数环境下 stdout 会包含 hello-paismart
        if (r.success()) {
            assertTrue(r.stdout().contains("hello-paismart"));
        }
        assertTrue(tmp.resolve("t1").toFile().exists(), "session sandbox 应被自动创建");
    }
}
