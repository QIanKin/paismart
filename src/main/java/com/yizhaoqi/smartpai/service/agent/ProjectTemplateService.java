package com.yizhaoqi.smartpai.service.agent;

import com.yizhaoqi.smartpai.model.agent.ProjectTemplate;
import com.yizhaoqi.smartpai.repository.agent.ProjectTemplateRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 项目模板查询服务。
 *
 * 可见性：
 *  - ownerOrgTag=null 的模板对所有租户可见；
 *  - ownerOrgTag=X 的模板只对租户 X 可见。
 *
 * Phase 2 仅做管道，真正的 SOP 模板（小蜜蜂 MCN 流程等）由业务侧通过 REST/SQL 注入，
 * 这样非研发同事也可以在不改代码的前提下迭代 SOP。
 */
@Service
public class ProjectTemplateService {

    private final ProjectTemplateRepository repository;

    public ProjectTemplateService(ProjectTemplateRepository repository) {
        this.repository = repository;
    }

    public List<ProjectTemplate> listVisible(String orgTag) {
        List<ProjectTemplate> out = new ArrayList<>(repository.findByEnabledTrueAndOwnerOrgTagIsNullOrderByDisplayOrderAsc());
        if (orgTag != null && !orgTag.isBlank()) {
            out.addAll(repository.findByEnabledTrueAndOwnerOrgTagOrderByDisplayOrderAsc(orgTag));
        }
        return out;
    }

    public ProjectTemplate getByCode(String code) {
        return repository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在: " + code));
    }
}
