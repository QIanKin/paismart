package com.yizhaoqi.smartpai.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.service.ParseService;
import com.yizhaoqi.smartpai.service.UsageQuotaService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一处理带图片的用户消息：
 * - 写库时只落文本 + objectKey 引用；
 * - 发给模型时再转回 OpenAI-compatible 的多模态 content 数组；
 * - 返回给前端历史消息时附上可预览 URL。
 */
@Service
public class AgentMessageContentService {

    private static final Logger logger = LoggerFactory.getLogger(AgentMessageContentService.class);

    private static final String SCHEMA = "agent_user_multimodal_v1";
    public static final int TOKENS_PER_IMAGE = 300;
    private static final int MAX_DOCUMENT_CHARS_PER_FILE = 16_000;
    private static final int MAX_DOCUMENT_TOTAL_CHARS = 48_000;

    private final ObjectMapper objectMapper;
    private final AgentAssetService assetService;
    private final ParseService parseService;
    private final UsageQuotaService usageQuotaService;
    private final boolean inlineImageDataUrlForModel;

    public AgentMessageContentService(ObjectMapper objectMapper,
                                     AgentAssetService assetService,
                                     ParseService parseService,
                                     UsageQuotaService usageQuotaService,
                                     @Value("${agent.inline-image-data-url-for-model:true}") boolean inlineImageDataUrlForModel) {
        this.objectMapper = objectMapper;
        this.assetService = assetService;
        this.parseService = parseService;
        this.usageQuotaService = usageQuotaService;
        this.inlineImageDataUrlForModel = inlineImageDataUrlForModel;
    }

