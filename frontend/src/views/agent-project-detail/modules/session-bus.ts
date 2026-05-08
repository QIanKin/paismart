/**
 * session-bus.ts
 *
 * 跨项目详情页内部组件的状态总线，挂到 pinia 便于 sidebar / chat / tool-trail 三个模块都能对同一会话达成一致。
 *
 * 为什么独立一个 store？
 *
 * - 老的 useChatStore 把 userId-level 的 WS 链路 + 单一 list 做得耦合，改起来容易打乱历史行为；
 *   新会话界面直接读老 store 的 wsData/wsSend 就够用。
 * - 本 store 负责：currentProjectId / currentSessionId / message 列表（按会话分）/ tool trail（按会话分）
 *   + Phase 4a 新加的「活体状态」：tool progress 文本、ask_user 悬挂、todos 清单、step 指示器。
 */
import type { WritableComputedRef } from 'vue';

export interface AgentMessage {
  /** 前端生成的 id（用于找到同一条 assistant 消息持续追 chunk） */
  localId: string;
  /** 后端 messageId（有的话） */
  messageId?: string;
  role: 'user' | 'assistant' | 'tool' | 'system';
  content: string;
  attachments?: Api.Session.Attachment[];
  /** 'pending' 刚发送、'streaming' 正在推 chunk、'finished' 完成、'error' 出错 */
  status?: 'pending' | 'streaming' | 'finished' | 'error' | 'stopped';
  /** 一条 assistant 消息对应触发的 tool 调用（按出现顺序） */
  toolCalls?: ToolCallView[];
  ts: number;
}

export interface ToolCallView {
  toolUseId: string;
  tool: string;
  userFacingName?: string;
  summary?: string;
  readOnly?: boolean;
  input?: any;
  status: 'running' | 'ok' | 'error';
  /** tool_progress 事件累积到的最近一条描述（工具执行过程中的阶段提示） */
  progressText?: string;
  result?: {
    summary?: string;
    preview?: string;
    isError?: boolean;
    durationMs?: number;
    meta?: any;
  };
  startedAt: number;
  finishedAt?: number;
}

/** Agent 对用户发起的结构化提问，展示为聊天气泡 + 选项按钮。 */
export interface AskUserPrompt {
  /** 同一轮 assistant 消息内，一问一次即可；用 messageId 去重 */
  messageId?: string;
  question: string;
  options: string[];
  /** 用户已回答就标 true，UI 灰化按钮 */
  answered: boolean;
  askedAt: number;
}

export interface TodoItem {
  id: string;
  content: string;
  status: 'pending' | 'in_progress' | 'completed' | 'cancelled';
}

