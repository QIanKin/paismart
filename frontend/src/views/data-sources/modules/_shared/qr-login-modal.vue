<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { NButton, NModal, NProgress, NSpace, NTag } from 'naive-ui';
import { useAuthStore } from '@/store/modules/auth';
import { fetchXhsLoginCancel, fetchXhsLoginStart, fetchXhsLoginStatus } from '@/service/api';

/**
 * 小红书扫码登录弹窗（共享组件）。
 *
 * 原位置：views/xhs-cookies/modules/qr-login-modal.vue
 * 2026-04-21 搬到 data-sources/modules/_shared/ 后可被任意数据源面板复用；
 * 老 xhs-cookies 页已下线，本文件是唯一一份实现。
 *
 * 交互流程：
 *   1. 用户点"扫码登录" → 父组件 v-model:show = true
 *   2. onShow 触发 start()：POST /admin/xhs-cookies/qr-login 拿 sessionId
 *   3. 建立 WebSocket /proxy-ws/ws/xhs-login/{token}?session=xxx，订阅事件流
 *   4. 依次渲染 QR 图、状态；success 后提示刷新；error/expired/cancel 给重试入口
 *   5. 关闭弹窗 / 组件销毁时：若会话还在跑，发 POST /cancel + 关闭 WS
 *
 * 成功时 emit('success')，由父组件刷新列表。
 */
defineOptions({ name: 'QrLoginModal' });

const props = defineProps<{
  show: boolean;
}>();
const emit = defineEmits<{
  (e: 'update:show', v: boolean): void;
  (e: 'success'): void;
}>();

const authStore = useAuthStore();

const PLATFORM_LABELS: Record<Api.Xhs.Platform, string> = {
  xhs_pc: '小红书 PC',
  xhs_creator: '创作者中心',
  xhs_pgy: '蒲公英',
  xhs_qianfan: '千帆',
  xhs_spotlight: '聚光',
  xhs_competitor: '竞品'
};

const STATUS_LABELS: Record<Api.Xhs.LoginStatus, { text: string; type: 'default' | 'info' | 'success' | 'warning' | 'error' }> = {
  PENDING: { text: '正在启动浏览器…', type: 'default' },
  QR_READY: { text: '请用小红书 App 扫描二维码', type: 'info' },
  SCANNED: { text: '已扫码，请在手机端确认登录', type: 'info' },
  CONFIRMED: { text: '登录确认，正在采集各平台 Cookie…', type: 'info' },
  SUCCESS: { text: 'Cookie 已采集并入库', type: 'success' },
  FAILED: { text: '登录失败', type: 'error' },
  EXPIRED: { text: '登录超时', type: 'warning' },
  CANCELLED: { text: '已取消', type: 'warning' }
};

/** 后端默认采的四个平台——和 XhsLoginProperties.defaultPlatforms 保持一致。 */
const PLATFORMS: Api.Xhs.Platform[] = ['xhs_pc', 'xhs_creator', 'xhs_pgy', 'xhs_qianfan'];

const sessionId = ref<string | null>(null);
const status = ref<Api.Xhs.LoginStatus>('PENDING');
const qrDataUrl = ref<string | null>(null);
const errorMessage = ref<string | null>(null);
const capturedPlatforms = ref<string[]>([]);
const missingPlatforms = ref<string[]>([]);
const remainSecs = ref(0);
const totalSecs = ref(180);
const submitting = ref(false);

let ws: WebSocket | null = null;
let countdownTimer: ReturnType<typeof setInterval> | null = null;

const isTerminal = computed(
  () => status.value === 'SUCCESS' || status.value === 'FAILED' || status.value === 'EXPIRED' || status.value === 'CANCELLED'
);

const progressPct = computed(() => {
  if (totalSecs.value <= 0) return 0;
  return Math.min(100, Math.round(((totalSecs.value - remainSecs.value) / totalSecs.value) * 100));
});

function computeRemainSecs(expiresAt: string): number {
  const t = Date.parse(expiresAt);
  if (Number.isNaN(t)) return totalSecs.value;
  return Math.max(0, Math.ceil((t - Date.now()) / 1000));
}

function stopCountdown() {
  if (countdownTimer) {
    clearInterval(countdownTimer);
    countdownTimer = null;
  }
}

function startCountdown(expiresAt: string) {
  stopCountdown();
  remainSecs.value = computeRemainSecs(expiresAt);
  countdownTimer = setInterval(() => {
    if (isTerminal.value) {
      stopCountdown();
      return;
    }
    remainSecs.value = Math.max(0, remainSecs.value - 1);
    if (remainSecs.value === 0) stopCountdown();
  }, 1000);
}