    public String encodeUserContent(String text, List<AgentRequest.Attachment> attachments) {
        String safeText = StringUtils.defaultString(text);
        List<AgentRequest.Attachment> safeAttachments = attachments == null ? List.of() : attachments;
        if (safeAttachments.isEmpty()) {
            return safeText;
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "schema", SCHEMA,
                    "text", safeText,
                    "attachments", safeAttachments
            ));
        } catch (Exception e) {
            throw new RuntimeException("序列化带图片用户消息失败: " + e.getMessage(), e);
        }
    }

    public Object toOpenAiUserContent(String text, List<AgentRequest.Attachment> attachments) {
        return assembleForModel(text, attachments).content();
    }

    public Object toOpenAiUserContent(String rawStoredContent) {
        DecodedUserContent decoded = decode(rawStoredContent);
        if (!decoded.multimodal()) {
            return decoded.text();
        }
        return toOpenAiUserContent(decoded.text(), decoded.attachments());
    }

    public HistoryUserContent toHistoryUserContent(String rawStoredContent) {
        DecodedUserContent decoded = decode(rawStoredContent);
        if (decoded.attachments().isEmpty()) {
            return new HistoryUserContent(decoded.text(), List.of());
        }

        List<HistoryAttachment> out = new ArrayList<>();
        for (AgentRequest.Attachment attachment : decoded.attachments()) {
            String url = safeGenerateUrl(attachment.objectKey());
            if (url == null) continue;
            out.add(new HistoryAttachment(
                    attachment.type(),
                    attachment.objectKey(),
                    attachment.fileName(),
                    attachment.mimeType(),
                    attachment.size(),
                    url
            ));
        }
        return new HistoryUserContent(decoded.text(), out);
    }

    public int estimateUserInputTokens(String text, List<AgentRequest.Attachment> attachments, int textTokens) {
        return assembleForModel(text, attachments).estimatedTokens();
    }

    public ModelUserContent assembleForModel(String text, List<AgentRequest.Attachment> attachments) {
        String safeText = StringUtils.defaultString(text);
        List<AgentRequest.Attachment> safeAttachments = attachments == null ? List.of() : attachments;
        if (safeAttachments.isEmpty()) {
            return new ModelUserContent(safeText, usageQuotaService.estimateTextTokens(safeText));
        }

        String textWithDocuments = appendDocumentContents(safeText, safeAttachments);
        List<Map<String, Object>> parts = new ArrayList<>();
        boolean hasImage = false;
        if (!textWithDocuments.isBlank()) {
            parts.add(Map.of("type", "text", "text", textWithDocuments));
        }

        int estimatedTokens = usageQuotaService.estimateTextTokens(textWithDocuments);
        for (AgentRequest.Attachment attachment : safeAttachments) {
            if (!isImageAttachment(attachment)) {
                continue;
            }
            String url = safeGenerateModelImageUrl(attachment);
            if (url == null) {
                continue;
            }
            Map<String, Object> imageUrl = new LinkedHashMap<>();
            imageUrl.put("url", url);

            Map<String, Object> part = new LinkedHashMap<>();
            part.put("type", "image_url");
            part.put("image_url", imageUrl);
            parts.add(part);
            hasImage = true;
            estimatedTokens += TOKENS_PER_IMAGE;
        }

        if (hasImage) {
            return new ModelUserContent(parts, estimatedTokens);
        }
        return new ModelUserContent(textWithDocuments, estimatedTokens);
    }

    private DecodedUserContent decode(String rawStoredContent) {
        if (StringUtils.isBlank(rawStoredContent)) {
            return new DecodedUserContent("", List.of(), false);
        }
        try {
            StoredEnvelope env = objectMapper.readValue(rawStoredContent, StoredEnvelope.class);
            if (!StringUtils.equals(SCHEMA, env.schema())) {
                return new DecodedUserContent(rawStoredContent, List.of(), false);
            }
            List<AgentRequest.Attachment> attachments = env.attachments() == null
                    ? List.of()
                    : env.attachments().stream()
                    .filter(item -> item != null && StringUtils.isNotBlank(item.objectKey()))
                    .map(item -> new AgentRequest.Attachment(
                            StringUtils.defaultIfBlank(item.type(), "image"),
                            item.objectKey(),
                            item.fileName(),
                            item.mimeType(),
                            item.size()
                    ))
                    .toList();
            return new DecodedUserContent(StringUtils.defaultString(env.text()), attachments, !attachments.isEmpty());
        } catch (Exception e) {
            return new DecodedUserContent(rawStoredContent, List.of(), false);
        }
    }

    private String safeGenerateUrl(String objectKey) {
        if (StringUtils.isBlank(objectKey)) return null;
        try {
            return assetService.generateViewUrl(objectKey);
        } catch (Exception e) {
            logger.warn("生成聊天附件访问地址失败 objectKey={} err={}", objectKey, e.getMessage());
            return null;
        }
    }

    private String safeGenerateModelImageUrl(AgentRequest.Attachment attachment) {
        if (attachment == null || StringUtils.isBlank(attachment.objectKey())) return null;
        try {
            if (inlineImageDataUrlForModel) {
                return assetService.generateModelDataUrl(attachment.objectKey(), attachment.mimeType());
            }
            return assetService.generateViewUrl(attachment.objectKey());
        } catch (Exception e) {
            logger.warn("生成模型图片输入失败 objectKey={} err={}", attachment.objectKey(), e.getMessage());
            return null;
        }
    }

    private String appendDocumentContents(String text, List<AgentRequest.Attachment> attachments) {
        StringBuilder out = new StringBuilder(StringUtils.defaultString(text));
        int consumedChars = 0;
        int documentIndex = 0;

        for (AgentRequest.Attachment attachment : attachments) {
            if (isImageAttachment(attachment)) {
                continue;
            }
            int remainingChars = MAX_DOCUMENT_TOTAL_CHARS - consumedChars;
            if (remainingChars <= 0) {
                if (out.length() > 0) {
                    out.append("\n\n");
                }
                out.append("[其余文档内容因上下文长度限制已省略]");
                break;
            }

            DocumentExtraction extraction = safeExtractDocumentText(attachment, Math.min(MAX_DOCUMENT_CHARS_PER_FILE, remainingChars));
            if (extraction == null || extraction.text().isBlank()) {
                continue;
            }

            if (out.length() > 0) {
                out.append("\n\n");
            }
            documentIndex++;
            out.append("[附件文档 ").append(documentIndex).append("]\n");
            out.append("文件名: ").append(StringUtils.defaultIfBlank(attachment.fileName(), "未命名")).append('\n');
            out.append("类型: ").append(StringUtils.defaultIfBlank(attachment.mimeType(), "unknown")).append('\n');
            out.append("以下为抽取文本").append(extraction.truncated() ? "（已截断）" : "").append(":\n");
            out.append(extraction.text());
            out.append("\n[附件文档结束]");
            consumedChars += extraction.text().length();
        }

        return out.toString();
    }

    private DocumentExtraction safeExtractDocumentText(AgentRequest.Attachment attachment, int maxChars) {
        if (attachment == null || StringUtils.isBlank(attachment.objectKey()) || maxChars <= 0) {
            return null;
        }
        try (var inputStream = assetService.openObjectStream(attachment.objectKey())) {
            String extracted = parseService.extractPlainText(inputStream, attachment.fileName());
            if (extracted == null || extracted.isBlank()) {
                return null;
            }
            String normalized = extracted.strip();
            boolean truncated = normalized.length() > maxChars;
            if (truncated) {
                normalized = normalized.substring(0, maxChars) + "\n…";
            }
            return new DocumentExtraction(normalized, truncated);
        } catch (Exception e) {
            logger.warn("抽取聊天文档文本失败 objectKey={} fileName={} err={}",
                    attachment.objectKey(), attachment.fileName(), e.getMessage());
            return new DocumentExtraction("[该附件暂时无法抽取文本，请用户改传纯文本或较简单的文档格式。]", false);
        }
    }

    private boolean isImageAttachment(AgentRequest.Attachment attachment) {
        if (attachment == null) {
            return false;
        }
        if (attachment.mimeType() != null && attachment.mimeType().toLowerCase().startsWith("image/")) {
            return true;
        }
        return StringUtils.equalsIgnoreCase(attachment.type(), "image");
    }

    private record StoredEnvelope(
            String schema,
            String text,
            List<StoredAttachment> attachments
    ) {}

    private record StoredAttachment(
            String type,
            String objectKey,
            String fileName,
            String mimeType,
            Long size
    ) {}

    private record DecodedUserContent(
            String text,
            List<AgentRequest.Attachment> attachments,
            boolean multimodal
    ) {}

    public record HistoryUserContent(
            String text,
            List<HistoryAttachment> attachments
    ) {}

    public record ModelUserContent(
            Object content,
            int estimatedTokens
    ) {}

    public record HistoryAttachment(
            String type,
            String objectKey,
            String fileName,
            String mimeType,
            Long size,
            String url
    ) {}

    private record DocumentExtraction(
            String text,
            boolean truncated
    ) {}
}
