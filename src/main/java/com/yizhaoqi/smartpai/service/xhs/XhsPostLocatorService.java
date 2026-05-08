package com.yizhaoqi.smartpai.service.xhs;

import com.yizhaoqi.smartpai.model.creator.CreatorPost;
import com.yizhaoqi.smartpai.repository.creator.CreatorPostRepository;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 通过已入库的 creator_posts 反查 noteId 对应的小红书链接。
 *
 * <p>用途：
 * - agent 只传 noteId 时，自动补回已存的 explore 链接（含 xsec_token）；
 * - 避免 TikHub 的 fetch_note_detail 因缺 xsec_token 直接 400。
 */
@Service
public class XhsPostLocatorService {

    private final CreatorPostRepository creatorPostRepository;

    public XhsPostLocatorService(CreatorPostRepository creatorPostRepository) {
        this.creatorPostRepository = creatorPostRepository;
    }

    public Optional<String> findLinkByNoteId(String noteId) {
        if (noteId == null || noteId.isBlank()) return Optional.empty();
        return creatorPostRepository.findByPlatformAndPlatformPostId("xhs", noteId.trim())
                .map(CreatorPost::getLink)
                .filter(link -> link != null && !link.isBlank());
    }
}
