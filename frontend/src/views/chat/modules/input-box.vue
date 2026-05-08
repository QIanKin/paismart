<script setup lang="ts">
import ChatImagePreviewModal from '@/components/custom/chat-image-preview-modal.vue';
import { useChatImageAttachments } from '@/hooks/business/use-chat-image-attachments';
import { fileSize } from '@/utils/common';

const chatStore = useChatStore();
const { connectionStatus, input, isRateLimited, list, rateLimitRemainingSeconds, wsData } = storeToRefs(chatStore);
const previewVisible = ref(false);
const previewImages = ref<Array<{ url?: string | null; fileName?: string | null }>>([]);
const previewIndex = ref(0);

function buildWsErrorMessage(data: Record<string, any>) {
  if (data.code === 429) {
    const retryAfterSeconds = Number(data.retryAfterSeconds || 0);
    const baseMessage = data.message || '聊天请求过于频繁';

    if (retryAfterSeconds > 0) {
      return `${baseMessage}，请在 ${retryAfterSeconds} 秒后重试`;
    }

    return `${baseMessage}，请稍后再试`;
  }

  if (typeof data.error === 'string' && data.error.trim()) {
    return data.error.trim();
  }

  if (typeof data.message === 'string' && data.message.trim()) {
    return data.message.trim();
  }

  return '服务器繁忙，请稍后再试';
}

const latestMessage = computed(() => {
  return list.value[list.value.length - 1] ?? {};
});

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});
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

function openImagePreview(images: Array<{ url?: string | null; fileName?: string | null }>, index = 0) {
  const list = images
    .filter(item => Boolean(item.url))
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

function openAttachment(attachment: Api.AgentAsset.Attachment | undefined | null) {
  if (!attachment?.url) return;
  if (isImageAttachment(attachment)) return;
  window.open(attachment.url, '_blank', 'noopener,noreferrer');
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

const sendDisabled = computed(() => {
  if (isSending.value) {
    return false;
  }

  if (isRateLimited.value) {
    return true;
  }

  return (!input.value.message && readyAttachments.value.length === 0)
    || hasBlockingUploads.value
    || ['CLOSED', 'CONNECTING'].includes(connectionStatus.value);
});

const connectionText = computed(() => {
  if (connectionStatus.value === 'OPEN') {
    return '已连接';
  }

  if (connectionStatus.value === 'RECONNECTING') {
    return '重连中';
  }

  if (connectionStatus.value === 'CONNECTING') {
    return '连接中';
  }

  return '未连接';
});

const cooldownText = computed(() => {
  if (!isRateLimited.value) {
    return '';
  }

  return `${rateLimitRemainingSeconds.value} 秒后可重新发送`;
});

watch(wsData, val => {
  if (!val) return;

  let payload: Record<string, any>;

  try {
    payload = JSON.parse(val);
  } catch {
    return;
  }

  const assistant = list.value[list.value.length - 1];

  if (!assistant) return;

  if (payload.type === 'completion' && payload.status === 'finished' && assistant.status !== 'error')
    assistant.status = 'finished';

  if (payload.error || Number(payload.code) >= 400) {
    if (Number(payload.code) === 429) {
      chatStore.startRateLimitCountdown(Number(payload.retryAfterSeconds || 0));
    }

    const message = buildWsErrorMessage(payload);

    assistant.status = 'error';
    assistant.content = message;

    if (Number(payload.code) === 429) {
      window.$message?.warning(message);
    } else {
      window.$message?.error(message);
    }
  } else if (payload.chunk) {
    assistant.status = 'loading';
    assistant.content += payload.chunk;
  }
});

const handleSend = async () => {
  if (!input.value.message && pendingAttachments.value.length === 0) {
    return;
  }

  if (isRateLimited.value) {
      window.$message?.warning(`当前发送受限，${cooldownText.value}`);
      return;
  }

  if (hasBlockingUploads.value) {
    return;
  }

  //  判断是否正在发送, 如果发送中，则停止ai继续响应
  if (isSending.value) {
    const { error, data: tokenData } = await request<Api.Chat.Token>({
      url: 'chat/websocket-token',
      baseURL: 'proxy-api'
    });
    if (error) return;

    chatStore.wsSend(JSON.stringify({ type: 'stop', _internal_cmd_token: tokenData.cmdToken }));

    list.value[list.value.length - 1].status = 'finished';
    if (!latestMessage.value.content) list.value.pop();
    return;
  }

  list.value.push({
    content: input.value.message,
    role: 'user',
    attachments: readyAttachments.value.map(item => ({
      type: item.type,
      objectKey: item.objectKey,
      fileName: item.fileName,
      mimeType: item.mimeType,
      size: item.size,
      url: item.url
    }))
  });
  chatStore.wsSend(
    JSON.stringify({
      type: 'chat',
      content: input.value.message,
      attachments: readyAttachments.value.map(item => ({
        type: item.type,
        objectKey: item.objectKey,
        fileName: item.fileName,
        mimeType: item.mimeType,
        size: item.size
      }))
    })
  );
  list.value.push({
    content: '',
    role: 'assistant',
    status: 'pending'
  });
  input.value.message = '';
  resetAttachments();
};

const inputRef = ref();
// 手动插入换行符（确保所有浏览器兼容）
const insertNewline = () => {
  const textarea = inputRef.value;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  // 在光标位置插入换行符
  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  // 更新光标位置（在插入的换行符之后）
  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus(); // 确保保持焦点
  });
};

