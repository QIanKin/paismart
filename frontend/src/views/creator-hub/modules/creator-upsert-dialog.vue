<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { NButton, NDynamicTags, NForm, NFormItem, NInput, NInputNumber, NModal, NSelect, NSwitch } from 'naive-ui';

interface Props {
  visible: boolean;
  initial: Partial<Api.Creator.Account> | null;
  customFields: Api.Creator.CustomField[];
}

const props = defineProps<Props>();
const emit = defineEmits<{
  'update:visible': [boolean];
  submit: [Partial<Api.Creator.Account>];
}>();

const show = computed({
  get: () => props.visible,
  set: v => emit('update:visible', v)
});

const form = ref<Partial<Api.Creator.Account> & { _tagList?: string[]; _customFieldMap?: Record<string, any> }>({});

const platformOptions = [
  { label: '小红书 xhs', value: 'xhs' },
  { label: '抖音 douyin', value: 'douyin' },
  { label: '快手 kuaishou', value: 'kuaishou' },
  { label: 'B 站 bilibili', value: 'bilibili' },
  { label: '微博 weibo', value: 'weibo' },
  { label: 'YouTube', value: 'youtube' },
  { label: 'Instagram', value: 'instagram' },
  { label: 'TikTok', value: 'tiktok' }
];

function parseTagList(raw?: string | null): string[] {
  if (!raw) return [];
  try {
    const v = JSON.parse(raw);
    return Array.isArray(v) ? v.map(x => String(x)) : [];
  } catch {
    return String(raw)
      .split(/[,，]/)
      .map(x => x.trim())
      .filter(Boolean);
  }
}

function parseCustomFieldMap(raw?: string | null): Record<string, any> {
  if (!raw) return {};
  try {
    const v = JSON.parse(raw);
    return v && typeof v === 'object' ? v : {};
  } catch {
    return {};
  }
}

watch(
  () => props.visible,
  v => {
    if (!v) return;
    const src = props.initial ?? {};
    form.value = {
      ...src,
      _tagList: parseTagList(src.platformTags),
      _customFieldMap: parseCustomFieldMap(src.customFields)
    };
  },
  { immediate: true }
);

const isEditing = computed(() => Boolean(form.value.id));

function onSubmit() {
  const payload: Partial<Api.Creator.Account> = { ...form.value };
  // transform back
  if (form.value._tagList) {
    payload.platformTags = JSON.stringify(form.value._tagList);
  }
  if (form.value._customFieldMap) {
    const cleaned: Record<string, any> = {};
    for (const [k, v] of Object.entries(form.value._customFieldMap)) {
      if (v !== undefined && v !== null && v !== '') cleaned[k] = v;
    }
    payload.customFields = JSON.stringify(cleaned);
  }
  delete (payload as any)._tagList;
  delete (payload as any)._customFieldMap;
  emit('submit', payload);
}

function closeDialog() {
  emit('update:visible', false);
}
</script>

