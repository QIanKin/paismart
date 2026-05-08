import { fetchAgentAttachmentUpload } from '@/service/api';

interface UseChatImageAttachmentsOptions {
  maxImages?: number;
  disabled?: () => boolean;
}

export interface PendingChatAttachment {
  localId: string;
  fileName: string;
  size: number;
  previewUrl?: string;
  sourceFile: File;
  status: 'uploading' | 'ready' | 'error';
  progress: number;
  errorMessage?: string;
  uploaded?: Api.AgentAsset.Attachment;
}

const BACKEND_MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const FALLBACK_MAX_IMAGE_DIMENSION = 4096;
const SUPPORTED_DOCUMENT_EXTENSIONS = new Set(['pdf', 'doc', 'docx', 'xls', 'xlsx', 'csv', 'txt', 'md']);

export function useChatImageAttachments(options: UseChatImageAttachmentsOptions = {}) {
  const maxImages = options.maxImages ?? 4;

  const imageInputRef = ref<HTMLInputElement | null>(null);
  const pendingAttachments = ref<PendingChatAttachment[]>([]);
  const uploadingImages = ref(false);
  const dragActive = ref(false);
  const readyAttachments = computed(() => {
    return pendingAttachments.value
      .filter(item => item.status === 'ready' && item.uploaded)
      .map(item => item.uploaded!) as Api.AgentAsset.Attachment[];
  });
  const hasBlockingUploads = computed(() => {
    return pendingAttachments.value.some(item => item.status !== 'ready');
  });

  let dragDepth = 0;

  function isDisabled() {
    return Boolean(options.disabled?.());
  }

  function openImagePicker() {
    if (isDisabled() || uploadingImages.value) return;
    imageInputRef.value?.click();
  }

  async function handleImagePicked(event: Event) {
    const input = event.target as HTMLInputElement | null;
    const files = Array.from(input?.files ?? []);
    if (input) input.value = '';
    if (!files.length) return;
    await uploadFiles(files);
  }

  async function handlePaste(event: ClipboardEvent) {
    if (isDisabled() || uploadingImages.value) return;
    const files = Array.from(event.clipboardData?.items ?? [])
      .filter(item => item.kind === 'file' && item.type.startsWith('image/'))
      .map(item => item.getAsFile())
      .filter(Boolean) as File[];
    if (!files.length) return;
    await uploadFiles(files);
  }

  function handleDragEnter(event: DragEvent) {
    if (isDisabled() || !hasFilePayload(event)) return;
    event.preventDefault();
    dragDepth += 1;
    dragActive.value = true;
  }

  function handleDragOver(event: DragEvent) {
    if (isDisabled() || !hasFilePayload(event)) return;
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'copy';
    }
    dragActive.value = true;
  }

  function handleDragLeave(event: DragEvent) {
    if (!hasFilePayload(event)) return;
    dragDepth = Math.max(0, dragDepth - 1);
    if (dragDepth === 0) {
      dragActive.value = false;
    }
  }

  async function handleDrop(event: DragEvent) {
    dragDepth = 0;
    dragActive.value = false;
    if (isDisabled()) return;
    event.preventDefault();
    const files = Array.from(event.dataTransfer?.files ?? []);
    if (!files.length) return;
    await uploadFiles(files);
  }

  function removePendingAttachment(localId: string) {
    const target = pendingAttachments.value.find(item => item.localId === localId);
    if (target?.previewUrl) {
      URL.revokeObjectURL(target.previewUrl);
    }
    pendingAttachments.value = pendingAttachments.value.filter(item => item.localId !== localId);
  }

  async function retryPendingAttachment(localId: string) {
    const target = pendingAttachments.value.find(item => item.localId === localId);
    if (!target || target.status !== 'error' || uploadingImages.value || isDisabled()) return;
    await uploadOne(target);
  }

  function resetAttachments() {
    for (const item of pendingAttachments.value) {
      if (item.previewUrl) URL.revokeObjectURL(item.previewUrl);
    }
    pendingAttachments.value = [];
    dragDepth = 0;
    dragActive.value = false;
  }

  async function uploadFiles(files: File[]) {
    if (isDisabled()) return;

    const attachmentFiles = files.filter(file => isSupportedAttachment(file));
    if (!attachmentFiles.length) {
      window.$message?.warning('仅支持图片、PDF、Word、Excel、CSV、TXT、Markdown');
      return;
    }

    const remainingSlots = Math.max(0, maxImages - pendingAttachments.value.length);
    if (remainingSlots <= 0) {
      window.$message?.warning(`一次最多发送 ${maxImages} 个附件`);
      return;
    }

    const toUpload = attachmentFiles.slice(0, remainingSlots);
    if (toUpload.length < attachmentFiles.length) {
      window.$message?.warning(`超出部分已忽略，一次最多发送 ${maxImages} 个附件`);
    }

    for (const file of toUpload) {
      const uploadFile = await prepareUploadFile(file);
      const item: PendingChatAttachment = {
        localId: `img-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        fileName: uploadFile.name || file.name,
        size: uploadFile.size,
        previewUrl: file.type.startsWith('image/') ? URL.createObjectURL(file) : undefined,
        sourceFile: uploadFile,
        status: 'uploading',
        progress: 0
      };
      pendingAttachments.value.push(item);
      // 必须拿数组里的响应式对象继续改状态。
      // 否则 uploadOne 改的是 push 前的原始对象，模板可能“看起来已就绪”，
      // 但 readyAttachments 这个 computed 不会正确失效，最终发出去的 attachments 会是空数组。
      const reactiveItem = pendingAttachments.value[pendingAttachments.value.length - 1];
      if (!reactiveItem) continue;
      await uploadOne(reactiveItem);
    }
  }

  async function uploadOne(item: PendingChatAttachment) {
    item.status = 'uploading';
    item.errorMessage = undefined;
    item.progress = Math.max(item.progress, 1);
    uploadingImages.value = true;
    try {
      const { data, error } = await fetchAgentAttachmentUpload(item.sourceFile, event => {
        const progress = typeof event.progress === 'number'
          ? Math.round(event.progress * 100)
          : event.total
            ? Math.round((event.loaded / event.total) * 100)
            : item.progress;
        item.progress = Math.min(99, Math.max(progress, item.progress || 1));
      });
      if (error || !data) {
        item.status = 'error';
        item.errorMessage = error?.response?.data?.message || error?.message || '上传失败';
        item.progress = 0;
        return;
      }
      item.status = 'ready';
      item.uploaded = data;
      item.progress = 100;
    } catch (err) {
      item.status = 'error';
      item.errorMessage = err instanceof Error ? err.message : '上传失败';
      item.progress = 0;
    } finally {
      uploadingImages.value = pendingAttachments.value.some(entry => entry.status === 'uploading');
    }
  }

  function hasFilePayload(event: DragEvent) {
    const types = Array.from(event.dataTransfer?.types ?? []);
    return types.includes('Files') || types.includes('application/x-moz-file');
  }

  async function prepareUploadFile(file: File): Promise<File> {
    if (!file.type.startsWith('image/')) {
      return file;
    }
    return maybeCompressImage(file);
  }

  async function maybeCompressImage(file: File): Promise<File> {
    // 识图/OCR 对有损重编码非常敏感。
    // 10MB 以内默认直传原图，避免把文字、小图标、UI 细节在前端先压坏。
    if (file.type === 'image/gif' || !file.type.startsWith('image/') || file.size <= BACKEND_MAX_IMAGE_BYTES) {
      return file;
    }

    try {
      const image = await loadImage(file);
      const { width, height } = fitSize(image.naturalWidth, image.naturalHeight, FALLBACK_MAX_IMAGE_DIMENSION);
      const scaled = width !== image.naturalWidth || height !== image.naturalHeight;

      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const context = canvas.getContext('2d');
      if (!context) return file;

      context.drawImage(image, 0, 0, width, height);
      const quality = file.size > 16 * 1024 * 1024 ? 0.9 : 0.94;
      const blob = await canvasToBlob(canvas, 'image/webp', quality);
      if (!blob) return file;

      if (blob.size >= file.size * 0.98) {
        return file;
      }

      const nextName = replaceExt(file.name, 'webp');
      return new File([blob], nextName, {
        type: 'image/webp',
        lastModified: Date.now()
      });
    } catch {
      return file;
    }
  }

  return {
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
  };
}

function isSupportedAttachment(file: File) {
  if (!file) return false;
  if (file.type.startsWith('image/')) return true;
  const extension = file.name.includes('.') ? file.name.split('.').pop()?.toLowerCase() || '' : '';
  return SUPPORTED_DOCUMENT_EXTENSIONS.has(extension);
}

function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(url);
      resolve(image);
    };
    image.onerror = error => {
      URL.revokeObjectURL(url);
      reject(error);
    };
    image.src = url;
  });
}

function fitSize(width: number, height: number, maxDimension: number) {
  if (width <= maxDimension && height <= maxDimension) {
    return { width, height };
  }
  const scale = Math.min(maxDimension / width, maxDimension / height);
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale))
  };
}

function canvasToBlob(canvas: HTMLCanvasElement, type: string, quality: number): Promise<Blob | null> {
  return new Promise(resolve => {
    canvas.toBlob(blob => resolve(blob), type, quality);
  });
}

function replaceExt(fileName: string, nextExt: string) {
  const base = fileName.replace(/\.[^.]+$/, '');
  return `${base}.${nextExt}`;
}