function resetState() {
  stopCountdown();
  sessionId.value = null;
  status.value = 'PENDING';
  qrDataUrl.value = null;
  errorMessage.value = null;
  capturedPlatforms.value = [];
  missingPlatforms.value = [];
  remainSecs.value = 0;
}

function closeWs() {
  if (ws) {
    try {
      ws.close(1000, 'modal closed');
    } catch {}
    ws = null;
  }
}

/**
 * 建立 WS 订阅。后端路径：/ws/xhs-login/{token}?session={sessionId}
 * 前端走 nginx /proxy-ws 反向代理。
 */
function openWs(sId: string, token: string) {
  closeWs();
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const url = `${proto}//${window.location.host}/proxy-ws/ws/xhs-login/${encodeURIComponent(token)}?session=${encodeURIComponent(sId)}`;
  ws = new WebSocket(url);
  ws.addEventListener('message', ev => {
    try {
      const frame: Api.Xhs.LoginWsFrame = JSON.parse(ev.data as string);
      handleFrame(frame);
    } catch (e) {
      // 非 JSON 帧忽略
    }
  });
  ws.addEventListener('close', () => {
    // WS 关闭不等同于 session 失败；由后端最终状态决定
  });
  ws.addEventListener('error', () => {
    errorMessage.value = '实时连接异常，可尝试取消后重试';
  });
}

function handleFrame(frame: Api.Xhs.LoginWsFrame) {
  switch (frame.type) {
    case 'snapshot': {
      const p = frame.payload || {};
      if (p.status) status.value = p.status as Api.Xhs.LoginStatus;
      if (p.qrDataUrl) qrDataUrl.value = p.qrDataUrl as string;
      if (p.errorMessage) errorMessage.value = p.errorMessage as string;
      if (p.capturedPlatforms) capturedPlatforms.value = String(p.capturedPlatforms).split(',').filter(Boolean);
      if (p.missingPlatforms) missingPlatforms.value = String(p.missingPlatforms).split(',').filter(Boolean);
      break;
    }
    case 'qr_ready':
      qrDataUrl.value = (frame.payload?.dataUrl as string) || null;
      status.value = 'QR_READY';
      break;
    case 'status': {
      const st = frame.payload?.status as Api.Xhs.LoginStatus;
      if (st && STATUS_LABELS[st]) status.value = st;
      break;
    }
    case 'success': {
      status.value = 'SUCCESS';
      capturedPlatforms.value = (frame.payload?.captured as string[]) || [];
      missingPlatforms.value = (frame.payload?.missing as string[]) || [];
      emit('success');
      break;
    }
    case 'error': {
      status.value = 'FAILED';
      const type = frame.payload?.errorType as string | undefined;
      const msg = frame.payload?.message as string | undefined;
      if (type === 'expired') status.value = 'EXPIRED';
      errorMessage.value = [type, msg].filter(Boolean).join(': ') || '未知错误';
      break;
    }
    case 'closed':
      if (!isTerminal.value) {
        status.value = 'FAILED';
        errorMessage.value = `子进程退出 (exit=${frame.payload?.exitCode ?? -1})`;
      }
      break;
    default:
      break;
  }
}

async function start() {
  resetState();
  submitting.value = true;
  const token = (authStore.token || '').trim();
  if (!token) {
    window.$message?.error('未登录，无法发起扫码');
    submitting.value = false;
    return;
  }
  const { data, error } = await fetchXhsLoginStart(PLATFORMS);
  submitting.value = false;
  if (error || !data) return;

  sessionId.value = data.sessionId;
  status.value = data.status;
  totalSecs.value = Math.max(30, Math.round((Date.parse(data.expiresAt) - Date.now()) / 1000));
  startCountdown(data.expiresAt);
  openWs(data.sessionId, token);
}

async function handleCancel() {
  if (sessionId.value && !isTerminal.value) {
    await fetchXhsLoginCancel(sessionId.value);
  }
  closeWs();
  emit('update:show', false);
}

async function handleRetry() {
  await start();
}

/** 断线兜底：若 WS 异常断开、但 modal 还开着，轮询一次状态同步。 */
async function pollOnce() {
  if (!sessionId.value) return;
  const { data } = await fetchXhsLoginStatus(sessionId.value);
  if (!data) return;
  status.value = data.status;
  if (data.errorMessage) errorMessage.value = data.errorMessage;
  if (data.capturedPlatforms) capturedPlatforms.value = data.capturedPlatforms.split(',').filter(Boolean);
  if (data.missingPlatforms) missingPlatforms.value = data.missingPlatforms.split(',').filter(Boolean);
}

