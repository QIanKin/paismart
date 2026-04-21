/**
 * Namespace Api
 *
 * All backend api type
 */
declare namespace Api {
  namespace Common {
    /** common params of paginating */
    interface PaginatingCommonParams {
      /** current page number */
      page?: number;
      number: number;
      /** page size */
      size?: number;
      /** total count */
      totalElements: number;
    }

    /** common params of paginating query list data */
    interface PaginatingQueryRecord<T = any> extends PaginatingCommonParams {
      data: T[];
      content: T[];
    }

    /** common search params of table */
    type CommonSearchParams = Pick<Common.PaginatingCommonParams, 'page' | 'size'>;
  }

  /**
   * namespace Auth
   *
   * backend api module: "auth"
   */
  namespace Auth {
    interface LoginToken {
      token: string;
      refreshToken: string;
    }

    interface UserInfo {
      id: number;
      username: string;
      role: 'USER' | 'ADMIN';
      orgTags: string[];
      primaryOrg: string;
    }
  }

  /**
   * namespace Route
   *
   * backend api module: "route"
   */
  namespace Route {
    type ElegantConstRoute = import('@elegant-router/types').ElegantConstRoute;

    interface MenuRoute extends ElegantConstRoute {
      id: string;
    }

    interface UserRoute {
      routes: MenuRoute[];
      home: import('@elegant-router/types').LastLevelRouteKey;
    }
  }

  namespace OrgTag {
    interface Item {
      tagId: string;
      name: string;
      description: string;
      parentTag: string | null;
      uploadMaxSizeBytes: number | null;
      uploadMaxSizeMb: number | null;
      children?: Item[];
    }

    type List = Common.PaginatingQueryRecord<Item>;

    type Details = Pick<Item, 'tagId' | 'name' | 'description'>;
    type Mine = {
      orgTags: string[];
      primaryOrg: string;
      orgTagDetails: Details[];
    };
  }

  namespace User {
    interface UsageQuota {
      enabled: boolean;
      usedTokens: number;
      limitTokens: number;
      remainingTokens: number;
      requestCount: number;
    }

    interface UsageSnapshot {
      day: string;
      chatRequestCount: number;
      llm: UsageQuota;
      embedding: UsageQuota;
    }

    interface TokenRecord {
      id: number;
      recordDate: string;
      tokenType: 'LLM' | 'EMBEDDING';
      changeType: 'INCREASE' | 'CONSUME';
      amount: number;
      balanceBefore: number | null;
      balanceAfter: number | null;
      reason: string;
      remark: string | null;
      requestCount: number;
      createdAt: string;
    }

    type SearchParams = CommonType.RecordNullable<
      Common.CommonSearchParams & {
        keyword: string;
        orgTag: string;
        status: number;
      }
    >;

    type Item = {
      userId: string;
      username: string;
      status: number;
      orgTags: Pick<OrgTag.Item, 'tagId' | 'name'>[];
      primaryOrg: string;
      createdAt: string;
      usage: UsageSnapshot;
      chatUsage?: string;
      llmUsage?: string;
      embeddingUsage?: string;
    };

    type List = Common.PaginatingQueryRecord<Item>;
  }

  namespace InviteCode {
    type SearchParams = CommonType.RecordNullable<
      Common.CommonSearchParams & {
        enabled: boolean;
      }
    >;

    interface Creator {
      id: number;
      username: string;
    }

    interface Item {
      id: number;
      code: string;
      maxUses: number;
      usedCount: number;
      expiresAt: string | null;
      enabled: boolean;
      createdBy?: Creator;
      createdAt: string;
      updatedAt: string;
    }

    interface ListPayload {
      records: Item[];
      total: number;
      pages: number;
      current: number;
      size: number;
    }
  }

  /**
   * namespace Recharge
   *
   * backend api module: "recharge"
   */
  namespace Recharge {
    /** 充值套餐 */
    interface Package {
      id: number;
      packageName: string;
      packagePrice: number; // 单位分
      packageDesc: string;
      packageBenefit: string;
      llmToken: number; // LLM token 数量
      embeddingToken: number; // Embedding token 数量
      enabled: boolean;
      createdAt: string;
      updatedAt: string;
    }

