package com.yizhaoqi.smartpai.service.tool;

/**
 * Agent 工具统一的错误码常量。小写 snake_case。
 *
 * <p>为什么抽常量？
 * <ul>
 *   <li>LLM 可以稳定地识别代码（不受工具措辞变化影响）；</li>
 *   <li>前端可以把 {@code errorCode} 映射到帮助链接/引导按钮（如 {@code config_missing} → 数据源页）；</li>
 *   <li>避免不同工具写出 {@code "not-found"}、{@code "notFound"}、{@code "NOT_FOUND"} 等不一致变体。</li>
 * </ul>
 *
 * <p>新增错误码时同步：
 * <ol>
 *   <li>前端 {@code frontend/src/views/agent-project-detail/modules/agent-error-codes.ts}
 *       里加人话说明 + 帮助链接；</li>
 *   <li>若该错误经常意味着"配置缺失"，考虑在对应管理页加 deep-link。</li>
 * </ol>
 */
public final class ToolErrors {

    private ToolErrors() {}

    // ==== 输入/参数 ====

    /** 入参不合法（schema 通过但业务校验失败）。 */
    public static final String BAD_REQUEST = "bad_request";

    /** 校验通过但对应 cookie 缺字段（a1/web_session/webId 或 refresh_token）。 */
    public static final String COOKIE_INVALID = "cookie_invalid";

    // ==== 资源状态 ====

    /** 目标不存在 / 被删除 / 跨 org 不可见。 */
    public static final String NOT_FOUND = "not_found";

    /** 当前 org 下没有可用目标，通常引导去 /data-sources 录入。 */
    public static final String NO_TARGET = "no_target";

    /** 找到了但类型/平台不对（例如把 xhs_pc 传给 spotlight 工具）。 */
    public static final String WRONG_PLATFORM = "wrong_platform";

    /** 找到了但没带 refresh_token，无法续签。 */
    public static final String MISSING_REFRESH_TOKEN = "missing_refresh_token";

    // ==== 权限 ====

    /** 当前用户角色不够（大多需要 admin）。 */
    public static final String PERMISSION_DENIED = "permission_denied";

    /** 破坏性工具要求二次确认但 LLM 没带 _confirm=true。由 ToolExecutor 注入。 */
    public static final String CONFIRMATION_REQUIRED = "confirmation_required";

    // ==== 配置 ====

    /** 运维侧环境变量 / application.yml 未配齐。 */
    public static final String CONFIG_MISSING = "config_missing";

    // ==== 外部依赖 ====

    /** 外部 HTTP 接口报错（4xx/5xx），一般附 {@code extra.httpStatus}。 */
    public static final String UPSTREAM_ERROR = "upstream_error";

    /** 网络/DNS/连接异常。 */
    public static final String NETWORK = "network";

    /** 外部接口返回业务失败（HTTP 200 但 code != 0）。 */
    public static final String UPSTREAM_REJECTED = "upstream_rejected";

    /** 上游限速/额度。 */
    public static final String RATE_LIMIT = "rate_limit";

    /** 调用超时。 */
    public static final String TIMEOUT = "timeout";

    // ==== 内部 ====

    /** 未分类的运行时异常，自动兜底。 */
    public static final String INTERNAL = "internal";
}
