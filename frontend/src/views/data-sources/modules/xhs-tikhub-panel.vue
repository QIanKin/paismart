<script setup lang="ts">
/**
 * TikHub 第三方小红书数据通道面板（运维侧只读视图）。
 *
 * <p>这条通道用于替代 cookie 直连下载/解析，零封号风险：
 *   1. 视频解析与无水印直链：xhs_third_party_note_detail / xhs_third_party_media_download
 *   2. 完整爆款视频拆解：xhs_video_analyze（解析+下载+ASR+LLM 报告）
 *
 * <p>页面只展示当前是否启用 + 关键 env 变量，真实开关在后端 .env / application.yml。
 */
import { NAlert, NCard, NCode, NDescriptions, NDescriptionsItem, NTag } from 'naive-ui';

defineOptions({ name: 'XhsTikhubPanel' });

const tikhubEnv = [
  ['XHS_THIRD_PARTY_ENABLED', '总开关；true 时第三方 provider 工具才允许调用'],
  ['XHS_THIRD_PARTY_PROVIDER', '默认 tikhub；其他值会回落到旧 generic HTTP 客户端'],
  ['XHS_TIKHUB_ENABLED', 'TikHub 子开关；同时配 api key 才生效'],
  ['XHS_TIKHUB_API_KEY', 'TikHub Bearer token（生产请走 secrets 管理，不要进版本库）'],
  ['XHS_TIKHUB_DEFAULT_QUALITY', '视频默认下载清晰度：best/1080p/720p/480p'],
  ['XHS_TIKHUB_MAX_VIDEO_BYTES', '单视频字节数上限（默认 200MB）']
];

const asrEnv = [
  ['SMARTPAI_ASR_ENABLED', 'ASR 总开关；关掉后视频拆解会跳过转写步骤'],
  ['SMARTPAI_ASR_PROVIDER', '默认 whisper-compatible（backend 容器内 faster-whisper）；可切 dashscope-paraformer-v2'],
  ['SMARTPAI_WHISPER_MODE', 'local-faster-whisper=默认同容器脚本；asr-webservice / openai-compat=HTTP 外连'],
  ['WHISPER_MODEL', '传给 faster-whisper 的体量：tiny/base/small/medium'],
  ['SMARTPAI_WHISPER_SCRIPT', '本地模式 python 脚本路径，默认 /app/scripts/local_whisper_transcribe.py'],
  ['DASHSCOPE_ASR_API_KEY', '仅 provider=dashscope-... 时使用；留空 fallback 到 EMBEDDING_API_KEY']
];
</script>

<template>
  <div class="h-full overflow-auto pb-16px">
    <div class="grid gap-12px lg:grid-cols-2">
      <NCard :bordered="false" size="small" class="card-wrapper">
        <template #header>
          <div class="flex items-center gap-2">
            <span>TikHub · 视频解析与下载</span>
            <NTag size="small" type="success" :bordered="false">零封号风险</NTag>
          </div>
        </template>
        <div class="text-13px leading-relaxed text-stone-600 dark:text-stone-300">
          <p>
            通过 TikHub 获取小红书无水印视频直链 + 笔记元数据 + 互动指标。整个流程<b>不消耗自家 cookie 池</b>，
            是 cookie + yt-dlp 老链路的安全替代，业务侧只需要把分享链接（含 xsec_token）丢给 Agent 即可。
          </p>
          <NAlert type="info" :bordered="false" class="mt-12px">
            Agent 工具：
            <code>xhs_third_party_note_detail</code> / <code>xhs_third_party_media_download</code>，
            完整拆解走 <code>xhs_video_analyze</code>。
          </NAlert>
        </div>
      </NCard>

      <NCard :bordered="false" size="small" class="card-wrapper">
        <template #header>
          <div class="flex items-center gap-2">
            <span>ASR · 音频转写双通道</span>
            <NTag size="small" type="info" :bordered="false">默认本地 Whisper</NTag>
          </div>
        </template>
        <NDescriptions :column="1" size="small" label-placement="left" bordered>
          <NDescriptionsItem label="本地 faster-whisper">
            已打进 backend 镜像：Java 调 python3 子进程转写，不依赖 Docker Hub 上的独立 whisper 镜像；首次识别会从 HuggingFace 拉模型（可设 HF_ENDPOINT）
          </NDescriptionsItem>
          <NDescriptionsItem label="DashScope">
            paraformer-v2 异步任务，准确率高、速度快；要求 MinIO 公网可达
          </NDescriptionsItem>
          <NDescriptionsItem label="切换">
            改 .env：SMARTPAI_WHISPER_MODE=local-faster-whisper | asr-webservice | openai-compat；ASR 总路由 SMARTPAI_ASR_PROVIDER=whisper-compatible | dashscope-paraformer-v2
          </NDescriptionsItem>
          <NDescriptionsItem label="降级">
            ASR 失败时仍保留视频/音频原件并把 errorType 透传给 Agent，便于切换渠道
          </NDescriptionsItem>
        </NDescriptions>
      </NCard>

      <NCard :bordered="false" size="small" class="card-wrapper lg:col-span-2">
        <template #header>TikHub 必备环境变量</template>
        <div class="grid gap-8px md:grid-cols-2">
          <div v-for="item in tikhubEnv" :key="item[0]" class="rounded-6px bg-stone-100 p-10px dark:bg-stone-800">
            <div class="font-mono text-12px text-primary">{{ item[0] }}</div>
            <div class="mt-4px text-12px text-stone-500">{{ item[1] }}</div>
          </div>
        </div>
      </NCard>

      <NCard :bordered="false" size="small" class="card-wrapper lg:col-span-2">
        <template #header>ASR 必备环境变量</template>
        <div class="grid gap-8px md:grid-cols-2">
          <div v-for="item in asrEnv" :key="item[0]" class="rounded-6px bg-stone-100 p-10px dark:bg-stone-800">
            <div class="font-mono text-12px text-primary">{{ item[0] }}</div>
            <div class="mt-4px text-12px text-stone-500">{{ item[1] }}</div>
          </div>
        </div>
        <NCode
          class="mt-12px"
          language="bash"
          code="# 验证 TikHub 凭证（容器内执行）\nbash acceptance/probe-tikhub.sh"
        />
      </NCard>
    </div>
  </div>
</template>