    /** 订单信息 */
    interface OrderInfo {
      outTradeNo: string;
      appId: string;
      prePayId: string;
      expireTime: number;
    }

    /** 充值订单 */
    interface Order {
      id: number;
      tradeNo: string;
      userId: string;
      packageId: number;
      amount: number; // 单位分
      llmToken: number; // LLM token 数量
      embeddingToken: number; // Embedding token 数量
      wxTransactionId: string;
      status: 'NOT_PAY' | 'PAYING' | 'SUCCEED' | 'FAIL' | 'CANCELLED';
      description: string;
      payTime: string | null;
      createdAt: string;
      updatedAt: string;
    }
  }

  namespace Admin {
    interface WindowLimit {
      max: number;
      windowSeconds: number;
    }

    interface DualWindowLimit {
      minuteMax: number;
      minuteWindowSeconds: number;
      dayMax: number;
      dayWindowSeconds: number;
    }

    interface TokenBudgetLimit {
      minuteMax: number;
      minuteWindowSeconds: number;
      dayMax: number;
      dayWindowSeconds: number;
    }

    interface RateLimitSettings {
      chatMessage: WindowLimit;
      llmGlobalToken: TokenBudgetLimit;
      embeddingUploadToken: TokenBudgetLimit;
      embeddingQueryRequest: DualWindowLimit;
      embeddingQueryGlobalToken: TokenBudgetLimit;
    }

    interface ModelProviderItem {
      provider: string;
      displayName: string;
      apiStyle: string;
      apiBaseUrl: string;
      model: string;
      dimension: number | null;
      enabled: boolean;
      active: boolean;
      hasApiKey: boolean;
      maskedApiKey: string;
      apiKeyInput?: string;
    }

    interface ModelProviderScopeSettings {
      scope: 'llm' | 'embedding';
      activeProvider: string;
      providers: ModelProviderItem[];
    }

    interface ModelProviderSettings {
      llm: ModelProviderScopeSettings;
      embedding: ModelProviderScopeSettings;
    }

    interface ConnectivityTestResult {
      success: boolean;
      message: string;
      latencyMs: number;
    }

    interface UsageTrendPoint {
      day: string;
      chatRequestCount: number;
      llmUsedTokens: number;
      llmRequestCount: number;
      embeddingUsedTokens: number;
      embeddingRequestCount: number;
    }

    interface UsageRankingItem {
      userId: string;
      username: string;
      scope: 'llm' | 'embedding';
      usedTokens: number;
      limitTokens: number;
      remainingTokens: number;
      requestCount: number;
    }

    interface UsageAlert {
      level: 'critical' | 'warning';
      userId: string;
      username: string;
      scope: 'llm' | 'embedding';
      usedTokens: number;
      limitTokens: number;
      remainingTokens: number;
      requestCount: number;
      usageRatio: number;
      message: string;
    }

    interface UsageOverview {
      days: number;
      today: UsageTrendPoint;
      trends: UsageTrendPoint[];
      llmRankings: UsageRankingItem[];
      embeddingRankings: UsageRankingItem[];
      alerts: UsageAlert[];
    }
  }

  namespace KnowledgeBase {
    interface SearchParams {
      userId: string;
      query: string;
      topK: number;
    }

    interface SearchResult {
      fileMd5: string;
      chunkId: number;
      textContent: string;
      score: number;
      fileName: string;
    }

    interface UploadState {
      tasks: UploadTask[];
      activeUploads: Set<string>; // 当前正在上传的任务ID
    }

    interface Form {
      orgTag: string | null;
      orgTagName: string | null;
      uploadMaxSizeBytes?: number | null;
      uploadMaxSizeMb?: number | null;
      isPublic: boolean;
      fileList: import('naive-ui').UploadFileInfo[];
    }