watch(
  () => props.show,
  async (v: boolean) => {
    if (v) {
      await start();
    } else {
      if (sessionId.value && !isTerminal.value) {
        fetchXhsLoginCancel(sessionId.value);
      }
      closeWs();
      stopCountdown();
    }
  }
);

onBeforeUnmount(() => {
  if (sessionId.value && !isTerminal.value) {
    fetchXhsLoginCancel(sessionId.value);
  }
  closeWs();
  stopCountdown();
});

defineExpose({ pollOnce });
</script>

<template>
  <NModal
    :show="props.show"
    preset="card"
    title="扫码登录采集 Cookie"
    class="w-540px!"
    :closable="true"
    :mask-closable="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="flex flex-col items-center gap-16px py-8px">
      <div class="text-12px leading-relaxed op-70">
        后端会在容器里打开一个无头 Chromium，截取小红书登录页二维码给你。<br />
        用小红书 App "我 → 扫一扫" 扫描二维码并在手机端确认即可，后端会把
        <NTag v-for="p in PLATFORMS" :key="p" size="tiny" :bordered="false" class="mx-1">{{ PLATFORM_LABELS[p] }}</NTag>
        四个平台的 Cookie 自动入池。
      </div>

      <div
        class="relative h-240px w-240px flex items-center justify-center rd-8px bg-stone-50 dark:bg-stone-800"
      >
        <img
          v-if="qrDataUrl"
          :src="qrDataUrl"
          alt="登录二维码"
          class="h-full w-full rd-8px object-contain p-8px"
        />
        <div v-else class="text-13px op-60">
          <span v-if="submitting || status === 'PENDING'">正在启动浏览器…</span>
          <span v-else-if="status === 'SUCCESS'">登录成功</span>
          <span v-else-if="status === 'FAILED' || status === 'EXPIRED' || status === 'CANCELLED'">
            {{ errorMessage || STATUS_LABELS[status].text }}
          </span>
          <span v-else>等待二维码…</span>
        </div>

        <div
          v-if="isTerminal"
          class="absolute inset-0 flex items-center justify-center rd-8px bg-black/50 text-14px text-white"
        >
          {{ STATUS_LABELS[status].text }}
        </div>
      </div>

      <div class="w-full flex-col gap-6px">
        <div class="flex items-center justify-between">
          <NTag :type="STATUS_LABELS[status].type" :bordered="false">
            {{ STATUS_LABELS[status].text }}
          </NTag>
          <span v-if="!isTerminal && remainSecs > 0" class="text-12px op-60">剩余 {{ remainSecs }}s</span>
        </div>
        <NProgress
          v-if="!isTerminal && totalSecs > 0"
          :percentage="progressPct"
          :show-indicator="false"
          :height="4"
          status="info"
        />
      </div>

      <div v-if="status === 'SUCCESS'" class="w-full flex flex-wrap items-center gap-2 text-12px">
        <span class="op-70">已采集：</span>
        <NTag v-for="p in capturedPlatforms" :key="p" type="success" size="small" :bordered="false">
          {{ PLATFORM_LABELS[p as Api.Xhs.Platform] || p }}
        </NTag>
        <template v-if="missingPlatforms.length">
          <span class="op-70">|</span>
          <span class="op-70">未采集：</span>
          <NTag v-for="p in missingPlatforms" :key="p" type="warning" size="small" :bordered="false">
            {{ PLATFORM_LABELS[p as Api.Xhs.Platform] || p }}
          </NTag>
          <span class="w-full text-11px op-60">
            这些平台通常是独立体系（比如千帆），一次扫码无法通过 SSO 拿到。可以单独手动粘贴补录。
          </span>
        </template>
      </div>

      <div
        v-if="errorMessage && (status === 'FAILED' || status === 'EXPIRED')"
        class="w-full rd-6px bg-red-50 p-8px text-12px text-red-700 dark:bg-red-900/20 dark:text-red-300"
      >
        {{ errorMessage }}
      </div>
    </div>

    <template #footer>
      <NSpace justify="end" :size="12">
        <NButton v-if="!isTerminal" @click="handleCancel">取消</NButton>
        <NButton v-else @click="emit('update:show', false)">关闭</NButton>
        <NButton v-if="status === 'FAILED' || status === 'EXPIRED' || status === 'CANCELLED'" type="primary" @click="handleRetry">
          重新扫码
        </NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped></style>
