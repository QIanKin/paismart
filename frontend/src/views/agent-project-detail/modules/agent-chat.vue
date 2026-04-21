<script setup lang="ts">
/**
 * 项目详情页中间的 Agent 聊天区。
 *
 * WebSocket 复用 {@link useChatStore}（用户级长连接）， 但把「事件解析 / 消息落盘」全放进本地的 agent-session-bus。
 *
 * 协议（由后端 AgentEventPublisher 下发）： envelope: { type, ts, sessionId, messageId, data: {...} } type ∈ { start, chunk,
 * tool_call, tool_progress, tool_result, plan, todo, completion, stopped, error, rate_limit, ask_user, step_start,
 * step_end }
 *
 * 发送：走 chatStore.wsSend(JSON.stringify({ type:'chat', content, sessionId, projectId }))
 */
import { NButton, NEmpty, NScrollbar, NSpin, NTag, NTooltip } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { fetchSessionMessages } from '@/service/api';
import { mapServerMessages, useAgentSessionStore } from './session-bus';

interface Props {
  projectId: number;
  sessionId: number;
}
const props = defineProps<Props>();

const chatStore = useChatStore();
const sessionBus = useAgentSessionStore();
// storeToRefs 不转函数，wsSend 这种 action 直接从 store 实例拿
const { connectionStatus, wsData } = storeToRefs(chatStore);
const wsSend = chatStore.wsSend;

// 当前会话 messages（响应式）
const messages = computed(() => sessionBus.messagesBySession[props.sessionId] || []);
const loadingHistory = ref(false);

// 用户发送消息时记住哪个业务会话在"等回复"；因为后端事件不带业务 sessionId，
// 我们用"最近一次 send 的 businessSessionId"作为归属键。
const pendingBusinessSessionId = ref<number | null>(null);
const currentAssistantSessionId = ref<number | null>(null);

const inputText = ref('');
const inputRef = ref<HTMLTextAreaElement | null>(null);

const isSending = computed(() => pendingBusinessSessionId.value !== null);

async function loadHistory(sid: number) {
  loadingHistory.value = true;
  try {
    const { data } = await fetchSessionMessages(sid);
    sessionBus.setMessages(sid, mapServerMessages(data ?? []));
  } finally {
    loadingHistory.value = false;
  }
}

watch(
  () => props.sessionId,
  (sid, old) => {
    if (sid && sid !== old) {
      loadHistory(sid);
    }
  },
  { immediate: true }
);

