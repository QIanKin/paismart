package com.yizhaoqi.smartpai.service.asr;

import com.yizhaoqi.smartpai.config.AsrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ASR provider 路由器。注入容器里所有 {@link AsrService} 实现，按 {@code smartpai.asr.provider} 选用。
 *
 * <p>这层做两件事：
 * <ol>
 *   <li>对外只暴露一个稳定门面，业务工具（比如 {@code XhsVideoAnalyzeTool}）不必关心当前是 DashScope 还是 Whisper；</li>
 *   <li>把"未配置 provider"等准入逻辑统一处理，避免每个调用方各自 if/else。</li>
 * </ol>
 */
@Service
public class AsrDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AsrDispatcher.class);

    private final AsrProperties props;
    private final Map<String, AsrService> services;

    public AsrDispatcher(AsrProperties props, List<AsrService> implementations) {
        this.props = props;
        this.services = new LinkedHashMap<>();
        for (AsrService svc : implementations) {
            services.put(svc.provider(), svc);
        }
        log.info("ASR providers loaded: {}; selected={}", services.keySet(), props.getProvider());
    }

    /** 当前激活的 provider id（来自配置）。 */
    public String activeProvider() { return props.getProvider(); }

    /** 当前激活 provider 是否已就绪可调用。 */
    public boolean configured() {
        return current().map(AsrService::configured).orElse(false);
    }

    /** 全局 ASR 开关（仅 enabled 字段）。 */
    public boolean enabled() { return props.isEnabled(); }

    /**
     * 调用当前 provider 完成一次转写。
     *
     * @param localFile 本地音频文件，{@code whisper-compatible} 模式必传
     * @param publicUrl 公网音频 URL，{@code dashscope-paraformer-v2} 模式必传
     */
    public AsrService.AsrResult transcribe(Path localFile, String publicUrl) throws AsrService.AsrException {
        if (!props.isEnabled()) {
            throw new AsrService.AsrException("provider_disabled",
                    "smartpai.asr.enabled=false，已禁用 ASR；请在 .env 设置 SMARTPAI_ASR_ENABLED=true");
        }
        AsrService svc = current().orElseThrow(() -> new AsrService.AsrException(
                "provider_disabled",
                "未找到 provider=" + props.getProvider() + " 的实现；可选: " + services.keySet()));
        if (!svc.configured()) {
            throw new AsrService.AsrException("provider_disabled",
                    "Provider " + svc.provider() + " 未就绪（缺配置或 key）");
        }
        log.info("ASR dispatch provider={} hasLocalFile={} hasPublicUrl={}",
                svc.provider(), localFile != null, publicUrl != null && !publicUrl.isBlank());
        return svc.transcribe(localFile, publicUrl);
    }

    private Optional<AsrService> current() {
        if (props.getProvider() == null || props.getProvider().isBlank()) return Optional.empty();
        return Optional.ofNullable(services.get(props.getProvider()));
    }
}
