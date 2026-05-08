<script setup lang="ts">
import { NButton, NModal } from 'naive-ui';
import SvgIcon from './svg-icon.vue';

defineOptions({ name: 'ChatImagePreviewModal' });

interface PreviewImage {
  url?: string | null;
  fileName?: string | null;
}

interface Props {
  show: boolean;
  images: PreviewImage[];
  startIndex?: number;
}

const props = withDefaults(defineProps<Props>(), {
  startIndex: 0
});

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void;
}>();

const currentIndex = ref(0);

watch(
  () => [props.show, props.startIndex, props.images.length],
  () => {
    if (!props.images.length) {
      currentIndex.value = 0;
      return;
    }
    const nextIndex = Math.min(Math.max(props.startIndex, 0), props.images.length - 1);
    currentIndex.value = nextIndex;
  },
  { immediate: true }
);

const currentImage = computed(() => props.images[currentIndex.value] || null);
const canPrev = computed(() => currentIndex.value > 0);
const canNext = computed(() => currentIndex.value < props.images.length - 1);

function updateShow(value: boolean) {
  emit('update:show', value);
}

function prevImage() {
  if (canPrev.value) currentIndex.value -= 1;
}

function nextImage() {
  if (canNext.value) currentIndex.value += 1;
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :auto-focus="false"
    :mask-closable="true"
    class="chat-image-preview-modal"
    @update:show="updateShow"
  >
    <div class="flex items-center justify-between gap-3 pb-12px">
      <div class="min-w-0 flex-1">
        <div class="truncate text-14px font-semibold">
          {{ currentImage?.fileName || '图片预览' }}
        </div>
        <div v-if="images.length > 1" class="text-12px text-stone-400">
          {{ currentIndex + 1 }} / {{ images.length }}
        </div>
      </div>
      <div class="flex items-center gap-2">
        <NButton size="small" quaternary :disabled="!canPrev" @click="prevImage">
          <template #icon>
            <SvgIcon icon="solar:alt-arrow-left-linear" />
          </template>
        </NButton>
        <NButton size="small" quaternary :disabled="!canNext" @click="nextImage">
          <template #icon>
            <SvgIcon icon="solar:alt-arrow-right-linear" />
          </template>
        </NButton>
        <a
          v-if="currentImage?.url"
          :href="currentImage.url"
          target="_blank"
          rel="noopener"
          class="inline-flex h-32px items-center rounded-6px px-10px text-13px text-primary-500 hover:bg-primary-50"
        >
          原图
        </a>
      </div>
    </div>

    <div class="flex max-h-[78vh] min-h-[240px] items-center justify-center overflow-auto rounded-8px bg-black/85 p-10px">
      <img
        v-if="currentImage?.url"
        :src="currentImage.url"
        :alt="currentImage.fileName || 'image'"
        class="max-h-[74vh] max-w-full rounded-6px object-contain"
      />
      <div v-else class="text-sm text-white/70">图片加载失败</div>
    </div>
  </NModal>
</template>

<style scoped>
:deep(.chat-image-preview-modal.n-modal) {
  width: min(92vw, 1120px);
  background: #111827;
  color: #f8fafc;
}
</style>