<template>
  <NModal
    v-model:show="show"
    :title="isEditing ? '编辑账号' : '新增账号'"
    preset="card"
    :style="{ width: '720px' }"
    :bordered="false"
    size="huge"
  >
    <NForm label-placement="top" :model="form">
      <div class="grid grid-cols-2 gap-x-4">
        <NFormItem label="平台" required>
          <NSelect
            v-model:value="form.platform"
            :options="platformOptions"
            placeholder="选择平台"
            :disabled="isEditing"
          />
        </NFormItem>
        <NFormItem label="平台 UID" required>
          <NInput v-model:value="form.platformUserId" placeholder="平台侧唯一 ID" :disabled="isEditing" />
        </NFormItem>
        <NFormItem label="Handle / @名">
          <NInput v-model:value="form.handle" placeholder="@xxx" />
        </NFormItem>
        <NFormItem label="昵称">
          <NInput v-model:value="form.displayName" placeholder="显示名称" />
        </NFormItem>
        <NFormItem label="主赛道">
          <NInput v-model:value="form.categoryMain" placeholder="美妆 / 美食 / ..." />
        </NFormItem>
        <NFormItem label="子赛道">
          <NInput v-model:value="form.categorySub" placeholder="细分赛道" />
        </NFormItem>
        <NFormItem label="主页 URL">
          <NInput v-model:value="form.homepageUrl" placeholder="https://..." />
        </NFormItem>
        <NFormItem label="头像 URL">
          <NInput v-model:value="form.avatarUrl" placeholder="https://..." />
        </NFormItem>
        <NFormItem label="粉丝数">
          <NInputNumber v-model:value="form.followers" :min="0" class="w-full" />
        </NFormItem>
        <NFormItem label="点赞累计">
          <NInputNumber v-model:value="form.likes" :min="0" class="w-full" />
        </NFormItem>
        <NFormItem label="平均点赞">
          <NInputNumber v-model:value="form.avgLikes" :min="0" class="w-full" />
        </NFormItem>
        <NFormItem label="平均评论">
          <NInputNumber v-model:value="form.avgComments" :min="0" class="w-full" />
        </NFormItem>
        <NFormItem label="互动率 (0-1)">
          <NInputNumber v-model:value="form.engagementRate" :min="0" :max="1" :step="0.01" class="w-full" />
        </NFormItem>
        <NFormItem label="爆款率 (0-1)">
          <NInputNumber v-model:value="form.hitRatio" :min="0" :max="1" :step="0.01" class="w-full" />
        </NFormItem>
        <NFormItem label="认证">
          <NSwitch v-model:value="form.verified as any" />
        </NFormItem>
        <NFormItem label="地区">
          <NInput v-model:value="form.region" placeholder="国家 / 城市" />
        </NFormItem>
      </div>

      <NFormItem label="简介">
        <NInput v-model:value="form.bio" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" />
      </NFormItem>

      <NFormItem label="平台标签">
        <NDynamicTags v-model:value="form._tagList" />
      </NFormItem>

      <template v-if="customFields.length">
        <div class="mb-2 mt-4 text-3.5 text-stone-700 font-bold dark:text-stone-200">自定义字段</div>
        <div class="grid grid-cols-2 gap-x-4">
          <NFormItem v-for="cf in customFields" :key="cf.fieldKey" :label="cf.label">
            <NSelect
              v-if="cf.dataType === 'enum'"
              v-model:value="(form._customFieldMap as any)[cf.fieldKey]"
              :options="
                (cf.options
                  ? cf.options
                      .split(',')
                      .map(o => o.trim())
                      .filter(Boolean)
                  : []
                ).map(o => ({
                  label: o,
                  value: o
                }))
              "
              clearable
            />
            <NInputNumber
              v-else-if="cf.dataType === 'number'"
              :value="(form._customFieldMap as any)[cf.fieldKey] ?? null"
              class="w-full"
              @update:value="(v: number | null) => ((form._customFieldMap as any)[cf.fieldKey] = v)"
            />
            <NSwitch
              v-else-if="cf.dataType === 'boolean'"
              :value="Boolean((form._customFieldMap as any)[cf.fieldKey])"
              @update:value="(v: boolean) => ((form._customFieldMap as any)[cf.fieldKey] = v)"
            />
            <NInput
              v-else-if="cf.dataType === 'text'"
              v-model:value="(form._customFieldMap as any)[cf.fieldKey]"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 4 }"
            />
            <NInput
              v-else
              v-model:value="(form._customFieldMap as any)[cf.fieldKey]"
              :placeholder="cf.description || ''"
            />
          </NFormItem>
        </div>
      </template>
    </NForm>

    <template #footer>
      <div class="flex justify-end gap-2">
        <NButton @click="closeDialog">取消</NButton>
        <NButton type="primary" @click="onSubmit">保存</NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped></style>