export const useAgentSessionStore = defineStore('agent-session-bus', () => {
  const currentProjectId = ref<number | null>(null);
  const currentSessionId = ref<number | null>(null);

  // sessionId -> messages
  const messagesBySession = ref<Record<number, AgentMessage[]>>({});
  // sessionId -> 正在 stream 的 assistant 消息 localId（便于 chunk 累加）
  const streamingIdBySession = ref<Record<number, string | null>>({});

  // ---- Phase 4a 活体状态 ----
  // sessionId -> 当前待回答的 ask_user 悬挂；每会话最多一个（同一轮内覆盖）
  const askUserBySession = ref<Record<number, AskUserPrompt | null>>({});
  // sessionId -> todo 清单快照（todo_write 工具整体替换）
  const todosBySession = ref<Record<number, TodoItem[]>>({});
  // sessionId -> 当前 step 号（step_start 递增，step_end 可能保留）
  const currentStepBySession = ref<Record<number, number | null>>({});

  // 哪些会话当前在等 Agent 回复——agent-chat.vue 通过本字段判断「发送按钮可用 / stop 按钮可见」。
  // 之前这状态只活在组件本地（reactive Set），离开 chat 组件后 WS 推过来就没人接 →
  // Bug-fix「流式中断不续接」：把状态升到 store 层，组件可在挂载时直接读，不再独立 watch。
  const pendingSessionIds = ref<Set<number>>(new Set());
  // 同样从组件迁过来：最后一次本端发送命中的 sid，作为后端 envelope 没带 sessionId 时的回退。
  const lastSentSessionId = ref<number | null>(null);

  function markPending(sid: number) {
    if (!sid) return;
    const next = new Set(pendingSessionIds.value);
    next.add(sid);
    pendingSessionIds.value = next;
    lastSentSessionId.value = sid;
  }
  function clearPending(sid: number) {
    if (!pendingSessionIds.value.has(sid)) return;
    const next = new Set(pendingSessionIds.value);
    next.delete(sid);
    pendingSessionIds.value = next;
  }
  function isSessionPending(sid: number): boolean {
    return pendingSessionIds.value.has(sid);
  }

  function ensureList(sid: number): AgentMessage[] {
    if (!messagesBySession.value[sid]) messagesBySession.value[sid] = [];
    return messagesBySession.value[sid];
  }

  function setMessages(sid: number, list: AgentMessage[]) {
    messagesBySession.value[sid] = list;
    streamingIdBySession.value[sid] = null;
    currentStepBySession.value[sid] = null;
  }

  function pushUser(sid: number, content: string, attachments: Api.Session.Attachment[] = []) {
    const m: AgentMessage = {
      localId: `u-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      role: 'user',
      content,
      attachments,
      status: 'finished',
      ts: Date.now()
    };
    ensureList(sid).push(m);
    return m;
  }

  function pushAssistantPending(sid: number): AgentMessage {
    const m: AgentMessage = {
      localId: `a-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      role: 'assistant',
      content: '',
      status: 'pending',
      toolCalls: [],
      ts: Date.now()
    };
    ensureList(sid).push(m);
    streamingIdBySession.value[sid] = m.localId;
    return m;
  }

  function getStreaming(sid: number): AgentMessage | null {
    const localId = streamingIdBySession.value[sid];
    if (!localId) return null;
    return ensureList(sid).find(m => m.localId === localId) || null;
  }

  function appendChunk(sid: number, delta: string) {
    const m = getStreaming(sid);
    if (!m) return;
    m.status = 'streaming';
    m.content += delta;
  }

  function upsertToolCall(
    sid: number,
    call: Omit<ToolCallView, 'status' | 'startedAt'> & { status?: ToolCallView['status'] }
  ) {
    const m = getStreaming(sid) ?? ensureList(sid)[ensureList(sid).length - 1];
    if (!m || m.role !== 'assistant') return;
    if (!m.toolCalls) m.toolCalls = [];
    const existing = m.toolCalls.find(c => c.toolUseId === call.toolUseId);
    if (existing) {
      Object.assign(existing, call);
      if (!existing.status) existing.status = 'running';
    } else {
      m.toolCalls.push({ ...call, status: call.status ?? 'running', startedAt: Date.now() });
    }
  }

  /**
   * 找到某个 toolUseId 对应的 ToolCallView 并写入 progressText。
   * tool_progress 事件可能早于 tool_call 到达（极端时序），那就先忽略。
   */
  function updateToolProgress(sid: number, toolUseId: string, text: string) {
    const list = ensureList(sid);
    for (let i = list.length - 1; i >= 0; i -= 1) {
      const m = list[i];
      if (m.role !== 'assistant' || !m.toolCalls) continue;
      const call = m.toolCalls.find(c => c.toolUseId === toolUseId);
      if (call) {
        call.progressText = text;
        return;
      }
    }
  }

  function finishToolCall(sid: number, toolUseId: string, result: ToolCallView['result'], isError: boolean) {
    const list = ensureList(sid);
    for (let i = list.length - 1; i >= 0; i -= 1) {
      const m = list[i];
      if (m.role !== 'assistant' || !m.toolCalls) continue;
      const call = m.toolCalls.find(c => c.toolUseId === toolUseId);
      if (call) {
        call.status = isError ? 'error' : 'ok';
        call.result = result;
        call.finishedAt = Date.now();
        // progressText 在 result 出来后对 UI 已无意义，清掉省显示空间
        call.progressText = undefined;
        return;
      }
    }
  }

  function completeAssistant(sid: number, finishReason?: string) {
    const m = getStreaming(sid);
    if (m) {
      m.status = finishReason === 'stopped' ? 'stopped' : 'finished';
    }
    streamingIdBySession.value[sid] = null;
    // 一轮结束：清掉 step 指示器，ask_user 如果还没回答保留（允许用户下一轮再回）
    currentStepBySession.value[sid] = null;
  }

  function failAssistant(sid: number, err: string) {
    const m = getStreaming(sid);
    if (m) {
      m.status = 'error';
      if (!m.content) m.content = err;
      else m.content += `\n\n[错误] ${err}`;
    }
    streamingIdBySession.value[sid] = null;
    currentStepBySession.value[sid] = null;
  }

  // ---- Phase 4a 新 action ----

  function setAskUser(sid: number, question: string, options: string[], messageId?: string) {
    askUserBySession.value[sid] = {
      messageId,
      question,
      options: Array.isArray(options) ? options : [],
      answered: false,
      askedAt: Date.now()
    };
  }

  function markAskUserAnswered(sid: number) {
    const cur = askUserBySession.value[sid];
    if (cur) cur.answered = true;
  }

  function clearAskUser(sid: number) {
    askUserBySession.value[sid] = null;
  }

  function setTodos(sid: number, todos: TodoItem[]) {
    todosBySession.value[sid] = Array.isArray(todos) ? todos : [];
  }

  function setCurrentStep(sid: number, step: number | null) {
    currentStepBySession.value[sid] = step;
  }

  /**
   * 把后端「正在进行中」的 turn 快照（{@link Api.Session.LiveSnapshot}）拼接到当前会话尾。
   *
   * 用于「用户切走或刷新后再回来」的场景：先 fetchSessionMessages 拿到已 appendTurn 的历史，
   * 再调 fetchSessionLive 拿到正在生成中的 partial，本方法把它转成一条 streaming assistant message
   * 并把已完成的 tool 卡片回填，让 WS 后续推送的 chunk 能继续 append 到它身上。
   *
   * 已经有这个 messageId 在 list 里就不重复添加（防多次刷新）。
   */
  function attachLiveSnapshot(sid: number, snap: Api.Session.LiveSnapshot | null | undefined) {
    if (!sid || !snap || !snap.messageId) return;
    if (snap.status && snap.status !== 'running') return;

    const list = ensureList(sid);
    const exists = list.find(m => m.messageId === snap.messageId);
    const partial = snap.partialContent || '';
    const toolCalls: ToolCallView[] = (snap.toolCalls || []).map(tc => ({
      toolUseId: tc.toolUseId,
      tool: String(tc.tool || 'tool'),
      userFacingName: tc.userFacingName || undefined,
      summary: tc.summary || undefined,
      readOnly: tc.readOnly == null ? undefined : Boolean(tc.readOnly),
      input: tc.input,
      status: (tc.status === 'ok' || tc.status === 'error') ? tc.status : 'running',
      progressText: tc.progressText || undefined,
      result: tc.status === 'ok' || tc.status === 'error'
        ? {
            summary: tc.summary || undefined,
            preview: tc.preview || undefined,
            isError: Boolean(tc.isError),
            durationMs: Number(tc.durationMs || 0),
            meta: tc.meta || undefined
          }
        : undefined,
      startedAt: Number(tc.startedAt || snap.startedAt || Date.now()),
      finishedAt: tc.finishedAt ? Number(tc.finishedAt) : undefined
    }));

    if (exists) {
      // 已经被 WS chunk 同步过了；只补足未出现的 tool calls / 把 content 用 partial 覆盖（更长者优先）
      if ((partial?.length || 0) > (exists.content?.length || 0)) {
        exists.content = partial;
      }
      if (!exists.toolCalls) exists.toolCalls = [];
      for (const tc of toolCalls) {
        if (!exists.toolCalls.some(c => c.toolUseId === tc.toolUseId)) exists.toolCalls.push(tc);
      }
      // 仍在 running，确保 streaming 标志在
      if (exists.status !== 'finished' && exists.status !== 'error') {
        exists.status = partial ? 'streaming' : 'pending';
        streamingIdBySession.value[sid] = exists.localId;
      }
    } else {
      const m: AgentMessage = {
        localId: `live-${snap.messageId}`,
        messageId: snap.messageId,
        role: 'assistant',
        content: partial,
        status: partial ? 'streaming' : 'pending',
        toolCalls,
        ts: Number(snap.startedAt || Date.now())
      };
      list.push(m);
      streamingIdBySession.value[sid] = m.localId;
    }

    if (snap.currentStep != null) currentStepBySession.value[sid] = Number(snap.currentStep);
    if (Array.isArray(snap.todos)) todosBySession.value[sid] = snap.todos as TodoItem[];
    if (snap.askUser?.question) {
      askUserBySession.value[sid] = {
        messageId: snap.messageId,
        question: snap.askUser.question,
        options: Array.isArray(snap.askUser.options) ? snap.askUser.options : [],
        answered: false,
        askedAt: Number(snap.askUser.askedAt || Date.now())
      };
    }
    // 既然有 live snapshot，本会话肯定还在等回复，pending 标志要补上
    markPending(sid);
  }

  // ---------- 全局 WS 事件订阅 ----------
  // 旧实现把 watch(wsData) 放在 agent-chat.vue 里，组件卸载（用户切走）就再没人接事件，
  // 视频卡片 / chunk 全丢。现在挂在 store 层，整个 SPA 期间常驻。
  const wsWatcherRegistered = ref(false);

  /**
   * 在 setup 中调用一次（agent-chat.vue 挂载时触发）来确保 wsData 被订阅。store 是单例，重复调用安全。
   *
   * 注：因为 useChatStore 内部用 useWebSocket，在 SPA 启动时就会建立连接；本订阅只是把消息分发到
   * sessionBus 各 action，不影响连接生命周期。
   */
  function ensureWsWatcher() {
    if (wsWatcherRegistered.value) return;
    wsWatcherRegistered.value = true;
    const chatStore = useChatStore();
    watch(
      () => chatStore.wsData,
      raw => {
        if (!raw) return;
        let payload: any;
        try {
          payload = JSON.parse(String(raw));
        } catch {
          return;
        }
        if (!payload || payload.type === 'connection') return;
        const eventType = payload.type;
        if (!eventType) return;

        const envelopeSid = Number(payload.sessionId);
        const targetSid: number | null = Number.isFinite(envelopeSid) && envelopeSid > 0
          ? envelopeSid
          : (lastSentSessionId.value || null);
        if (!targetSid) return;

        const data = payload.data || {};
        switch (eventType) {
          case 'start':
            markPending(targetSid);
            break;
          case 'chunk':
            appendChunk(targetSid, String(data.delta || ''));
            break;
          case 'tool_call':
            upsertToolCall(targetSid, {
              toolUseId: String(data.toolUseId || ''),
              tool: String(data.tool || 'tool'),
              userFacingName: data.userFacingName,
              summary: data.summary,
              readOnly: Boolean(data.readOnly),
              input: data.input,
              status: 'running'
            });
            break;
          case 'tool_progress': {
            const tid = String(data.toolUseId || '');
            const text = String(data.message || data.progressType || '');
            if (tid && text) updateToolProgress(targetSid, tid, text);
            break;
          }
          case 'tool_result':
            finishToolCall(
              targetSid,
              String(data.toolUseId || ''),
              {
                summary: data.summary,
                preview: data.preview,
                isError: Boolean(data.isError),
                durationMs: Number(data.durationMs || 0),
                meta: data.meta
              },
              Boolean(data.isError)
            );
            break;
          case 'ask_user': {
            const q = String(data.question || '').trim();
            if (q) setAskUser(targetSid, q, Array.isArray(data.options) ? data.options : [], payload.messageId);
            break;
          }
          case 'todo':
            setTodos(targetSid, Array.isArray(data.todos) ? data.todos : []);
            break;
          case 'step_start':
            setCurrentStep(targetSid, Number(data.step) || null);
            break;
          case 'completion':
            completeAssistant(targetSid, data.finishReason);
            clearPending(targetSid);
            break;
          case 'stopped':
            completeAssistant(targetSid, 'stopped');
            clearPending(targetSid);
            break;
          case 'error':
            failAssistant(targetSid, String(data.message || '服务端错误'));
            clearPending(targetSid);
            break;
          case 'rate_limit':
            failAssistant(
              targetSid,
              `${String(data.message || '请求过于频繁')}${
                Number(data.retryAfterSeconds || 0) > 0
                  ? `，请 ${data.retryAfterSeconds} 秒后再试`
                  : '，请稍后再试'
              }`
            );
            clearPending(targetSid);
            break;
          default:
            break;
        }
      }
    );
  }

  return {
    currentProjectId,
    currentSessionId,
    messagesBySession,
    askUserBySession,
    todosBySession,
    currentStepBySession,
    pendingSessionIds,
    lastSentSessionId,
    setMessages,
    ensureList,
    pushUser,
    pushAssistantPending,
    appendChunk,
    upsertToolCall,
    updateToolProgress,
    finishToolCall,
    completeAssistant,
    failAssistant,
    setAskUser,
    markAskUserAnswered,
    clearAskUser,
    setTodos,
    setCurrentStep,
    attachLiveSnapshot,
    markPending,
    clearPending,
    isSessionPending,
    ensureWsWatcher
  };
});