// ctrl + enter 换行
// enter 发送
const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};

</script>

<template>
  <div
    class="relative w-full b-1 b-#1c1c1c20 bg-#fff p-4 transition-colors card-wrapper dark:bg-#1c1c1c"
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
    <div v-if="pendingAttachments.length || uploadingImages" class="mb-3 flex flex-wrap gap-3">
      <div
        v-for="attachment in pendingAttachments"
        :key="attachment.localId"
        class="relative w-110px overflow-hidden rounded-8px b b-#e5e7eb bg-#fafafa p-1 dark:b-#2f3742 dark:bg-#20242b"
      >
        <button
          type="button"
          class="block w-full"
          @click="attachment.previewUrl
            ? openImagePreview([{ url: attachment.uploaded?.url || attachment.previewUrl, fileName: attachment.fileName }], 0)
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
      v-model.trim="input.message"
      placeholder="给 小蜜蜂 发送消息，支持附带图片或文档"
      class="min-h-10 w-full cursor-text resize-none b-none bg-transparent color-#333 caret-[rgb(var(--primary-color))] outline-none dark:color-#f1f1f1"
      @keydown="handShortcut"
      @paste="handlePaste"
    />
    <div class="flex items-center justify-between pt-2">
      <div class="flex items-center gap-3 text-18px color-gray-500">
        <NText class="text-14px">连接状态：</NText>
        <icon-eos-icons:loading
          v-if="connectionStatus === 'CONNECTING' || connectionStatus === 'RECONNECTING'"
          class="color-yellow"
        />
        <icon-fluent:plug-connected-checkmark-20-filled v-else-if="connectionStatus === 'OPEN'" class="color-green" />
        <icon-tabler:plug-connected-x v-else class="color-red" />
        <NText class="text-14px">
          {{
            dragActive
              ? '松手即可添加附件'
              : hasBlockingUploads
                ? '附件处理中'
                : connectionText
          }}
        </NText>
        <NText v-if="isRateLimited" type="warning" class="text-13px">{{ cooldownText }}</NText>
      </div>
      <div class="flex items-center gap-2">
        <NButton quaternary :disabled="uploadingImages || isSending" @click="openImagePicker">
          <template #icon>
            <icon-material-symbols:imagesmode-outline-rounded />
          </template>
        </NButton>
        <NButton :disabled="sendDisabled" strong circle type="primary" @click="handleSend">
          <template #icon>
            <icon-material-symbols:stop-rounded v-if="isSending" />
            <icon-guidance:send v-else />
          </template>
        </NButton>
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
