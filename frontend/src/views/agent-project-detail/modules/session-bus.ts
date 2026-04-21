/**
 * session-bus.ts
 *
 * 跨项目详情页内部组件的状态总线，挂到 pinia 便于 sidebar / chat / tool-trail 三个模块都能对同一会话达成一致。
 *
 * 为什么独立一个 store？
 *
 * - 老的 useChatStore 把 userId-level 的 WS 链路 + 单一 list 做得耦合，改起来容易 打乱历史行为；新会话界面直接读老 store 的 wsData/wsSend 就够用。
 * - 本 store 只负责：currentProjectId / currentSessionId / message 列表（按会话分） / tool trail（按会话分）。
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

export const useAgentSessionStore = defineStore('agent-session-bus', () => {
  const currentProjectId = ref<number | null>(null);
  const currentSessionId = ref<number | null>(null);

  // sessionId -> messages
  const messagesBySession = ref<Record<number, AgentMessage[]>>({});
  // sessionId -> 正在 stream 的 assistant 消息 localId（便于 chunk 累加）
  const streamingIdBySession = ref<Record<number, string | null>>({});

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

  function finishToolCall(sid: number, toolUseId: string, result: ToolCallView['result'], isError: boolean) {
    const list = ensureList(sid);
    // 从最近 assistant 消息往前找
    for (let i = list.length - 1; i >= 0; i -= 1) {
      const m = list[i];
      if (m.role !== 'assistant' || !m.toolCalls) continue;
      const call = m.toolCalls.find(c => c.toolUseId === toolUseId);
      if (call) {
        call.status = isError ? 'error' : 'ok';
        call.result = result;
        call.finishedAt = Date.now();
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
  }

  function failAssistant(sid: number, err: string) {
    const m = getStreaming(sid);
    if (m) {
      m.status = 'error';
      if (!m.content) m.content = err;
      else m.content += `\n\n[错误] ${err}`;
    }
    streamingIdBySession.value[sid] = null;
  }

  return {
    currentProjectId,
    currentSessionId,
    messagesBySession,
    setMessages,
    ensureList,
    pushUser,
    pushAssistantPending,
    appendChunk,
    upsertToolCall,
    finishToolCall,
    completeAssistant,
    failAssistant
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
