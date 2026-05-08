package com.yizhaoqi.smartpai.service.asr;

import java.nio.file.Path;
import java.util.List;

/**
 * 音频转写服务的统一抽象。
 *
 * <p>不同 provider 对输入的需求不同：
 * <ul>
 *   <li><b>DashScope paraformer</b>：要求音频必须是公网可达 URL（异步任务模型），不接受 binary 上传；</li>
 *   <li><b>Whisper / SiliconFlow</b>：直接 multipart 上传 binary 文件，不需要 MinIO 公网。</li>
 * </ul>
 *
 * <p>因此接口同时把 {@code localFile} 和 {@code publicUrl} 都暴露给实现，每个实现自取所需。
 * 这样 {@code XhsVideoAnalyzeTool} 不必关心当前 provider 选了哪条路。
 */
public interface AsrService {

    /**
     * provider 标识，匹配 {@code smartpai.asr.provider} 配置。
     * 如：{@code dashscope-paraformer-v2}、{@code whisper-compatible}。
     */
    String provider();

    /**
     * 当前 provider 是否已就绪（开关 + 必需配置都齐全）。
     */
    boolean configured();

    /**
     * 同步转写并返回完整结果。失败时抛 {@link AsrException}。
     *
     * @param localFile 抽出的本地音频文件路径，可空（仅 Whisper 等用 binary 上传的需要）
     * @param publicUrl 公网可达的音频 URL，可空（仅 DashScope 等用 URL 模式的需要）
     */
    AsrResult transcribe(Path localFile, String publicUrl) throws AsrException;

    /**
     * 转写结果。
     * @param text             整段拼好的文本
     * @param sentences        句级时间戳（无时可为空 list）
     * @param durationMs       音频时长 ms（无可填 0）
     * @param detectedLanguages 自动识别的语言（如 "zh"），可为 null
     * @param taskId           provider 侧任务 id，用于排查
     */
    record AsrResult(String text, List<Sentence> sentences, long durationMs,
                     String detectedLanguages, String taskId) {
        public boolean isEmpty() {
            return text == null || text.isBlank();
        }
    }

    /** 单条句子（带时间戳）。 */
    record Sentence(long beginTime, long endTime, String text, String speakerId) {}

    /** 统一异常。code 字段会被工具层透传给 LLM/UI。 */
    class AsrException extends Exception {
        private final String code;

        public AsrException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() { return code; }
    }
}