    interface UploadTask {
      file: File;
      chunk: Blob | null;
      fileMd5: string;
      chunkIndex: number;
      totalSize: number;
      fileName: string;
      userId?: string;
      orgTag: string | null;
      orgTagName?: string | null;
      public: boolean;
      isPublic: boolean;
      uploadedChunks: number[];
      progress: number;
      status: UploadStatus;
      estimatedEmbeddingTokens?: number;
      estimatedChunkCount?: number;
      actualEmbeddingTokens?: number;
      actualChunkCount?: number;
      createdAt?: string;
      mergedAt?: string;
      requestIds?: string[]; // 请求ID，用于取消上传
    }
    type List = Common.PaginatingQueryRecord<UploadTask>;

    type Merge = Pick<UploadTask, 'fileMd5' | 'fileName'>;

    interface Progress {
      uploaded: number[];
      progress: number;
      totalChunks: number;
    }

    interface MergeResult {
      objectUrl: string;
      estimatedEmbeddingTokens?: number;
      estimatedChunkCount?: number;
    }
  }

  namespace Chat {
    interface ReferenceEvidence {
      fileMd5: string;
      fileName: string;
      pageNumber?: number | null;
      anchorText?: string | null;
      retrievalMode?: 'HYBRID' | 'TEXT_ONLY' | null;
      retrievalLabel?: string | null;
      retrievalQuery?: string | null;
      matchedChunkText?: string | null;
      evidenceSnippet?: string | null;
      score?: number | null;
      chunkId?: number | null;
    }

    interface Input {
      message: string;
      conversationId?: string;
    }

    interface Output {
      chunk: string;
    }

    interface Conversation {
      conversationId: string;
    }

    interface Message {
      role: 'user' | 'assistant';
      content: string;
      status?: 'pending' | 'loading' | 'finished' | 'error';
      timestamp?: string;
      conversationId?: string;
      referenceMappings?: Record<string, ReferenceEvidence>;
    }

    interface Token {
      cmdToken: string;
    }
  }

  /**
   * namespace Creator
   *
   * backend api module: "creators" (/api/v1/creators)
   */
  namespace Creator {
    type Platform = string;

    /** 博主（人）主表 */
    interface Person {
      id: number;
      ownerOrgTag: string;
      displayName: string;
      realName?: string | null;
      gender?: string | null;
      birthYear?: number | null;
      city?: string | null;
      country?: string | null;
      personaTags?: string | null;
      trackTags?: string | null;
      cooperationStatus?: string | null;
      internalOwnerId?: number | null;
      internalNotes?: string | null;
      priceNote?: string | null;
      customFields?: string | null;
      createdBy?: string | null;
      createdAt?: string | null;
      updatedAt?: string | null;
    }

    /** 平台账号（扁平：一个 platform+userId 一行） */
    interface Account {
      id: number;
      creatorId?: number | null;
      ownerOrgTag: string;
      platform: Platform;
      platformUserId: string;
      handle?: string | null;
      displayName?: string | null;
      avatarUrl?: string | null;
      bio?: string | null;
      followers?: number | null;
      following?: number | null;
      likes?: number | null;
      posts?: number | null;
      avgLikes?: number | null;
      avgComments?: number | null;
      hitRatio?: number | null;
      engagementRate?: number | null;
      verified?: boolean | null;
      verifyType?: string | null;
      region?: string | null;
      homepageUrl?: string | null;
      categoryMain?: string | null;
      categorySub?: string | null;
      platformTags?: string | null;
      customFields?: string | null;
      lastSyncAt?: string | null;
      createdAt?: string | null;
      updatedAt?: string | null;
    }

    /** 单条内容（视频 / 图文 / 笔记） */
    interface Post {
      id: number;
      accountId: number;
      platform: string;
      platformPostId: string;
      type?: string | null;
      title?: string | null;
      content?: string | null;
      cover?: string | null;
      postUrl?: string | null;
      publishedAt?: string | null;
      duration?: number | null;
      likes?: number | null;
      comments?: number | null;
      shares?: number | null;
      collects?: number | null;
      views?: number | null;
      isHit?: boolean | null;
      hashtags?: string | null;
      hitStructureTags?: string | null;
      screenshotKey?: string | null;
    }

    /** 历史粉丝快照，用于画趋势线 */
    interface Snapshot {
      id?: number;
      accountId?: number;
      snapshotDate?: string;
      followers?: number | null;
      likes?: number | null;
      posts?: number | null;
      engagementRate?: number | null;
    }

