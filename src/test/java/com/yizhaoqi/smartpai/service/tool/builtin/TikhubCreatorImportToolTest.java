package com.yizhaoqi.smartpai.service.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yizhaoqi.smartpai.model.creator.CreatorAccount;
import com.yizhaoqi.smartpai.repository.creator.CreatorAccountRepository;
import com.yizhaoqi.smartpai.service.creator.CreatorService;
import com.yizhaoqi.smartpai.service.tool.ToolContext;
import com.yizhaoqi.smartpai.service.tool.ToolResult;
import com.yizhaoqi.smartpai.service.xhs.TikhubXhsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TikhubCreatorImportToolTest {

    private TikhubXhsService tikhubService;
    private CreatorService creatorService;
    private CreatorAccountRepository accountRepository;
    private TikhubCreatorImportTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tikhubService = mock(TikhubXhsService.class);
        creatorService = mock(CreatorService.class);
        accountRepository = mock(CreatorAccountRepository.class);
        tool = new TikhubCreatorImportTool(tikhubService, creatorService, accountRepository);
    }

    private ToolContext ctx() {
        return ToolContext.builder().userId("u1").orgTag("acme").role("user").build();
    }

    @Test
    void localHit_willNotCallTikhubSearch() throws Exception {
        when(tikhubService.configured()).thenReturn(true);

        CreatorAccount account = new CreatorAccount();
        account.setId(1L);
        account.setPlatform("xhs");
        account.setPlatformUserId("5ab4695811be106505c1711a");
        account.setHandle("945087305");
        account.setDisplayName("严大小姐");
        account.setFollowers(15000L);
        account.setPosts(648L);
        account.setHomepageUrl("https://www.xiaohongshu.com/user/profile/5ab4695811be106505c1711a");

        when(creatorService.searchAccounts(any(), any()))
                .thenReturn(new PageImpl<>(List.of(account)));

        ObjectNode in = mapper.createObjectNode();
        ArrayNode keywords = in.putArray("keywords");
        keywords.add("严大小姐");

        ToolResult r = tool.call(ctx(), in);

        assertFalse(r.isError());
        assertTrue(r.summary().contains("TikHub 导入 1 个候选"));
        @SuppressWarnings("unchecked")
        var data = (java.util.Map<String, Object>) r.data();
        assertEquals(1, data.get("localHits"));
        verify(creatorService).searchAccounts(any(), any());
        verify(tikhubService, never()).searchUsers(any(), any(Integer.class));
    }
}
