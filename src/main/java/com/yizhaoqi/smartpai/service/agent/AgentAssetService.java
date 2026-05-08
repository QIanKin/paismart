package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.config.MinioConfig;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Agent 聊天里的附件资产管理。
 *
 * <p>设计目标：
 * 1. 前端上传附件后只在消息里持久化 objectKey，不把二进制塞进数据库；
 * 2. 真正发给模型时再临时生成带签名的可访问 URL 或按需读取对象流；
 * 3. 复用现有 MinIO / nginx / minio-proxy 配置，不新引入对象存储通道。
 */
@Service
public class AgentAssetService {

    private static final Logger logger = LoggerFactory.getLogger(AgentAssetService.class);

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024L;
    private static final long MAX_DOCUMENT_BYTES = 20L * 1024 * 1024L;
    /** 视频/音频上限：与 TikhubProperties.maxVideoBytes 同量级，工具层已预校验，这里做兜底。 */
    private static final long MAX_VIDEO_BYTES = 200L * 1024 * 1024L;
    private static final long MAX_AUDIO_BYTES = 50L * 1024 * 1024L;
    private static final long MAX_TRANSCRIPT_BYTES = 4L * 1024 * 1024L;
    private static final int VIEW_URL_EXPIRE_DAYS = 7;
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif"
    );
    private static final Set<String> ALLOWED_DOCUMENT_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "csv", "txt", "md"
    );
    private static final Set<String> ALLOWED_DOCUMENT_MIME_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "text/csv",
            "text/plain",
            "text/markdown",
            "text/x-markdown"
    );
    private static final Set<String> GENERIC_MIME_TYPES = Set.of(
            "",
            "application/octet-stream",
            "binary/octet-stream"
    );

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;
    private final String bucketName;

    public AgentAssetService(MinioClient minioClient,
                             MinioConfig minioConfig,
                             @Value("${minio.bucketName:uploads}") String bucketName) {
        this.minioClient = minioClient;
        this.minioConfig = minioConfig;
        this.bucketName = bucketName;
    }

    public UploadedImage uploadChatImage(MultipartFile file, String userId, String orgTag) {
        UploadedAttachment uploaded = uploadChatAttachment(file, userId, orgTag);
        return new UploadedImage(
                uploaded.type(),
                uploaded.objectKey(),
                uploaded.fileName(),
                uploaded.mimeType(),
                uploaded.size(),
                uploaded.url()
        );
    }

    public UploadedAttachment uploadChatAttachment(MultipartFile file, String userId, String orgTag) {
        validateAttachment(file);
        String fileName = safeFileName(file.getOriginalFilename());
        String mimeType = normalizeMimeType(file.getContentType(), fileName);
        String type = classifyAttachmentType(fileName, mimeType);
        String ext = extensionFor(fileName, mimeType);
        LocalDate today = LocalDate.now();
        String objectKey = String.format("%s/%s/%s/%04d/%02d/%02d/%s.%s",
                "image".equals(type) ? "agent-chat-images" : "agent-chat-files",
                sanitizePathSegment(orgTag),
                sanitizePathSegment(userId),
                today.getYear(),
                today.getMonthValue(),
                today.getDayOfMonth(),
                UUID.randomUUID(),
                ext);

        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(mimeType)
                            .build()
            );
            String url = generateViewUrl(objectKey);
            logger.info("聊天附件上传成功 userId={} orgTag={} objectKey={} size={} type={}",
                    userId, orgTag, objectKey, file.getSize(), type);
            return new UploadedAttachment(
                    type,
                    objectKey,
                    fileName,
                    mimeType,
                    file.getSize(),
                    url
            );
        } catch (Exception e) {
            logger.error("聊天附件上传失败 userId={} orgTag={} fileName={} err={}",
                    userId, orgTag, fileName, e.getMessage(), e);
            throw new RuntimeException("附件上传失败: " + e.getMessage(), e);
        }
    }

    public String generateViewUrl(String objectKey) {
        try {
            String presigned = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .method(Method.GET)
                            .expiry(VIEW_URL_EXPIRE_DAYS, TimeUnit.DAYS)
                            .build()
            );
            return toPublicUrl(presigned);
        } catch (Exception e) {
            throw new RuntimeException("生成附件访问地址失败: " + e.getMessage(), e);
        }
    }

    public String generateModelDataUrl(String objectKey, String mimeType) {
        String safeMimeType = StringUtils.defaultIfBlank(mimeType, "image/jpeg");
        try (InputStream inputStream = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(bucketName)
                        .object(objectKey)
                        .build()
        )) {
            byte[] bytes = inputStream.readAllBytes();
            return "data:" + safeMimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            throw new RuntimeException("生成模型图片 data URL 失败: " + e.getMessage(), e);
        }
    }

    public InputStream openObjectStream(String objectKey) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("读取附件失败: " + e.getMessage(), e);
        }
    }

    // ============================================================
    // Phase 4A: 视频 / 音频 / transcript 资产入库（供 xhs_video_analyze 等工具使用）
    // ============================================================

    /**
     * 把一个本地视频文件上传到 MinIO，返回签名 URL + objectKey。
     *
     * <p>命名空间：{@code agent-chat-videos/<orgTag>/<userId>/<yyyy>/<MM>/<dd>/<uuid>.<ext>}。
     * 与 image / file 资产采用同样的 7 天预签名策略，前端能直接 video 标签播放。
     */
    public StoredAsset uploadVideoAsset(Path file, String orgTag, String userId,
                                        String mimeType, String suggestedFileName) {
        return uploadGenericAsset(file, orgTag, userId, mimeType, suggestedFileName,
                "video", "agent-chat-videos", MAX_VIDEO_BYTES, "mp4");
    }

    /**
     * 把一个本地音频文件上传到 MinIO，返回签名 URL + objectKey。
     * 注意：这个 URL 必须能被 DashScope 公网访问（部署时把 MINIO_PUBLIC_URL 配成公网域名）。
     */
    public StoredAsset uploadAudioAsset(Path file, String orgTag, String userId,
                                        String mimeType, String suggestedFileName) {
        return uploadGenericAsset(file, orgTag, userId, mimeType, suggestedFileName,
                "audio", "agent-chat-audios", MAX_AUDIO_BYTES, "mp3");
    }

    /**
     * 把 transcript 文本（已包含完整识别结果）落 MinIO，方便 Agent 后续 file_read。
     */
    public StoredAsset uploadTranscriptAsset(String text, String orgTag, String userId,
                                             String suggestedFileName) {
        if (text == null) text = "";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TRANSCRIPT_BYTES) {
            throw new IllegalArgumentException("transcript 大小 " + bytes.length
                    + " 超过上限 " + MAX_TRANSCRIPT_BYTES);
        }
        String fileName = StringUtils.defaultIfBlank(suggestedFileName, "transcript.txt");
        String ext = StringUtils.defaultIfBlank(extension(fileName), "txt");
        LocalDate today = LocalDate.now();
        String objectKey = String.format("%s/%s/%s/%04d/%02d/%02d/%s.%s",
                "agent-chat-transcripts",
                sanitizePathSegment(orgTag),
                sanitizePathSegment(userId),
                today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
                UUID.randomUUID(), ext);

        try (InputStream inputStream = new java.io.ByteArrayInputStream(bytes)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(inputStream, bytes.length, -1)
                            .contentType("text/plain; charset=utf-8")
                            .build()
            );
            String url = generateViewUrl(objectKey);
            logger.info("transcript 上传成功 userId={} orgTag={} objectKey={} bytes={}",
                    userId, orgTag, objectKey, bytes.length);
            return new StoredAsset("transcript", objectKey, fileName, "text/plain", bytes.length, url);
        } catch (Exception e) {
            logger.error("transcript 上传失败 userId={} orgTag={} err={}", userId, orgTag, e.getMessage(), e);
            throw new RuntimeException("transcript 上传失败: " + e.getMessage(), e);
        }
    }

    private StoredAsset uploadGenericAsset(Path file, String orgTag, String userId,
                                           String mimeType, String suggestedFileName,
                                           String type, String pathPrefix,
                                           long maxBytes, String defaultExt) {
        if (file == null) throw new IllegalArgumentException("file 不能为空");
        long size;
        try {
            size = Files.size(file);
        } catch (Exception e) {
            throw new RuntimeException("无法读取文件大小: " + e.getMessage(), e);
        }
        if (size <= 0) throw new IllegalArgumentException(type + " 文件为空");
        if (size > maxBytes) {
            throw new IllegalArgumentException(type + " 大小 " + size + " 超过上限 " + maxBytes);
        }
        String fileName = StringUtils.defaultIfBlank(suggestedFileName, file.getFileName().toString());
        String ext = StringUtils.defaultIfBlank(extension(fileName), defaultExt);
        String safeMime = StringUtils.defaultIfBlank(mimeType,
                "video".equals(type) ? "video/mp4" : "audio".equals(type) ? "audio/mpeg"
                        : "application/octet-stream");
        LocalDate today = LocalDate.now();
        String objectKey = String.format("%s/%s/%s/%04d/%02d/%02d/%s.%s",
                pathPrefix,
                sanitizePathSegment(orgTag),
                sanitizePathSegment(userId),
                today.getYear(), today.getMonthValue(), today.getDayOfMonth(),
                UUID.randomUUID(), ext);

        try (InputStream inputStream = Files.newInputStream(file)) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(inputStream, size, -1)
                            .contentType(safeMime)
                            .build()
            );
            String url = generateViewUrl(objectKey);
            logger.info("{} 上传成功 userId={} orgTag={} objectKey={} size={}",
                    type, userId, orgTag, objectKey, size);
            return new StoredAsset(type, objectKey, fileName, safeMime, size, url);
        } catch (Exception e) {
            logger.error("{} 上传失败 userId={} orgTag={} err={}", type, userId, orgTag, e.getMessage(), e);
            throw new RuntimeException(type + " 上传失败: " + e.getMessage(), e);
        }
    }

    // ============================================================

    private void validateAttachment(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择附件");
        }
        String fileName = safeFileName(file.getOriginalFilename());
        String mimeType = normalizeMimeType(file.getContentType(), fileName);
        String type = classifyAttachmentType(fileName, mimeType);
        if ("image".equals(type)) {
            if (file.getSize() > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("图片不能超过 10MB");
            }
            if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
                throw new IllegalArgumentException("仅支持 JPG / PNG / WEBP / GIF 图片");
            }
            return;
        }
        if ("document".equals(type)) {
            if (file.getSize() > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("文档不能超过 20MB");
            }
            if (!isAllowedDocument(fileName, mimeType)) {
                throw new IllegalArgumentException("仅支持 PDF / Word / Excel / CSV / TXT / Markdown");
            }
            return;
        }
        throw new IllegalArgumentException("暂不支持该附件类型");
    }

    private String normalizeMimeType(String rawMimeType, String fileName) {
        String ext = extension(fileName);
        String inferredMimeType = switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "csv" -> "text/csv";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            default -> "application/octet-stream";
        };
        String mimeType = rawMimeType == null ? "" : rawMimeType.trim().toLowerCase(Locale.ROOT);
        if (GENERIC_MIME_TYPES.contains(mimeType)) {
            return inferredMimeType;
        }
        if ("application/zip".equals(mimeType) && ("docx".equals(ext) || "xlsx".equals(ext))) {
            return inferredMimeType;
        }
        return mimeType;
    }

    private String extensionFor(String fileName, String mimeType) {
        String ext = extension(fileName);
        if (!ext.isBlank()) return ext;
        return switch (mimeType) {
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "application/pdf" -> "pdf";
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "text/csv" -> "csv";
            case "text/plain" -> "txt";
            case "text/markdown", "text/x-markdown" -> "md";
            default -> "bin";
        };
    }

    private String extension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String safeFileName(String original) {
        String fileName = StringUtils.defaultIfBlank(original, "attachment");
        return fileName.replaceAll("[\\r\\n\\\\/]+", "_");
    }

    private String classifyAttachmentType(String fileName, String mimeType) {
        if (mimeType != null && mimeType.startsWith("image/")) {
            return "image";
        }
        if (isAllowedDocument(fileName, mimeType)) {
            return "document";
        }
        return "unknown";
    }

    private boolean isAllowedDocument(String fileName, String mimeType) {
        if (mimeType != null && ALLOWED_DOCUMENT_MIME_TYPES.contains(mimeType)) {
            return true;
        }
        String ext = extension(fileName);
        return !ext.isBlank() && ALLOWED_DOCUMENT_EXTENSIONS.contains(ext);
    }

    private String sanitizePathSegment(String raw) {
        String value = StringUtils.defaultIfBlank(raw, "unknown");
        return value.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private String toPublicUrl(String minioUrl) {
        if (StringUtils.isBlank(minioUrl) || StringUtils.equals(minioConfig.getEndpoint(), minioConfig.getPublicUrl())) {
            return minioUrl;
        }
        return minioUrl.replace(minioConfig.getEndpoint(), minioConfig.getPublicUrl());
    }

    public record UploadedImage(
            String type,
            String objectKey,
            String fileName,
            String mimeType,
            long size,
            String url
    ) {}

    public record UploadedAttachment(
            String type,
            String objectKey,
            String fileName,
            String mimeType,
            long size,
            String url
    ) {}

    /**
     * 视频 / 音频 / transcript 等服务端生成资产的统一返回结构。
     * 与 {@link UploadedAttachment} 字段对齐，但语义上是后端写入的资产，不带 user 校验细节。
     */
    public record StoredAsset(
            String type,
            String objectKey,
            String fileName,
            String mimeType,
            long size,
            String url
    ) {}
}