// ============ 解析后端 WS 事件 ============
watch(wsData, raw => {
  if (!raw) return;
  let payload: any;
  try {
    payload = JSON.parse(raw);
  } catch {
    return;
  }

  // 兼容旧协议（只有顶层 connection/chunk/error）：
  if (payload.type === 'connection') return;

  const eventType = payload.type;
  if (!eventType) return;

  // 事件归属：如果有正在等回复的业务会话，事件都归它；否则忽略（防止老 chat 路由事件污染）
  const targetSid = currentAssistantSessionId.value ?? pendingBusinessSessionId.value;
  if (!targetSid) return;

  const data = payload.data || {};

  switch (eventType) {
    case 'start':
      currentAssistantSessionId.value = targetSid;
      break;

    case 'chunk':
      sessionBus.appendChunk(targetSid, String(data.delta || ''));
      break;

    case 'tool_call':
      sessionBus.upsertToolCall(targetSid, {
        toolUseId: String(data.toolUseId || ''),
        tool: String(data.tool || 'tool'),
        userFacingName: data.userFacingName,
        summary: data.summary,
        readOnly: Boolean(data.readOnly),
        input: data.input,
        status: 'running'
      });
      break;

    case 'tool_result':
      sessionBus.finishToolCall(
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

    case 'completion':
      sessionBus.completeAssistant(targetSid, data.finishReason);
      pendingBusinessSessionId.value = null;
      currentAssistantSessionId.value = null;
      break;

    case 'stopped':
      sessionBus.completeAssistant(targetSid, 'stopped');
      pendingBusinessSessionId.value = null;
      currentAssistantSessionId.value = null;
      break;

    case 'error':
      sessionBus.failAssistant(targetSid, String(data.message || '服务端错误'));
      pendingBusinessSessionId.value = null;
      currentAssistantSessionId.value = null;
      break;

    case 'rate_limit':
      sessionBus.failAssistant(targetSid, `请求过于频繁，请 ${data.retryAfterSeconds || 0} 秒后再试`);
      pendingBusinessSessionId.value = null;
      currentAssistantSessionId.value = null;
      break;

    default:
      // plan / todo / step_start / step_end / tool_progress / ask_user 暂不展示，后续可加
      break;
  }
});

// ============ 滚动到底 ============
const scrollRef = ref<any>(null);
function scrollToBottom() {
  nextTick(() => {
    scrollRef.value?.scrollTo({ top: 999999, behavior: 'smooth' });
  });
}
watch(() => [messages.value.length, messages.value[messages.value.length - 1]?.content], scrollToBottom);

// ============ 发送 ============
async function handleSend() {
  const text = inputText.value.trim();
  if (!text) return;
  if (isSending.value) return; // 上一条还没收完先别发

  if (connectionStatus.value !== 'OPEN') {
    window.$message?.warning('连接中，请稍等…');
    return;
  }

  sessionBus.pushUser(props.sessionId, text);
  sessionBus.pushAssistantPending(props.sessionId);
  pendingBusinessSessionId.value = props.sessionId;

  wsSend(
    JSON.stringify({
      type: 'chat',
      content: text,
      sessionId: props.sessionId,
      projectId: props.projectId
    })
  );
  inputText.value = '';
  scrollToBottom();
}

async function handleStop() {
  const { error, data } = await (
    await import('@/service/request')
  ).request<{ cmdToken: string }>({
    url: 'chat/websocket-token',
    baseURL: 'proxy-api'
  });
  if (error) return;
  wsSend(JSON.stringify({ type: 'stop', _internal_cmd_token: data.cmdToken }));
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.ctrlKey) {
    e.preventDefault();
    handleSend();
  }
}

function roleLabel(role: string) {
  if (role === 'user') return '你';
  if (role === 'assistant') return '小蜜蜂';
  return role;
}
</script>

