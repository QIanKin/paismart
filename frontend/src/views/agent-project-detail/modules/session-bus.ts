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

  function ensureList(sid: number): AgentMessage[] {
    if (!messagesBySession.value[sid]) messagesBySession.value[sid] = [];
    return messagesBySession.value[sid];
  }

  function setMessages(sid: number, list: AgentMessage[]) {
    messagesBySession.value[sid] = list;
  }

  function pushUser(sid: number, content: string) {
    const m: AgentMessage = {
      localId: `u-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      role: 'user',
      content,
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

  return {
    currentProjectId,
    currentSessionId,
    messagesBySession,
    askUserBySession,
    todosBySession,
    currentStepBySession,
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
    setCurrentStep
  };
});

export type AgentSessionStore = ReturnType<typeof useAgentSessionStore>;

/** 把后端持久化的 AgentMessage[] 映射成前端 AgentMessage[]。 */
export function mapServerMessages(list: Api.Session.Message[]): AgentMessage[] {
  const out: AgentMessage[] = [];
  for (const msg of list || []) {
    const role = msg.role as AgentMessage['role'];
    if (role === 'tool') {
      // tool 消息挂在上一条 assistant 上
      const prev = [...out].reverse().find(m => m.role === 'assistant');
      if (!prev) continue;
      if (!prev.toolCalls) prev.toolCalls = [];
      const result = msg.toolResult || {};
      prev.toolCalls.push({
        toolUseId: msg.toolCallId || `hist-${out.length}`,
        tool: (result as any).tool || 'tool',
        status: (result as any).isError ? 'error' : 'ok',
        result: {
          summary: (result as any).summary,
          preview: typeof (result as any).preview === 'string' ? (result as any).preview : '',
          isError: Boolean((result as any).isError),
          durationMs: (result as any).durationMs
        },
        startedAt: msg.createdAt ? new Date(msg.createdAt).getTime() : Date.now(),
        finishedAt: msg.createdAt ? new Date(msg.createdAt).getTime() : Date.now()
      });
      continue;
    }
    out.push({
      localId: `srv-${msg.id ?? out.length}`,
      messageId: msg.id != null ? String(msg.id) : undefined,
      role: role === 'assistant' || role === 'user' || role === 'system' ? role : 'assistant',
      content: msg.content || '',
      status: 'finished',
      toolCalls: [],
      ts: msg.createdAt ? new Date(msg.createdAt).getTime() : Date.now()
    });
  }
  return out;
}

export type MessagesRef = WritableComputedRef<AgentMessage[]>;
