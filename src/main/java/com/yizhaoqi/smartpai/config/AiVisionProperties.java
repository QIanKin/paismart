package com.yizhaoqi.smartpai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 视觉/多模态 LLM 配置。
 *
 * <p>设计目标：和主 LLM 共用同一个 base_url + api_key，只是 {@link #model} 字段不同。
 * 这样切换 vision 模型不需要新建一套 provider 体系，{@code application.yml} 里改个变量即可。
 *
 * <p>{@link #model} 留空时会复用主 LLM 的 model 字段——前提是该模型本身支持 vision（例如 GPT-4o-mini、
 * GLM-5.1 多模态版本、Qwen-VL 系列）。当用户主 LLM 是纯文本模型（DeepSeek-Chat 等）时，
 * 强烈建议显式配上 {@code AI_VISION_MODEL}。
 */
@Configuration
@ConfigurationProperties(prefix = "ai.vision")
public class AiVisionProperties {

    /** 视觉模型 id；空字符串 = 复用主 LLM model。 */
    private String model = "";

    /** 总开关；关掉时所有依赖视觉 LLM 的 skill / tool 都不会跑视觉路径。 */
    private boolean enabled = true;

    /** 单次视频拆解抽多少帧给 vision LLM。 */
    private int frameCount = 6;

    /** 单帧最长边像素上限（用 ffmpeg scale 缩到这里），平衡 token 成本与识别精度。 */
    private int frameMaxEdge = 768;

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getFrameCount() { return frameCount; }
    public void setFrameCount(int frameCount) { this.frameCount = frameCount; }
    public int getFrameMaxEdge() { return frameMaxEdge; }
    public void setFrameMaxEdge(int frameMaxEdge) { this.frameMaxEdge = frameMaxEdge; }
}
