package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ASR (自动语音识别) 服务配置。
 *
 * <p>支持两类 provider，互斥按 {@link #provider} 切换：
 * <ul>
 *   <li>{@code dashscope-paraformer-v2}：阿里百炼云端，要求音频 URL 公网可达；</li>
 *   <li>{@code whisper-compatible}：默认在 backend 容器内跑 {@code faster-whisper}（子进程）；
 *       也可切到 HTTP 模式连自建 whisper-asr-webservice。零额外镜像、国内镜像拉不动 Docker Hub 时仍可用。</li>
 * </ul>
 *
 * <p>字段语义见 {@code application.yml#smartpai.asr}。
 */
@Component
@ConfigurationProperties(prefix = "smartpai.asr")
@Data
public class AsrProperties {

    /** 是否启用 ASR。关掉后所有依赖 ASR 的工具走"无字幕回退"分支。 */
    private boolean enabled = true;

    /**
     * 当前 provider。可选：
     * <ul>
     *   <li>{@code dashscope-paraformer-v2}（云端，付费）</li>
     *   <li>{@code whisper-compatible}（本地/自建，免费）</li>
     * </ul>
     */
    private String provider = "whisper-compatible";

    /** DashScope base url，paraformer-v2 异步任务专用接口。 */
    private String baseUrl = "https://dashscope.aliyuncs.com";

    /** DashScope 模型 id。 */
    private String model = "paraformer-v2";

    /**
     * DashScope ASR API Key。空则回退到 {@code embedding.api.key}（默认同 DashScope key 即可用）。
     * 建议独立配 {@code DASHSCOPE_ASR_API_KEY}，便于额度统计。
     */
    private String apiKey = "";

    /** 任务提交后多久轮询一次（秒）。默认 3s。 */
    private int pollIntervalSeconds = 3;

    /** 最长等待秒数（含上传 + 异步识别）。默认 600s。 */
    private int maxPollSeconds = 600;

    /** 单条 HTTP 请求超时（秒）。 */
    private int httpTimeoutSeconds = 30;

    /** 任务提交时 enable_punctuation_prediction（是否打标点）。默认 true。 */
    private boolean punctuation = true;

    /** 任务提交时 enable_inverse_text_normalization（数字归一化）。默认 true。 */
    private boolean itn = true;

    /** language_hints；逗号分隔。默认 zh,en（中英自适应）。 */
    private String languageHints = "zh,en";

    /** Whisper 子配置（仅 provider=whisper-compatible 时使用）。 */
    private Whisper whisper = new Whisper();

    /**
     * Whisper / OpenAI Whisper 兼容服务（multipart 文件上传形式）。
     * 适配 {@code onerahmet/openai-whisper-asr-webservice} 与上游 whisper.cpp HTTP server。
     */
    @Data
    public static class Whisper {
        /** HTTP 模式下的服务地址；{@code local-faster-whisper} 时可留空。 */
        private String baseUrl = "";

        /**
         * 转写端点。两套 schema：
         * <ul>
         *   <li>{@code /asr}（onerahmet 默认）：{@code multipart audio_file=}</li>
         *   <li>{@code /v1/audio/transcriptions}（OpenAI 兼容服务，例 faster-whisper-server）：{@code multipart file=}</li>
         * </ul>
         */
        private String path = "/asr";

        /**
         * 模式：
         * <ul>
         *   <li>{@code local-faster-whisper}（默认）：同容器 python3 + faster-whisper，无需 base-url；</li>
         *   <li>{@code asr-webservice}：POST multipart 到自建 onerahmet 等；</li>
         *   <li>{@code openai-compat}：OpenAI 兼容 {@code /v1/audio/transcriptions}。</li>
         * </ul>
         */
        private String mode = "local-faster-whisper";

        /** {@code local-faster-whisper} 时调用的脚本路径（镜像内默认已带）。 */
        private String localScriptPath = "/app/scripts/local_whisper_transcribe.py";

        /** 任务类型。{@code transcribe}（默认）/ {@code translate}（翻译为英文）。 */
        private String task = "transcribe";

        /** 强制语种，留空自动检测。中文给 {@code zh} 准确率更高。 */
        private String language = "zh";

        /** 输出格式：{@code json} / {@code text} / {@code vtt} / {@code srt} / {@code tsv}（whisper-asr-webservice 支持）。 */
        private String output = "json";

        /** 模型：local 模式为 tiny/base/small/...；openai-compat 为完整 HF id 或 OpenAI 名。 */
        private String model = "base";

        /** 单次请求超时（秒）。CPU 跑 base 模型一般 30~120s 够。 */
        private int httpTimeoutSeconds = 600;

        /** OpenAI 兼容 API Key（如对接 OpenAI 官方 whisper），onerahmet 容器无需。 */
        private String apiKey = "";

        /** 单文件上传字节上限。超过则前置失败。 */
        private long maxAudioBytes = 200L * 1024 * 1024L;
    }
}