    /**
     * 自定义字段实体类型：
     * - creator / account / post：博主库内部；
     * - project：项目级字段（客户、行业、campaign、合同起止日期等）；
     * - project_creator：名册条目级（交付物、流量目标、打款节点等）。
     */
    type CustomFieldEntity = 'creator' | 'account' | 'post' | 'project' | 'project_creator';
    type CustomFieldDataType =
      | 'string'
      | 'text'
      | 'number'
      | 'money'
      | 'boolean'
      | 'enum'
      | 'date'
      | 'tags'
      | 'url';

    interface CustomField {
      id?: number;
      ownerOrgTag?: string;
      entityType: CustomFieldEntity;
      fieldKey: string;
      label: string;
      dataType: CustomFieldDataType;
      options?: string | null;
      required?: boolean;
      builtIn?: boolean;
      orderNo?: number;
      description?: string | null;
    }

    interface SearchAccountParams {
      platform?: string | null;
      keyword?: string | null;
      categoryMain?: string | null;
      followersMin?: number | null;
      followersMax?: number | null;
      verifiedOnly?: boolean | null;
      creatorId?: number | null;
      tagContains?: string | null;
      page?: number;
      size?: number;
      sort?: string;
    }

    interface SearchPersonParams {
      keyword?: string | null;
      cooperationStatus?: string | null;
      tagContains?: string | null;
      page?: number;
      size?: number;
      sort?: string;
    }

    interface PageResult<T> {
      total: number;
      page: number;
      size: number;
      items: T[];
    }

    interface AccountDetail {
      account: Account;
      creator?: Person;
      recentPosts?: Post[];
    }

    interface BatchPostUpsertResult {
      inserted: number;
      updated: number;
      skipped: number;
    }

    interface XhsRefreshResult {
      accountId: number;
      fetched: number;
      /** 真实落库字段，dryRun=false 时出现 */
      inserted?: number;
      updated?: number;
      skipped?: number;
      /** dryRun=true 时出现 */
      dryRun?: boolean;
      preview?: Array<Record<string, unknown>>;
    }
  }

  /**
   * namespace Project
   *
   * backend api module: "agent/projects" (/api/v1/agent/projects)
   */
  namespace Project {
    interface Item {
      id: number;
      ownerId: number;
      orgTag?: string | null;
      name: string;
      description?: string | null;
      systemPrompt?: string | null;
      enabledTools?: string | null; // json array
      enabledSkills?: string | null; // json array
      templateCode?: string | null;
      status?: 'ACTIVE' | 'ARCHIVED' | string;
      createdAt?: string | null;
      updatedAt?: string | null;
    }

    interface Template {
      id: number;
      code: string;
      name: string;
      description?: string | null;
      systemPrompt?: string | null;
      enabledTools?: string | null;
      enabledSkills?: string | null;
      orgTag?: string | null;
      builtIn?: boolean;
      orderNo?: number;
    }

    interface UpsertPayload {
      name?: string | null;
      description?: string | null;
      systemPrompt?: string | null;
      enabledTools?: string[] | null;
      enabledSkills?: string[] | null;
    }

    /**
     * 博主名册条目：对应后端 ProjectCreator 的 Roster 视图。<br>
     * stage 全阶段：CANDIDATE / SHORTLISTED / LOCKED / SIGNED / PUBLISHED / SETTLED / DROPPED
     */
    type RosterStage =
      | 'CANDIDATE'
      | 'SHORTLISTED'
      | 'LOCKED'
      | 'SIGNED'
      | 'PUBLISHED'
      | 'SETTLED'
      | 'DROPPED';

    interface RosterEntry {
      id: number;
      projectId: number;
      creatorId: number;
      stage: RosterStage;
      priority?: number | null;
      quotedPrice?: number | null;
      currency?: string | null;
      assignedToUserId?: number | null;
      projectNotes?: string | null;
      addedBy?: string | null;
      createdAt?: string | null;
      updatedAt?: string | null;
      creator?: Creator.Person | null;
    }

