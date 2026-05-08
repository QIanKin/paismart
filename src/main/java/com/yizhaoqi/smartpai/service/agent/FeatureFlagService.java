package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.config.TikhubProperties;
import com.yizhaoqi.smartpai.config.XhsThirdPartyProperties;
import com.yizhaoqi.smartpai.model.agent.AgentFeatureFlag;
import com.yizhaoqi.smartpai.repository.agent.AgentFeatureFlagRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * 运行时 Feature Flag 服务。集中管理"数据源 / 能力组件能否被 Agent 使用"的开关。
 *
 * <p>解决的问题：之前 TikHub / 蒲公英 / 第三方 XHS 这些数据源是否启用，由 application.yml + .env
 * 控制，必须重启容器才能切换。这对运营 / 客户运维不友好（"我先关掉 TikHub 试试效果"基本没法做）。
 *
 * <p>本服务对每一个 flag 提供：
 * <ul>
 *   <li>{@code key}：稳定的程序内标识，例如 {@code data_source.tikhub}；</li>
 *   <li>{@code label / description}：前端展示用；</li>
 *   <li>{@code defaultProvider}：DB 没记录时回退 yml 默认（即旧行为）；</li>
 *   <li>{@code toolPrefixes}：与该 flag 绑定的工具名前缀。
 *       Agent 在 LLM manifest 阶段调 {@link #isToolEnabledByFlags(String)} 一并过滤。</li>
 * </ul>
 *
 * <p>读路径：
 * <ol>
 *   <li>启动时 {@link #initLoadFromDb()} 读 DB，把覆盖项放进 {@link #overrideMap}；</li>
 *   <li>{@link #isEnabled(String)}：先看 overrideMap；miss 时回到 catalog 默认。</li>
 * </ol>
 *
 * <p>写路径：
 * <ol>
 *   <li>{@link #setEnabled(String, boolean)} 同时刷 DB + overrideMap；</li>
 *   <li>未来若要清除 override 回到 yml，可以调 {@link #clearOverride(String)}。</li>
 * </ol>
 */
@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    /** 每个 flag 的元数据 + 默认值来源。 */
    public record FlagSpec(
            String key,
            String label,
            String description,
            BooleanSupplier defaultProvider,
            List<String> toolPrefixes
    ) {}

    private final AgentFeatureFlagRepository repository;

    /** flag 元数据目录。运行时不变化。 */
    private final Map<String, FlagSpec> catalog = new LinkedHashMap<>();

    /** DB 覆盖值。null 表示未覆盖，回退到 defaultProvider。 */
    private final Map<String, Boolean> overrideMap = new ConcurrentHashMap<>();

    /** 工具名 → flag key（运行时过滤）。同一工具最多绑一个 flag。 */
    private final Map<String, String> toolToFlag = new ConcurrentHashMap<>();

    public FeatureFlagService(AgentFeatureFlagRepository repository,
                              ObjectProvider<TikhubProperties> tikhubProps,
                              ObjectProvider<XhsThirdPartyProperties> thirdPartyProps) {
        this.repository = repository;

        register(new FlagSpec(
                "data_source.tikhub",
                "TikHub 公开数据 / 视频解析",
                "TikHub.io 的小红书公开 API：用户搜索、用户笔记、笔记详情、评论、热搜、热榜、视频无水印直链。"
                        + "关闭后 xhs_video_analyze、xhs_search_notes、xhs_third_party_* 等工具不再暴露给 LLM。",
                () -> {
                    TikhubProperties p = tikhubProps.getIfAvailable();
                    return p != null && p.isEnabled()
                            && p.getApiKey() != null && !p.getApiKey().isBlank();
                },
                List.of("xhs_video_analyze", "xhs_search_notes", "xhs_search_users",
                        "xhs_user_notes", "xhs_note_detail", "xhs_note_comments",
                        "xhs_hot_list", "xhs_trending")
        ));

        register(new FlagSpec(
                "data_source.third_party_xhs",
                "第三方小红书 provider",
                "通过 TikHub 适配的小红书数据 provider。关闭后 xhs_third_party_note_detail / "
                        + "xhs_third_party_media_download 将不再暴露给 LLM。",
                () -> {
                    XhsThirdPartyProperties p = thirdPartyProps.getIfAvailable();
                    return p != null && p.isEnabled();
                },
                List.of("xhs_third_party_")
        ));

        register(new FlagSpec(
                "data_source.pgy_cookie",
                "蒲公英 (PGY) Cookie 池",
                "蒲公英 pgy.xiaohongshu.com 的 cookie 池，KOL 列表、粉丝画像、报价等品牌侧能力依赖它。"
                        + "关闭后 xhs_fetch_pgy_kol、xhs_pgy_kol_detail、xhs_pgy_whoami、xhs_cookie_list、"
                        + "xhs_qr_login_start 等不再暴露给 LLM。",
                () -> true,
                List.of("xhs_fetch_pgy_", "xhs_pgy_", "xhs_cookie_list", "xhs_qr_login_start")
        ));

        register(new FlagSpec(
                "data_source.spotlight_oauth",
                "聚光 (Spotlight) MarketingAPI",
                "聚光官方广告 OAuth API，关键词以词推词、人群预估、计划列表等。关闭后 spotlight_* 工具不再暴露。",
                () -> true,
                List.of("spotlight_")
        ));
    }

    @PostConstruct
    public void initLoadFromDb() {
        try {
            for (AgentFeatureFlag row : repository.findAll()) {
                if (row.getKey() == null) continue;
                if (row.getEnabled() == null) continue;
                if (catalog.containsKey(row.getKey())) {
                    overrideMap.put(row.getKey(), row.getEnabled());
                }
            }
            log.info("FeatureFlagService 加载完成，目录 {} 个，DB 覆盖 {} 个",
                    catalog.size(), overrideMap.size());
        } catch (Exception e) {
            log.warn("加载 feature flags 失败（用 yml 默认值兜底）: {}", e.getMessage());
        }
    }

    private void register(FlagSpec spec) {
        catalog.put(spec.key, spec);
        for (String prefix : spec.toolPrefixes) {
            toolToFlag.put(prefix, spec.key);
        }
    }

    /** 是否启用某个 flag；查覆盖优先，缺省走 yml provider。 */
    public boolean isEnabled(String key) {
        if (key == null) return false;
        Boolean override = overrideMap.get(key);
        if (override != null) return override;
        FlagSpec spec = catalog.get(key);
        if (spec == null) return false;
        try {
            return spec.defaultProvider.getAsBoolean();
        } catch (Exception e) {
            log.warn("flag {} defaultProvider 抛异常 -> 返回 false: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * 给 ToolRegistry 用的：某个工具是否被关联的 flag 全部允许。无任何 flag 绑定时返回 true。
     */
    public boolean isToolEnabledByFlags(String toolName) {
        if (toolName == null) return true;
        String matched = matchPrefix(toolName);
        if (matched == null) return true;
        return isEnabled(matched);
    }

    /** 设置某个 flag。会立刻生效，不等下一轮启动。 */
    @Transactional
    public synchronized AgentFeatureFlag setEnabled(String key, boolean enabled) {
        FlagSpec spec = catalog.get(key);
        if (spec == null) throw new IllegalArgumentException("未知 flag: " + key);
        AgentFeatureFlag row = repository.findByKey(key).orElseGet(() -> {
            AgentFeatureFlag nf = new AgentFeatureFlag();
            nf.setKey(key);
            return nf;
        });
        row.setEnabled(enabled);
        if (row.getDescription() == null || row.getDescription().isBlank()) {
            row.setDescription(spec.description);
        }
        AgentFeatureFlag saved = repository.save(row);
        overrideMap.put(key, enabled);
        log.info("FeatureFlag {} -> {}", key, enabled);
        return saved;
    }

    @Transactional
    public synchronized void clearOverride(String key) {
        repository.findByKey(key).ifPresent(repository::delete);
        overrideMap.remove(key);
    }

    /** 列出所有 flag 的当前状态（前端能力中心 / 数据源面板用）。 */
    public List<FlagView> listAll() {
        List<FlagView> out = new ArrayList<>();
        for (FlagSpec s : catalog.values()) {
            boolean enabled = isEnabled(s.key);
            boolean overridden = overrideMap.containsKey(s.key);
            boolean ymlDefault;
            try {
                ymlDefault = s.defaultProvider.getAsBoolean();
            } catch (Exception e) {
                ymlDefault = false;
            }
            out.add(new FlagView(s.key, s.label, s.description, enabled, overridden, ymlDefault, s.toolPrefixes));
        }
        return out;
    }

    /** 查 flag 名所匹配到的 prefix 入口。 */
    private String matchPrefix(String toolName) {
        // 优先精确匹配
        if (toolToFlag.containsKey(toolName)) return toolToFlag.get(toolName);
        for (Map.Entry<String, String> e : toolToFlag.entrySet()) {
            if (toolName.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    /** 给 controller 序列化用的 view。 */
    public record FlagView(
            String key,
            String label,
            String description,
            boolean enabled,
            boolean overridden,
            boolean ymlDefault,
            List<String> toolPrefixes
    ) {
        public String displayKey() { return key.toLowerCase(Locale.ROOT); }
    }
}
