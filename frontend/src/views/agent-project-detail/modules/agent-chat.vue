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
import { NButton, NEmpty, NProgress, NScrollbar, NSpin, NTag, NTooltip } from 'naive-ui';
import ChatImagePreviewModal from '@/components/custom/chat-image-preview-modal.vue';
import SvgIcon from '@/components/custom/svg-icon.vue';
import { useChatImageAttachments } from '@/hooks/business/use-chat-image-attachments';
import { fetchSessionLive, fetchSessionMessages } from '@/service/api';
import { fileSize } from '@/utils/common';
import { VueMarkdownIt } from '@/vendor/vue-markdown-shiki';
import { type ErrorCodeHelp, lookupErrorHelp } from './agent-error-codes';
import { type ToolCallView, mapServerMessages, useAgentSessionStore } from './session-bus';

interface Props {
  projectId: number;
  sessionId: number;
}
const props = defineProps<Props>();

const chatStore = useChatStore();
const sessionBus = useAgentSessionStore();
// 全局 WS 事件订阅（Bug-fix：流式中断不续接）：把 chunk/tool_*/completion 等的处理迁到 store 层，
// 组件卸载也不会丢事件；这里只需保证至少注册过一次。
sessionBus.ensureWsWatcher();
const { connectionStatus } = storeToRefs(chatStore);
const wsSend = chatStore.wsSend;

// 当前会话 messages（响应式）
const messages = computed(() => sessionBus.messagesBySession[props.sessionId] || []);
// Phase 4a 活体状态：ask_user 悬挂、todos 清单、当前 step
const askUser = computed(() => sessionBus.askUserBySession[props.sessionId] || null);
const todos = computed(() => sessionBus.todosBySession[props.sessionId] || []);
const currentStep = computed(() => sessionBus.currentStepBySession[props.sessionId] ?? null);
const loadingHistory = ref(false);

const inputText = ref('');
const inputRef = ref<HTMLTextAreaElement | null>(null);
const previewVisible = ref(false);
const previewImages = ref<Array<{ url?: string | null; fileName?: string | null }>>([]);
const previewIndex = ref(0);

const isSending = computed(() => sessionBus.pendingSessionIds.has(props.sessionId));
const {
  imageInputRef,
  pendingAttachments,
  readyAttachments,
  uploadingImages,
  hasBlockingUploads,
  dragActive,
  openImagePicker,
  handleImagePicked,
  handlePaste,
  handleDragEnter,
  handleDragOver,
  handleDragLeave,
  handleDrop,
  removePendingAttachment,
  retryPendingAttachment,
  resetAttachments
} = useChatImageAttachments({
  maxImages: 4,
  disabled: () => isSending.value
});
const canSend = computed(() => {
  return (Boolean(inputText.value.trim()) || readyAttachments.value.length > 0)
    && !isSending.value
    && !hasBlockingUploads.value
    && connectionStatus.value === 'OPEN';
});

// 防止"快速切会话"时旧请求的响应覆盖当前会话：
// 每个 sessionId 维护一个"最新请求 id"，只有最新一次的响应可以写回消息。
const historyReqSeq = new Map<number, number>();