    interface RosterUpsertPayload {
      stage?: RosterStage | null;
      priority?: number | null;
      quotedPrice?: number | null;
      currency?: string | null;
      assignedToUserId?: number | null;
      projectNotes?: string | null;
    }
  }

  /**
   * namespace Session
   *
   * backend api module: "agent/sessions" (/api/v1/agent/sessions)
   *
   * 一个会话 = 一个博主/议题的方案沟通入口。
   *
   * - 属于某个项目（projectId，非必填但强烈推荐）
   * - 前端 WebSocket 发消息时把 id 放到 payload.sessionId，后端据此路由到对应上下文
   */
  namespace Session {
    /**
     * 会话业务类型，与后端 ChatSession.SessionType 对齐：
     * - GENERAL 通用对话
     * - ALLOCATION 博主分配（项目级别，无 creatorId）
     * - BLOGGER_BRIEF 博主方案（绑定某个 creator）
     * - CONTENT_REVIEW 内容审稿（绑定某个 creator）
     * - DATA_TRACK 数据追踪（绑定某个 creator）
     */
    type SessionType = 'GENERAL' | 'ALLOCATION' | 'BLOGGER_BRIEF' | 'CONTENT_REVIEW' | 'DATA_TRACK';

    interface Item {
      id: number;
      userId: number;
      projectId?: number | null;
      orgTag?: string | null;
      title?: string | null;
      status?: 'ACTIVE' | 'ARCHIVED' | string;
      lastMessageAt?: string | null;
      createdAt?: string | null;
      updatedAt?: string | null;
      /** 后端 AgentMessage 写到该会话里时记录的最大 step（UI 角标用） */
      messageCount?: number | null;
      /** 会话业务类型；缺省视为 GENERAL */
      sessionType?: SessionType | null;
      /** 绑定的博主 id（BLOGGER_BRIEF / CONTENT_REVIEW / DATA_TRACK 必填） */
      creatorId?: number | null;
    }

    interface CreateParams {
      projectId: number;
      title?: string | null;
      sessionType?: SessionType;
      creatorId?: number | null;
    }

    /** 一条历史消息（对应后端 AgentMessage 持久化）。 */
    interface Message {
      id?: number;
      sessionId?: number;
      /** 'user' / 'assistant' / 'tool' / 'system' */
      role: 'user' | 'assistant' | 'tool' | 'system' | string;
      /** 纯文本正文（tool 消息可能为空） */
      content?: string | null;
      /** assistant 想调用的工具 json 数组（function_call 结构） */
      toolCalls?: Array<Record<string, any>> | null;
      /** tool 消息对应哪一次 tool_call 的 id */
      toolCallId?: string | null;
      /** 结构化 tool 结果原始 json，tool 消息有 */
      toolResult?: Record<string, any> | null;
      createdAt?: string | null;
    }
  }

  /**
   * namespace Xhs
   *
   * backend api module:
   *
   * - admin xhs cookie manage: /api/v1/admin/xhs-cookies
   * - agent-driven refresh / search / pgy are called via chat → tools; 前端单纯展示 cookie 健康度 + 一键触发 agent 任务。
   */
  namespace Xhs {
    /**
     * 统一凭证平台：
     * - xhs_pc / xhs_creator / xhs_pgy / xhs_qianfan：基于浏览器 Cookie 的 Spider_XHS 抓取
     * - xhs_spotlight：小红书聚光广告 MarketingAPI（OAuth2 access_token）
     * - xhs_competitor：xhsCompetitorNote_website 接入（Supabase URL + key）
     */
    type Platform =
      | 'xhs_pc'
      | 'xhs_creator'
      | 'xhs_pgy'
      | 'xhs_qianfan'
      | 'xhs_spotlight'
      | 'xhs_competitor';
    type Status = 'ACTIVE' | 'EXPIRED' | 'BANNED' | 'DISABLED';

    /** UI 侧的"数据源族"分组：在数据源中心页用于切 tab。 */
    type DataSourceFamily = 'xhs_web' | 'xhs_spotlight' | 'xhs_competitor';