export type AgentSessionStore = ReturnType<typeof useAgentSessionStore>;

/**
 * 把后端 {@code AgentMessage[]} 历史流映射成前端展示用的 {@link AgentMessage}[]。
 *
 * 关键：后端一个 turn 会落库多条 {@code assistant}（每个 LLM step 一条，可能只有 tool_calls
 * 没有正文）+ 多条 {@code tool}。老版 mapping 对每条 assistant 都建一个气泡，导致"重新进入会话
 * 后，每调用一个工具就冒出一个空气泡"，UI 显示非常杂乱（用户原话）。
 *
 * 这里改成按 {@code messageGroupId} 折叠整个 turn：一个 turn 最多一个 user 气泡 + 一个
 * assistant 气泡；同一 turn 内若 assistant 出现多段正文则用空行拼接，所有 step 的 tool_calls
 * 汇入同一个 toolCalls 数组。tool 消息再按 toolCallId 回填对应卡片的 {@code result}。
 *
 * 如果后端某条历史消息没有 messageGroupId（极早期脏数据），回退到"遇到 user 就起新 turn"的
 * 兜底策略。
 */
export function mapServerMessages(list: Api.Session.Message[]): AgentMessage[] {
  const out: AgentMessage[] = [];
  if (!list || !list.length) return out;
  let currentAssistant: AgentMessage | null = null;
  let currentGroupId: string | null = null;
  // 同一 turn 内 toolCallId -> 已落位的 ToolCallView，tool 消息到达时 O(1) 找回
  const toolCallIndex = new Map<string, ToolCallView>();

  for (const msg of list) {
    const raw = msg as Record<string, any>;
    const role = String(raw.role || '').toLowerCase();
    const gid: string | null = raw.messageGroupId == null ? null : String(raw.messageGroupId);
    const ts = raw.createdAt ? new Date(raw.createdAt).getTime() : Date.now();

    // 换 turn 就重置合并态（没有 gid 的脏数据看 user 起头）
    const turnChanged = gid != null && gid !== currentGroupId;
    if (turnChanged || (!gid && role === 'user')) {
      currentGroupId = gid;
      currentAssistant = null;
      toolCallIndex.clear();
    }

    if (role === 'user' || role === 'system') {
      out.push({
        localId: `srv-${raw.id ?? out.length}`,
        messageId: raw.id != null ? String(raw.id) : undefined,
        role: role as AgentMessage['role'],
        content: String(raw.content || ''),
        attachments: normalizeAttachments(raw.attachments),
        status: 'finished',
        ts
      });
      currentAssistant = null;
      toolCallIndex.clear();
      continue;
    }

    if (role === 'assistant') {
      const text = raw.content == null ? '' : String(raw.content);
      const parsedCalls = parseToolCallsJson(raw.toolCallsJson ?? raw.toolCalls);

      if (!currentAssistant) {
        currentAssistant = {
          localId: `srv-${raw.id ?? out.length}`,
          messageId: raw.id != null ? String(raw.id) : undefined,
          role: 'assistant',
          content: text,
          status: 'finished',
          toolCalls: [],
          ts
        };
        out.push(currentAssistant);
      } else if (text) {
        // 同一 turn 的第二段正文，拼接时留一个空行，避免把两段分析塞成一行
        currentAssistant.content = currentAssistant.content
          ? `${currentAssistant.content}\n\n${text}`
          : text;
      }

      for (const tc of parsedCalls) {
        const id = tc && tc.id ? String(tc.id) : '';
        if (!id) continue;
        if (currentAssistant.toolCalls!.some(c => c.toolUseId === id)) continue;
        const fn = (tc.function || {}) as { name?: string };
        const view: ToolCallView = {
          toolUseId: id,
          tool: String(fn.name || 'tool'),
          // 历史数据里 assistant 还没配对 tool 结果前，先当作成功占位，tool 消息到达后再覆盖
          status: 'ok',
          startedAt: ts
        };
        currentAssistant.toolCalls!.push(view);
        toolCallIndex.set(id, view);
      }
      continue;
    }

    if (role === 'tool') {
      const callId = raw.toolCallId ? String(raw.toolCallId) : '';
      const payload = parseToolPayload(raw.content, raw.toolMetaJson);
      let view = callId ? toolCallIndex.get(callId) : undefined;

      if (!view) {
        // tool_calls 落库失败或顺序异常时的兜底：挂到当前 / 最近一条 assistant 上
        const host = currentAssistant
          ?? ([...out].reverse().find(m => m.role === 'assistant') as AgentMessage | undefined);
        if (!host) continue;
        if (!host.toolCalls) host.toolCalls = [];
        view = {
          toolUseId: callId || `hist-${raw.id ?? out.length}`,
          tool: String(raw.toolName || 'tool'),
          status: 'ok',
          startedAt: ts
        };
        host.toolCalls.push(view);
        if (callId) toolCallIndex.set(callId, view);
      }
      if (raw.toolName) view.tool = String(raw.toolName);

      view.status = payload.isError ? 'error' : 'ok';
      view.result = {
        summary: payload.summary,
        preview: payload.preview,
        isError: payload.isError,
        durationMs: Number(raw.toolDurationMs || 0),
        meta: payload.meta
      };
      view.finishedAt = ts;
      continue;
    }
  }
  return out;
}