async function loadHistory(sid: number) {
  const mySeq = (historyReqSeq.get(sid) || 0) + 1;
  historyReqSeq.set(sid, mySeq);
  loadingHistory.value = true;
  try {
    const { data } = await fetchSessionMessages(sid);
    if (historyReqSeq.get(sid) !== mySeq) return;
    sessionBus.setMessages(sid, mapServerMessages(data ?? []));

    // Bug-fix「流式中断不续接」：拉完已落库的历史，再请求活体快照。
    // 后端 runTurn 期间事件累积进 Redis；如果用户当时切走再回来，这里就能拿到 partial assistant +
    // 进行中工具卡片，拼到列表尾部。后续 chunk 通过 store 层的 WS 订阅继续 append。
    try {
      const live = await fetchSessionLive(sid);
      if (historyReqSeq.get(sid) !== mySeq) return;
      if (live?.data) sessionBus.attachLiveSnapshot(sid, live.data);
    } catch {
      /* 拿不到 live 不影响主流程 */
    }
  } finally {
    if (props.sessionId === sid && historyReqSeq.get(sid) === mySeq) loadingHistory.value = false;
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

// 注：解析 WS 事件已经迁到 sessionBus.ensureWsWatcher()，组件不再单独 watch wsData，
// 避免组件卸载（用户切走）后丢事件，导致重新进会话视频卡片消失 / 流式中断。

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
  if (!text && pendingAttachments.value.length === 0) return;
  if (isSending.value || uploadingImages.value) return; // 上一条还没收完先别发

  if (connectionStatus.value !== 'OPEN') {
    window.$message?.warning('连接中，请稍等…');
    return;
  }

  const attachments = readyAttachments.value.map(item => ({
    type: item.type,
    objectKey: item.objectKey,
    fileName: item.fileName,
    mimeType: item.mimeType,
    size: item.size,
    url: item.url
  }));
  sessionBus.pushUser(props.sessionId, text, attachments);
  sessionBus.pushAssistantPending(props.sessionId);
  sessionBus.markPending(props.sessionId);

  wsSend(
    JSON.stringify({
      type: 'chat',
      content: text,
      attachments: attachments.map(item => ({
        type: item.type,
        objectKey: item.objectKey,
        fileName: item.fileName,
        mimeType: item.mimeType,
        size: item.size
      })),
      sessionId: props.sessionId,
      projectId: props.projectId
    })
  );
  inputText.value = '';
  resetAttachments();
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

function openImagePreview(
  images: Array<Api.Session.Attachment | { url?: string | null; fileName?: string | null; mimeType?: string | null; type?: string | null }> | undefined,
  index = 0
) {
  const list = (images || [])
    .filter(item => Boolean(item.url) && isImageAttachment(item))
    .map(item => ({
      url: item.url,
      fileName: item.fileName
    }));
  if (!list.length) return;
  previewImages.value = list;
  previewIndex.value = Math.min(Math.max(index, 0), list.length - 1);
  previewVisible.value = true;
}

function isImageAttachment(attachment: { type?: string | null; mimeType?: string | null }) {
  return String(attachment.mimeType || '').startsWith('image/') || String(attachment.type || '') === 'image';
}

function openAttachment(attachment: Api.Session.Attachment | undefined | null) {
  if (!attachment?.url) return;
  if (isImageAttachment(attachment)) return;
  window.open(attachment.url, '_blank', 'noopener,noreferrer');
}

function openAttachmentImagePreview(attachments: Api.Session.Attachment[] | undefined, index: number) {
  const list = attachments || [];
  const imageIndex = list.slice(0, index + 1).filter(isImageAttachment).length - 1;
  openImagePreview(list, imageIndex);
}

function attachmentLabel(attachment: { fileName?: string | null; mimeType?: string | null }) {
  const fileName = String(attachment.fileName || '').toLowerCase();
  const mimeType = String(attachment.mimeType || '').toLowerCase();
  if (mimeType.includes('pdf') || fileName.endsWith('.pdf')) return 'PDF';
  if (mimeType.includes('word') || fileName.endsWith('.doc') || fileName.endsWith('.docx')) return 'WORD';
  if (mimeType.includes('sheet') || mimeType.includes('excel') || fileName.endsWith('.xls') || fileName.endsWith('.xlsx')) return 'EXCEL';
  if (mimeType.includes('csv') || fileName.endsWith('.csv')) return 'CSV';
  if (mimeType.includes('markdown') || fileName.endsWith('.md')) return 'MD';
  if (mimeType.includes('text') || fileName.endsWith('.txt')) return 'TXT';
  return 'FILE';
}

function roleLabel(role: string) {
  if (role === 'user') return '你';
  if (role === 'assistant') return '小蜜蜂';
  return role;
}

// Phase 4a：ask_user 选项点击 -> 作为下一轮 user message 发出
// 自定义 text 走 inputText 正常 handleSend；options 走这里
function handleAskUserOption(option: string) {
  if (!option || isSending.value) return;
  if (connectionStatus.value !== 'OPEN') {
    window.$message?.warning('连接中，请稍等…');
    return;
  }
  sessionBus.markAskUserAnswered(props.sessionId);
  sessionBus.pushUser(props.sessionId, option);
  sessionBus.pushAssistantPending(props.sessionId);
  sessionBus.markPending(props.sessionId);
  wsSend(
    JSON.stringify({
      type: 'chat',
      content: option,
      sessionId: props.sessionId,
      projectId: props.projectId
    })
  );
  scrollToBottom();
  // 清掉气泡：下一轮 agent 如果再发 ask_user 会重新 set
  setTimeout(() => sessionBus.clearAskUser(props.sessionId), 150);
}

function todoStatusIcon(status: string) {
  if (status === 'completed') return '✓';
  if (status === 'in_progress') return '◐';
  if (status === 'cancelled') return '–';
  return '○';
}

function todoStatusClass(status: string) {
  if (status === 'completed') return 'text-emerald-500 line-through opacity-70';
  if (status === 'in_progress') return 'text-primary-500 font-medium';
  if (status === 'cancelled') return 'text-stone-400 line-through';
  return 'text-stone-500';
}

// Phase 4b：工具失败时，读取 tool_result.meta.errorCode，查表得到友好标签 + 帮助链接
function getErrorHelp(call: ToolCallView): ErrorCodeHelp | null {
  const code = call.result?.meta?.errorCode;
  return code ? lookupErrorHelp(String(code)) : null;
}

const router = useRouter();

function handleErrorAction(action: NonNullable<ErrorCodeHelp['action']>) {
  if (action.to) {
    router.push(action.to);
  } else if (action.href) {
    window.open(action.href, '_blank', 'noopener');
  }
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
    <NSpin :show="loadingHistory" class="h-0 flex-auto" content-class="h-full" content-style="height: 100%">
      <NScrollbar ref="scrollRef" class="h-full">
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
              class="max-w-[80%] break-words rounded-10px px-14px py-10px text-14px leading-[1.7]"
              :class="[
                msg.role === 'user' ? 'bg-primary-1 dark:bg-#3a4a5e' : 'bg-#f3f4f6 dark:bg-#262a31'
              ]"
            >
              <span v-if="!msg.content && !msg.attachments?.length && msg.status === 'pending'" class="text-stone-400">思考中…</span>
              <template v-else-if="msg.role === 'assistant'">
                <VueMarkdownIt v-if="msg.content" :content="msg.content" />
              </template>
              <span v-else-if="msg.content" class="whitespace-pre-wrap">{{ msg.content }}</span>
              <div v-if="msg.attachments?.length" class="mt-3 flex flex-wrap gap-2">
                <button
                  v-for="(attachment, index) in msg.attachments"
                  :key="`${msg.localId}-img-${index}`"
                  type="button"
                  class="block"
                  @click="isImageAttachment(attachment) ? openAttachmentImagePreview(msg.attachments, index) : openAttachment(attachment)"
                >
                  <template v-if="isImageAttachment(attachment)">
                    <img
                      :src="attachment.url || undefined"
                      :alt="attachment.fileName || 'image'"
                      class="h-120px w-120px rounded-8px object-cover shadow-sm"
                    />
                  </template>
                  <template v-else>
                    <div class="flex h-120px w-160px flex-col items-start justify-between rounded-8px border border-#e5e7eb bg-white p-3 text-left shadow-sm dark:border-#2f3742 dark:bg-#20242b">
                      <div class="rounded-6px bg-#f3f4f6 px-2 py-1 text-11px font-600 text-stone-600 dark:bg-#2a3038 dark:text-stone-200">
                        {{ attachmentLabel(attachment) }}
                      </div>
                      <div class="line-clamp-2 text-13px text-stone-700 dark:text-stone-100">
                        {{ attachment.fileName || '未命名附件' }}
                      </div>
                      <div class="text-11px text-stone-400">
                        {{ fileSize(Number(attachment.size || 0)) }}
                      </div>
                    </div>
                  </template>
                </button>
              </div>
              <span v-if="msg.status === 'streaming'" class="ml-1 animate-pulse">▌</span>
            </div>

            <!-- tool calls 轨迹 -->
            <div v-if="msg.toolCalls && msg.toolCalls.length" class="flex-col gap-4px">
              <div v-for="call in msg.toolCalls" :key="call.toolUseId" class="flex-col gap-2px">
                <div
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
                <!-- Phase 4a: tool_progress 累积文本，仅在工具运行中显示 -->
                <div
                  v-if="call.status === 'running' && call.progressText"
                  class="pl-10px text-xs text-stone-400"
                >
                  <span class="mr-1 animate-pulse">›</span>{{ call.progressText }}
                </div>
                <!-- xhs_video_analyze 专属富卡片：视频预览 + transcript 入口 -->
                <div
                  v-if="call.tool === 'xhs_video_analyze' && call.status === 'ok' && (call.result?.meta as any)?.videoUrl"
                  class="rounded-8px b-1 b-#e5e7eb bg-white p-12px text-13px shadow-sm dark:b-#2f3742 dark:bg-#20242b"
                >
                  <div class="mb-8px flex items-center gap-2">
                    <SvgIcon icon="solar:videocamera-record-bold-duotone" class="text-16px text-primary-500" />
                    <span class="font-600">{{ (call.result?.meta as any)?.title || '小红书视频' }}</span>
                    <NTag size="tiny" type="success" :bordered="false">已入库</NTag>
                  </div>
                  <video
                    :src="(call.result?.meta as any)?.videoUrl"
                    controls
                    preload="metadata"
                    class="w-full max-w-480px rounded-6px bg-black"
                  />
                  <div class="mt-8px flex flex-wrap items-center gap-3 text-xs">
                    <a
                      v-if="(call.result?.meta as any)?.transcriptUrl"
                      :href="(call.result?.meta as any)?.transcriptUrl"
                      target="_blank"
                      class="text-primary-500 hover:underline"
                    >
                      📝 查看转写文本
                    </a>
                    <span class="text-stone-400">noteId: {{ (call.result?.meta as any)?.noteId }}</span>
                  </div>
                </div>
                <!-- Phase 4b: 工具失败时展示 errorCode 徽章 + 帮助跳转 -->
                <div
                  v-if="call.status === 'error' && getErrorHelp(call)"
                  class="flex items-center gap-2 pl-10px text-xs"
                >
                  <NTag size="tiny" type="error" :bordered="false">
                    {{ getErrorHelp(call)!.label }}
                  </NTag>
                  <a
                    v-if="getErrorHelp(call)!.action"
                    class="cursor-pointer text-primary-500 hover:underline"
                    @click="handleErrorAction(getErrorHelp(call)!.action!)"
                  >
                    {{ getErrorHelp(call)!.action!.text }} →
                  </a>
                </div>
              </div>
            </div>
          </div>

          <!-- Phase 4a: Agent 反问气泡（贴在消息流末尾，输入框上方；每个会话最多一个悬挂） -->
          <div
            v-if="askUser"
            class="self-start rounded-10px b-1 b-dashed b-primary-500/40 bg-primary-50 px-14px py-10px text-sm dark:bg-#223041"
          >
            <div class="flex items-center gap-2 text-xs text-primary-500">
              <SvgIcon icon="solar:question-circle-bold-duotone" class="text-14px" />
              <span>小蜜蜂请你确认</span>
              <span v-if="askUser.answered" class="ml-auto text-stone-400">已回复</span>
            </div>
            <div class="mt-1 text-[13px] leading-[1.6] text-stone-700 dark:text-stone-200">
              {{ askUser.question }}
            </div>
            <div v-if="askUser.options.length" class="mt-2 flex flex-wrap gap-2">
              <NButton
                v-for="opt in askUser.options"
                :key="opt"
                size="small"
                :type="askUser.answered ? 'default' : 'primary'"
                :ghost="!askUser.answered"
                :disabled="askUser.answered || isSending || connectionStatus !== 'OPEN'"
                @click="handleAskUserOption(opt)"
              >
                {{ opt }}
              </NButton>
            </div>
            <div v-else class="mt-1 text-xs text-stone-400">请在下方直接回复你的答案。</div>
          </div>
        </div>
      </NScrollbar>
    </NSpin>

    <!-- Phase 4a: TODO 面板（固定吸在输入框上方，整个会话共享） -->
    <div
      v-if="todos.length"
      class="border-t b-#e5e7eb20 bg-#ffffff/95 px-16px py-8px text-xs dark:b-#1f2937 dark:bg-#1b1d21/95"
    >
      <div class="mb-1 flex items-center gap-2 text-stone-500">
        <SvgIcon icon="solar:checklist-minimalistic-bold-duotone" class="text-14px text-primary-500" />
        <span>小蜜蜂的 TODO（{{ todos.filter(t => t.status === 'completed').length }} / {{ todos.length }}）</span>
        <span v-if="currentStep !== null" class="ml-auto text-stone-400">第 {{ currentStep }} 步</span>
      </div>
      <div class="flex-col gap-1">
        <div v-for="t in todos" :key="t.id" class="flex items-start gap-2" :class="todoStatusClass(t.status)">
          <span class="w-12px shrink-0 text-center">{{ todoStatusIcon(t.status) }}</span>
          <span class="flex-auto break-words">{{ t.content }}</span>
        </div>
      </div>
    </div>

    <!-- Input box -->
    <div
      class="border-t b-#e5e7eb20 bg-#ffffff px-16px py-12px transition-colors dark:b-#1f2937 dark:bg-#1b1d21"
      :class="dragActive ? 'bg-primary-50/70 dark:bg-#223041' : ''"
      @dragenter="handleDragEnter"
      @dragover="handleDragOver"
      @dragleave="handleDragLeave"
      @drop="handleDrop"
    >
      <input
        ref="imageInputRef"
        type="file"
        accept="image/png,image/jpeg,image/webp,image/gif,.pdf,.doc,.docx,.xls,.xlsx,.csv,.txt,.md"
        multiple
        class="hidden"
        @change="handleImagePicked"
      />
      <div v-if="pendingAttachments.length || uploadingImages" class="mb-10px flex flex-wrap gap-3">
          <div
            v-for="attachment in pendingAttachments"
            :key="attachment.localId"
            class="relative w-110px overflow-hidden rounded-8px b b-#e5e7eb bg-#fafafa p-1 dark:b-#2f3742 dark:bg-#20242b"
          >
            <button
              type="button"
              class="block w-full"
              @click="attachment.previewUrl
                ? openImagePreview([{ url: attachment.uploaded?.url || attachment.previewUrl, fileName: attachment.fileName, mimeType: attachment.uploaded?.mimeType || attachment.sourceFile.type, type: attachment.uploaded?.type }], 0)
                : openAttachment(attachment.uploaded)"
            >
              <template v-if="attachment.previewUrl">
                <img
                  :src="attachment.uploaded?.url || attachment.previewUrl"
                  :alt="attachment.fileName"
                  class="h-76px w-full rounded-6px object-cover"
                />
              </template>
              <template v-else>
                <div class="flex h-76px w-full items-center justify-center rounded-6px bg-#eef2f7 text-12px font-600 text-stone-500 dark:bg-#27303a dark:text-stone-200">
                  {{ attachmentLabel(attachment) }}
                </div>
              </template>
            </button>
            <button
              type="button"
              class="absolute right-4px top-4px h-20px w-20px rounded-full bg-black/55 text-12px text-white"
              @click.stop="removePendingAttachment(attachment.localId)"
            >
              ×
            </button>
            <div
              v-if="attachment.status === 'uploading'"
              class="absolute inset-x-6px bottom-28px rounded-6px bg-black/60 px-6px py-5px"
            >
              <div class="mb-4px text-[10px] text-white/90">上传中 {{ attachment.progress }}%</div>
              <NProgress
                type="line"
                :show-indicator="false"
                :percentage="attachment.progress"
                :height="4"
                status="success"
                processing
              />
            </div>
            <div
              v-else-if="attachment.status === 'error'"
              class="absolute inset-x-6px bottom-28px rounded-6px bg-red-950/78 px-6px py-5px text-[10px] text-white"
            >
              <div class="truncate">{{ attachment.errorMessage || '上传失败' }}</div>
              <div class="mt-4px flex items-center gap-2">
                <button type="button" class="text-primary-200 hover:underline" @click.stop="retryPendingAttachment(attachment.localId)">
                  重试
                </button>
                <button type="button" class="text-white/80 hover:underline" @click.stop="removePendingAttachment(attachment.localId)">
                  移除
                </button>
              </div>
            </div>
            <div
              v-else
              class="absolute left-4px top-4px rounded-full bg-emerald-600/85 px-6px py-2px text-[10px] text-white"
            >
              已就绪
            </div>
            <div class="truncate px-4px pt-1 text-11px text-stone-500">
              {{ attachment.fileName }}
            </div>
          <div class="px-4px text-11px text-stone-400">
            {{ fileSize(Number(attachment.size || 0)) }}
          </div>
        </div>
      <div
        v-if="uploadingImages"
        class="flex h-110px w-110px items-center justify-center rounded-8px border border-dashed b-#cbd5e1 text-xs text-stone-400"
      >
          附件上传中…
        </div>
      </div>
      <textarea
        ref="inputRef"
        v-model="inputText"
        placeholder="给小蜜蜂发送消息，支持附带图片或文档（Enter 发送 / Shift+Enter 换行）"
        class="min-h-36px w-full resize-none b-none bg-transparent color-#333 caret-[rgb(var(--primary-color))] outline-none dark:color-#f1f1f1"
        rows="2"
        @keydown="handleKeydown"
        @paste="handlePaste"
      />
      <div class="flex items-center justify-between pt-2">
        <span class="text-xs text-stone-400">
          {{
            dragActive
              ? '松手即可添加附件'
              : hasBlockingUploads
                ? '有附件仍在上传或失败，请先等待完成或重试'
                : 'agent 会按需调用 list_skills / use_skill / creator_search / xhs_refresh_creator 等工具'
          }}
        </span>
        <div class="flex items-center gap-2">
          <NButton size="small" quaternary :disabled="uploadingImages || isSending" @click="openImagePicker">
            <template #icon>
              <SvgIcon icon="solar:gallery-wide-bold-duotone" />
            </template>
            附件
          </NButton>
          <NButton v-if="isSending" size="small" type="error" @click="handleStop">停止</NButton>
          <NButton size="small" type="primary" :disabled="!canSend" @click="handleSend">
            发送
          </NButton>
        </div>
      </div>
    </div>
    <ChatImagePreviewModal
      v-model:show="previewVisible"
      :images="previewImages"
      :start-index="previewIndex"
    />
  </div>
</template>

<style scoped></style>