<template>
  <div class="h-full flex-col">
    <!-- Header -->
    <div class="flex items-center justify-between gap-3 border-b b-#e5e7eb20 px-20px py-10px dark:b-#1f2937">
      <div class="flex min-w-0 items-center gap-2">
        <SvgIcon icon="solar:ghost-smile-bold-duotone" class="shrink-0 text-20px text-primary-500" />
        <div class="flex-col gap-1px">
          <div class="flex items-center gap-2">
            <span class="truncate text-14px font-semibold">会话 #{{ sessionId }}</span>
            <NTag v-if="connectionStatus === 'OPEN'" size="tiny" type="success" :bordered="false">已连接</NTag>
            <NTag v-else size="tiny" type="warning" :bordered="false">
              {{
                connectionStatus === 'CONNECTING' ? '连接中' : connectionStatus === 'RECONNECTING' ? '重连中' : '未连接'
              }}
            </NTag>
          </div>
          <NTooltip>
            <template #trigger>
              <span class="text-11px text-stone-400">
                这段对话是一条 <b>独立的 AI 记忆</b>，与其他会话互不串扰
              </span>
            </template>
            每个会话都会单独持久化 message / tool_call 记录，切回来可以继续聊。
            跨会话需要同步上下文时，手动在新会话里引用即可。
          </NTooltip>
        </div>
      </div>
      <NTooltip>
        <template #trigger>
          <span class="text-xs text-stone-400">项目 {{ projectId }}</span>
        </template>
        本会话消息带 sessionId/projectId 上下文，agent 会自动拉取项目 prompt 和启用的 tool/skill
      </NTooltip>
    </div>

    <!-- Message list -->
    <NSpin :show="loadingHistory" class="h-0 flex-auto">
      <NScrollbar ref="scrollRef" style="max-height: 100%; height: 100%">
        <div class="flex-col gap-16px px-24px py-16px">
          <div v-if="messages.length === 0" class="py-12">
            <NEmpty description="这是一段全新的 AI 记忆，从下面开始聊吧">
              <template #extra>
                <div class="mt-2 flex-col gap-1 text-xs text-stone-400">
                  <div>· 试试：「帮我找 10 个美妆 5w 粉博主」</div>
                  <div>· 或者：「把小红书『黄金街头』这个账号刷新一下」</div>
                  <div>· 也可以：「列出本项目名册里还没进 SHORTLISTED 的候选」</div>
                </div>
              </template>
            </NEmpty>
          </div>
          <div
            v-for="msg in messages"
            :key="msg.localId"
            class="flex-col gap-4px"
            :class="msg.role === 'user' ? 'items-end' : 'items-start'"
          >
            <div class="text-xs text-stone-400">
              {{ roleLabel(msg.role) }}
            </div>
            <div
              class="max-w-[80%] whitespace-pre-wrap break-words rounded-10px px-14px py-10px text-14px leading-[1.7]"
              :class="msg.role === 'user' ? 'bg-primary-1 dark:bg-#3a4a5e' : 'bg-#f3f4f6 dark:bg-#262a31'"
            >
              <span v-if="!msg.content && msg.status === 'pending'" class="text-stone-400">思考中…</span>
              <span v-else>{{ msg.content }}</span>
              <span v-if="msg.status === 'streaming'" class="ml-1 animate-pulse">▌</span>
            </div>

            <!-- tool calls 轨迹 -->
            <div v-if="msg.toolCalls && msg.toolCalls.length" class="flex-col gap-4px">
              <div
                v-for="call in msg.toolCalls"
                :key="call.toolUseId"
                class="flex items-center gap-2 rounded-6px bg-#fafafa px-10px py-6px text-xs text-stone-600 dark:bg-#2b2e34 dark:text-stone-300"
              >
                <NTag
                  size="tiny"
                  :type="call.status === 'error' ? 'error' : call.status === 'ok' ? 'success' : 'info'"
                  :bordered="false"
                >
                  {{ call.status === 'running' ? '⏳' : call.status === 'ok' ? '✓' : '✗' }}
                  {{ call.userFacingName || call.tool }}
                </NTag>
                <span class="flex-auto truncate">
                  {{ call.result?.summary || call.summary || '…' }}
                </span>
                <span v-if="call.result?.durationMs" class="text-stone-400">{{ call.result.durationMs }}ms</span>
              </div>
            </div>
          </div>
        </div>
      </NScrollbar>
    </NSpin>

    <!-- Input box -->
    <div class="border-t b-#e5e7eb20 bg-#ffffff px-16px py-12px dark:b-#1f2937 dark:bg-#1b1d21">
      <textarea
        ref="inputRef"
        v-model="inputText"
        placeholder="给小蜜蜂发送消息（Enter 发送 / Shift+Enter 换行）"
        class="min-h-36px w-full resize-none b-none bg-transparent color-#333 caret-[rgb(var(--primary-color))] outline-none dark:color-#f1f1f1"
        rows="2"
        @keydown="handleKeydown"
      />
      <div class="flex items-center justify-between pt-2">
        <span class="text-xs text-stone-400">
          agent 会按需调用 list_skills / use_skill / creator_search / xhs_refresh_creator 等工具
        </span>
        <div class="flex items-center gap-2">
          <NButton v-if="isSending" size="small" type="error" @click="handleStop">停止</NButton>
          <NButton
            size="small"
            type="primary"
            :disabled="!inputText.trim() || isSending || connectionStatus !== 'OPEN'"
            @click="handleSend"
          >
            发送
          </NButton>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped></style>
