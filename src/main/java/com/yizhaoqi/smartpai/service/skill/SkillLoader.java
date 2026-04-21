package com.yizhaoqi.smartpai.service.skill;

import com.yizhaoqi.smartpai.config.SkillProperties;
import com.yizhaoqi.smartpai.model.agent.Skill;
import com.yizhaoqi.smartpai.repository.agent.SkillRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 加载器：
 *  1. 启动时：扫描 {@link SkillProperties#getRoots} 下所有 SKILL.md → 解析 → 与 DB 合并 → 注入 Registry；
 *  2. 周期：每 {@link SkillProperties#getWatchIntervalSeconds} 秒做一次 diff（按 bodyHash 判定），
 *     新增/变化/删除都会同步写 DB 并刷新 Registry；
 *  3. API 触发：Controller 调 {@link #reloadNow()} 立即触发一次扫盘。
 *
 * 写入语义：
 *  - 磁盘是 source of truth；DB 只做索引 + 持久化 Hash/source/可见性。
 *  - 手动 disable 一个 skill → 写 DB enabled=false，下次 reload 时 Registry 会尊重此状态（不会因磁盘刷新把它再启用）。
 *
 * 边界：
 *  - 默认 source=LOCAL；如需要标成 BUILTIN 可以通过 Controller 改。
 *  - ownerOrgTag 目前固定为 null（全局）；多租户自定义放在后续"skill 上传"流程里解决。
 */
@Component
public class SkillLoader {

    private static final Logger logger = LoggerFactory.getLogger(SkillLoader.class);

    private final SkillProperties properties;
    private final SkillParser parser;
    private final SkillRegistry registry;
    private final SkillRepository repository;

    public SkillLoader(SkillProperties properties, SkillParser parser,
                       SkillRegistry registry, SkillRepository repository) {
        this.properties = properties;
        this.parser = parser;
        this.registry = registry;
        this.repository = repository;
    }

    @PostConstruct
    public void initialLoad() {
        if (!properties.isEnabled()) {
            logger.info("Skill 子系统已禁用（skills.enabled=false），跳过扫描");
            return;
        }
        try {
            reloadNow();
        } catch (Exception e) {
            logger.warn("Skill 初始扫描失败: {}", e.getMessage(), e);
        }
    }

    @Scheduled(fixedDelayString = "#{${skills.watch-interval-seconds:30} * 1000}")
    public void periodicReload() {
        if (!properties.isEnabled()) return;
        if (properties.getWatchIntervalSeconds() <= 0) return;
        try {
            reloadNow();
        } catch (Exception e) {
            logger.warn("Skill 定时扫描失败: {}", e.getMessage());
        }
    }

    @Transactional
    public ReloadResult reloadNow() {
        int scanned = 0, added = 0, updated = 0, skipped = 0, disabled = 0, errors = 0;

        // 1. 扫盘收集所有 ParsedSkill（按 name 去重，首个命中优先）
        Map<String, SkillParser.ParsedSkill> diskByName = new HashMap<>();
        for (String root : properties.getRoots()) {
            Path base = Paths.get(root).toAbsolutePath();
            if (!Files.isDirectory(base)) {
                logger.debug("Skill root 不存在，跳过: {}", base);
                continue;
            }
            try (var stream = Files.list(base)) {
                for (Path child : stream.toList()) {
                    if (!Files.isDirectory(child)) continue;
                    if (!Files.isRegularFile(child.resolve("SKILL.md"))) continue;
                    scanned++;
                    try {
                        SkillParser.ParsedSkill p = parser.parse(child);
                        diskByName.putIfAbsent(p.name(), p);
                    } catch (Exception ex) {
                        errors++;
                        logger.warn("解析 skill 失败 path={} err={}", child, ex.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.warn("扫描 skill root 失败 root={} err={}", root, e.getMessage());
            }
        }

        // 2. 读 DB 里全局 skill，逐个 diff
        List<Skill> dbGlobals = new ArrayList<>(repository.findByEnabledTrueAndOwnerOrgTagIsNull());
        // 加上禁用的，便于保留 disabled 状态
        List<Skill> disabledOnes = repository.findAll().stream()
                .filter(s -> Boolean.FALSE.equals(s.getEnabled()) && s.getOwnerOrgTag() == null)
                .toList();
        dbGlobals.addAll(disabledOnes);

        Map<String, Skill> dbByName = new HashMap<>();
        for (Skill s : dbGlobals) dbByName.put(s.getName(), s);

        List<LoadedSkill> newRegistry = new ArrayList<>();
        for (Map.Entry<String, SkillParser.ParsedSkill> e : diskByName.entrySet()) {
            String name = e.getKey();
            SkillParser.ParsedSkill p = e.getValue();
            Skill existing = dbByName.remove(name);
            Skill row;
            if (existing == null) {
                row = new Skill();
                row.setName(name);
                row.setSource(Skill.Source.LOCAL);
                row.setOwnerOrgTag(null);
                row.setEnabled(true);
                added++;
            } else if (existing.getBodyHash() != null && existing.getBodyHash().equals(p.bodyHash())) {
                skipped++;
                // body 没变，直接用 existing
                row = existing;
                row.setLastLoadedAt(LocalDateTime.now());
                repository.save(row);
                newRegistry.add(registry.fromEntity(row));
                continue;
            } else {
                row = existing;
                updated++;
            }
            row.setDescription(p.description());
            row.setVersion(p.version());
            row.setHomepage(p.homepage());
            row.setMetadataJson(p.metadataJson());
            row.setRequiredBinsJson(p.requiredBinsJson());
            row.setScriptsInventory(p.scriptsInventory());
            row.setBodyMd(p.bodyMd());
            row.setBodyHash(p.bodyHash());
            row.setRootPath(p.rootPath());
            row.setLastLoadedAt(LocalDateTime.now());
            Skill saved = repository.save(row);
            newRegistry.add(registry.fromEntity(saved));
        }

        // 3. 磁盘删除的 skill → disable
        for (Skill orphan : dbByName.values()) {
            if (Boolean.TRUE.equals(orphan.getEnabled())) {
                orphan.setEnabled(false);
                repository.save(orphan);
                disabled++;
                logger.info("磁盘上找不到 skill，自动禁用: {}", orphan.getName());
            }
            // 保留在 registry（disabled=false），不注入
        }

        registry.replaceAll(newRegistry);

        logger.info("SkillLoader reload 完成 scanned={} added={} updated={} skipped={} disabled={} errors={} registry={}",
                scanned, added, updated, skipped, disabled, errors, newRegistry.size());
        return new ReloadResult(scanned, added, updated, skipped, disabled, errors, newRegistry.size());
    }

    public Optional<Skill> setEnabled(Long id, boolean enabled) {
        return repository.findById(id).map(s -> {
            s.setEnabled(enabled);
            Skill saved = repository.save(s);
            if (!enabled) registry.remove(saved.getName(), saved.getOwnerOrgTag());
            else registry.upsert(registry.fromEntity(saved));
            return saved;
        });
    }

    public record ReloadResult(int scanned, int added, int updated, int skipped,
                               int disabled, int errors, int activeCount) {}
}