/** 解析 assistant.toolCallsJson（可能是字符串、已解析的数组，或空）。 */
function parseToolCallsJson(raw: unknown): Array<Record<string, any>> {
  if (!raw) return [];
  if (Array.isArray(raw)) return raw as Array<Record<string, any>>;
  if (typeof raw === 'string') {
    const s = raw.trim();
    if (!s) return [];
    try {
      const v = JSON.parse(s);
      return Array.isArray(v) ? v : [];
    } catch {
      return [];
    }
  }
  return [];
}

/**
 * tool 消息的 content 是 {@code ToolExecutor.resultToLlmPayload} 的产物，通常是一个 JSON
 * 对象（{summary, preview, isError, ...}），个别老工具直接回字符串。
 *
 * meta 优先级（高 → 低）：
 *  1. 后端 Bug-fix 之后随消息一起落库的 {@code toolMetaJson}（即 ToolResult.meta）；
 *  2. content JSON 内的 {@code meta} 子对象（极少数老工具只把 meta 塞进 data）；
 *  3. 从 content JSON 的 data.video.url / data.transcript.url / noteId / title 兜底重建
 *     —— 兼容 Bug-fix 上线前的历史数据。
 */
function parseToolPayload(content: unknown, toolMetaJsonRaw?: unknown): {
  summary?: string;
  preview: string;
  isError: boolean;
  meta?: any;
} {
  const raw = content == null ? '' : String(content);
  // 先尝试从持久化的 meta JSON 拿——Bug-fix 之后所有新消息都走这条
  let persistedMeta: Record<string, unknown> | undefined;
  if (toolMetaJsonRaw) {
    const metaStr = String(toolMetaJsonRaw).trim();
    if (metaStr) {
      try {
        const parsed = JSON.parse(metaStr);
        if (parsed && typeof parsed === 'object') {
          persistedMeta = parsed as Record<string, unknown>;
        }
      } catch {
        /* ignore: 后端落库异常，不致命 */
      }
    }
  }

  if (!raw) {
    return { preview: '', isError: false, meta: persistedMeta };
  }

  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === 'object') {
      const summary =
        typeof parsed.summary === 'string'
          ? parsed.summary
          : typeof parsed.message === 'string'
            ? parsed.message
            : undefined;
      const preview =
        typeof parsed.preview === 'string'
          ? parsed.preview
          : typeof parsed.data === 'string'
            ? parsed.data.slice(0, 400)
            : truncate(raw, 400);
      const inlineMeta = (parsed.meta as Record<string, unknown> | undefined) ?? undefined;
      // 兼容 Bug-fix 之前历史消息：从 data.video.url / data.transcript.url 重建富 UI 字段
      const videoUrlRaw = parsed.video?.url ?? parsed.data?.video?.url;
      const videoUrl = typeof videoUrlRaw === 'string' && videoUrlRaw ? videoUrlRaw : undefined;
      const trUrlRaw = parsed.transcript?.url ?? parsed.data?.transcript?.url;
      const transcriptUrl = typeof trUrlRaw === 'string' && trUrlRaw ? trUrlRaw : undefined;
      let meta: Record<string, unknown> | undefined =
        persistedMeta ?? inlineMeta ?? undefined;
      if (videoUrl || transcriptUrl || parsed.noteId != null || parsed.title != null) {
        const base: Record<string, unknown> = meta && typeof meta === 'object' ? { ...meta } : {};
        if (videoUrl && base.videoUrl == null) base.videoUrl = videoUrl;
        if (transcriptUrl && base.transcriptUrl == null) base.transcriptUrl = transcriptUrl;
        if (parsed.noteId != null && base.noteId == null) base.noteId = parsed.noteId;
        if (parsed.title != null && base.title == null) base.title = parsed.title;
        if (parsed.errorType != null && base.errorCode == null) base.errorCode = parsed.errorType;
        meta = base;
      }
      return {
        summary,
        preview,
        isError: Boolean(parsed.isError),
        meta
      };
    }
  } catch {
    /* fallthrough: 不是 JSON 就当纯文本预览 */
  }
  return { preview: truncate(raw, 400), isError: false, meta: persistedMeta };
}

function truncate(s: string, n: number) {
  return s.length <= n ? s : `${s.slice(0, n)}…`;
}

function normalizeAttachments(raw: unknown): Api.Session.Attachment[] {
  if (!Array.isArray(raw)) return [];
  return raw
    .map(item => {
      const rec = item as Record<string, unknown>;
      const url = rec.url == null ? null : String(rec.url);
      return {
        type: rec.type == null ? 'image' : String(rec.type),
        objectKey: rec.objectKey == null ? null : String(rec.objectKey),
        fileName: rec.fileName == null ? null : String(rec.fileName),
        mimeType: rec.mimeType == null ? null : String(rec.mimeType),
        size:
          rec.size == null
            ? null
            : typeof rec.size === 'number'
              ? rec.size
              : Number(String(rec.size)),
        url
      } satisfies Api.Session.Attachment;
    })
    .filter(item => Boolean(item.url));
}

export type MessagesRef = WritableComputedRef<AgentMessage[]>;
