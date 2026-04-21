package com.yizhaoqi.smartpai.service.agent.context;

import java.util.List;

/**
 * Context 层 provider。对标 openclaw/src/context-engine/registry.ts 的 ContextProvider。
 * Spring 自动收集所有实现，由 {@link ContextEngine} 协调。
 *
 * 约定：
 *  - 任何 provider 的异常都不应中断 assemble；ContextEngine 会包 try/catch 并跳过；
 *  - provider 若无贡献，返回 List.of()。
 */
public interface ContextProvider {

    /** 用于日志 + 调试；同名会去重（先注册的覆盖后注册的） */
    String name();

    /** 越小越先执行；顺序主要影响"在预算 overflow 时删谁"的决策——allocator 最终按 priority 字段裁剪。 */
    int order();

    List<ContextContribution> contribute(ContextRequest request);
}
