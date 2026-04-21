package com.yizhaoqi.smartpai.service.tool.builtin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileEditToolApplyTest {

    @Test
    void uniqueMatchReplaces() {
        String src = "alpha\nbeta\ngamma\n";
        FileEditTool.EditOutcome out = FileEditTool.applyEdit(src, "beta", "BETA", false);
        assertNull(out.error);
        assertEquals(1, out.replacements);
        assertEquals("alpha\nBETA\ngamma\n", out.newContent);
        assertNotNull(out.diffPreview);
        assertTrue(out.diffPreview.contains("- beta"));
        assertTrue(out.diffPreview.contains("+ BETA"));
    }

    @Test
    void nonUniqueWithoutReplaceAllFails() {
        String src = "x\nx\ny\n";
        FileEditTool.EditOutcome out = FileEditTool.applyEdit(src, "x", "X", false);
        assertNotNull(out.error);
        assertTrue(out.error.contains("出现 2 次"));
    }

    @Test
    void replaceAllHitsEverything() {
        String src = "x\nx\ny\n";
        FileEditTool.EditOutcome out = FileEditTool.applyEdit(src, "x", "X", true);
        assertNull(out.error);
        assertEquals(2, out.replacements);
        assertEquals("X\nX\ny\n", out.newContent);
    }

    @Test
    void missingOldStringFails() {
        FileEditTool.EditOutcome out = FileEditTool.applyEdit("abc", "zzz", "Z", false);
        assertNotNull(out.error);
        assertTrue(out.error.contains("未找到"));
    }

    @Test
    void preservesPrefixAndSuffix() {
        String src = "pre-mid-suf";
        FileEditTool.EditOutcome out = FileEditTool.applyEdit(src, "mid", "MIDDLE", false);
        assertEquals("pre-MIDDLE-suf", out.newContent);
        assertFalse(out.newContent.contains("mid"));
    }

    @Test
    void respectsIndentationInMatch() {
        String src = "def foo():\n    return 1\n\ndef bar():\n    return 2\n";
        FileEditTool.EditOutcome out = FileEditTool.applyEdit(src,
                "def bar():\n    return 2\n",
                "def bar():\n    return 42\n",
                false);
        assertNull(out.error);
        assertTrue(out.newContent.contains("return 42"));
        assertTrue(out.newContent.contains("return 1"));
    }
}