    interface Cookie {
      id: number;
      ownerOrgTag: string;
      platform: Platform;
      accountLabel?: string | null;
      cookiePreview?: string | null;
      /** 明文 cookie 里出现的 key 名字，逗号分隔；用于前端"是否带了 a1/web_session/webId"的权威判断。 */
      cookieKeys?: string | null;
      note?: string | null;
      status: Status;
      priority: number;
      successCount: number;
      failCount: number;
      lastUsedAt?: string | null;
      lastCheckedAt?: string | null;
      lastError?: string | null;
      createdBy?: string | null;
      createdAt?: string | null;
      updatedAt?: string | null;
    }

    interface CookieCreatePayload {
      platform: Platform;
      cookie: string;
      accountLabel?: string | null;
      note?: string | null;
      priority?: number | null;
    }

    interface CookieUpdatePayload {
      cookie?: string | null;
      accountLabel?: string | null;
      note?: string | null;
      priority?: number | null;
      status?: Status | null;
    }

    interface CookieListResponse {
      items: Cookie[];
      platforms: Platform[];
      /** 后端声明的必填 key 清单（例如 ["a1","web_session","webId"]），前端跟着它做完整性判断。 */
      requiredFields: string[];
      /** 后端 xhs.cookie-secret 是否仍是默认值，UI 需提示运维配置。 */
      insecureDefault: boolean;
    }

    interface CookieValidateResponse {
      detectedKeys: string[];
      missingRequired: string[];
      ok: boolean;
    }

    /**
     * 扫码登录会话生命周期（与后端 XhsLoginSession.Status 对齐）：
     *   PENDING   → 刚创建，子进程还没拉起
     *   QR_READY  → 二维码已就绪，可展示给业务员扫
     *   SCANNED   → 业务员扫到了（XHS 文案 "已扫描，请确认"）
     *   CONFIRMED → 业务员手机确认了，cookie 正在落库
     *   SUCCESS   → 全部成功，cookie 已入池
     *   FAILED    → 运行时错误（脚本异常 / 找不到二维码）
     *   EXPIRED   → 超过 expires_seconds 没完成
     *   CANCELLED → 业务员或管理员主动取消
     */
    type LoginStatus =
      | 'PENDING'
      | 'QR_READY'
      | 'SCANNED'
      | 'CONFIRMED'
      | 'SUCCESS'
      | 'FAILED'
      | 'EXPIRED'
      | 'CANCELLED';

    interface LoginStartResponse {
      sessionId: string;
      status: LoginStatus;
      expiresAt: string;
      platforms: Platform[];
      wsPathHint: string;
    }

    interface LoginStatusResponse {
      sessionId: string;
      status: LoginStatus;
      platforms: string;
      capturedPlatforms: string;
      missingPlatforms: string;
      errorMessage: string;
      startedAt: string;
      finishedAt: string;
    }

    /** /ping 连通性测试返回 —— 后端用当前 cookie 实际打一条平台轻量 API 验活。 */
    interface CookiePingResult {
      ok: boolean;
      /** 平台轻量 API 的往返耗时（ms）。 */
      latencyMs?: number | null;
      /** 失败时的分类：cookie_invalid / network / http_4xx / http_5xx / unsupported_platform / internal。 */
      errorType?: string | null;
      /** 失败时的简短说明，面向业务员展示。 */
      message?: string | null;
      /** 成功时带一点平台返回的可识别信号（比如 user_id 或 advertiser_id），便于目视确认。 */
      platformSignal?: string | null;
      /** 后端把测试结果也写回了 xhs_cookies.lastCheckedAt，所以刷新列表后可见。 */
      checkedAt?: string | null;
    }

    /** WebSocket 单帧 payload —— 和后端 XhsLoginWebSocketHandler 写出去的结构对齐。 */
    interface LoginWsFrame {
      type:
        | 'snapshot'
        | 'qr_ready'
        | 'status'
        | 'success'
        | 'error'
        | 'closed'
        | 'pong'
        | string;
      payload: Record<string, any>;
    }
  }

  namespace Document {
    interface DownloadResponse {
      fileName: string;
      downloadUrl: string;
      fileSize: number;
      fileMd5?: string;
    }

    interface ReferenceDetailResponse extends Chat.ReferenceEvidence {
      referenceNumber: number;
    }
  }
}
