<script setup lang="tsx">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
import {
  NAlert,
  NButton,
  NDataTable,
  NDrawer,
  NDrawerContent,
  NDropdown,
  NFlex,
  NForm,
  NFormItem,
  NIcon,
  NInput,
  NInputNumber,
  NModal,
  NPopconfirm,
  NSelect,
  NSwitch,
  NTabPane,
  NTabs,
  NTag
} from 'naive-ui';
import type { DataTableColumns, DropdownOption } from 'naive-ui';
import { fetchCustomFieldDelete, fetchCustomFieldUpsert, fetchCustomFields } from '@/service/api';

interface Props {
  visible: boolean;
}

const props = defineProps<Props>();
const emit = defineEmits<{ 'update:visible': [boolean]; changed: [] }>();

const show = computed({
  get: () => props.visible,
  set: v => emit('update:visible', v)
});

const entityType = ref<Api.Creator.CustomFieldEntity>('account');
const rows = ref<Api.Creator.CustomField[]>([]);
const loading = ref(false);

async function load() {
  loading.value = true;
  try {
    const { data } = await fetchCustomFields(entityType.value);
    rows.value = data ?? [];
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.visible, entityType.value],
  () => {
    if (props.visible) load();
  }
);

const editVisible = ref(false);
const editForm = reactive<Api.Creator.CustomField>({
  entityType: 'account',
  fieldKey: '',
  label: '',
  dataType: 'string',
  required: false,
  builtIn: false,
  orderNo: 0,
  options: '',
  description: ''
});

const dataTypeOptions = [
  { label: '文本 string', value: 'string' },
  { label: '长文本 text', value: 'text' },
  { label: '数字 number', value: 'number' },
  { label: '金额 money', value: 'money' },
  { label: '布尔 boolean', value: 'boolean' },
  { label: '枚举 enum', value: 'enum' },
  { label: '多标签 tags', value: 'tags' },
  { label: '日期 date', value: 'date' },
  { label: '链接 url', value: 'url' }
];

/**
 * "业务预设字段"：把常用的 MCN 报价 / 备注 / 渠道字段一键加进来。
 *
 * 这些字段在企业客户场景里几乎是必备的（蒲公英报价、本月折扣报价、内部备注、是否签约博主等），
 * 让用户一键添加就不用每次手填 fieldKey/dataType。
 */
interface CustomFieldPreset {
  entityType: Api.Creator.CustomFieldEntity;
  fieldKey: string;
  label: string;
  dataType: string;
  description: string;
}

const PRESETS: CustomFieldPreset[] = [
  { entityType: 'account', fieldKey: 'pgy_quote_yuan', label: '蒲公英报价（元）', dataType: 'money', description: '从蒲公英平台读到的官方报价；用于客户合同基线' },
  { entityType: 'account', fieldKey: 'discounted_quote_this_month', label: '本月折扣报价（元）', dataType: 'money', description: '当月给客户的对外报价，导出 Excel 时优先展示此字段' },
  { entityType: 'account', fieldKey: 'cooperation_history', label: '合作历史备注', dataType: 'text', description: '过往合作的关键记录：交付质量 / 拖稿 / 数据等' },
  { entityType: 'account', fieldKey: 'private_contact', label: '私下联系方式', dataType: 'string', description: '微信 / 电话；仅对内可见' },
  { entityType: 'account', fieldKey: 'is_signed_kol', label: '是否签约博主', dataType: 'boolean', description: '本公司签约/独家博主，true 时优先排期' },
  { entityType: 'project_creator', fieldKey: 'final_quote_yuan', label: '本项目最终报价（元）', dataType: 'money', description: '该博主在本项目下的成交价' },
  { entityType: 'project_creator', fieldKey: 'delivery_status', label: '交付状态', dataType: 'enum', description: '未确认 / 已签 / 拍摄中 / 已交付 / 已上线' },
  { entityType: 'project_creator', fieldKey: 'delivery_due_date', label: '交付截止日期', dataType: 'date', description: '本项目下博主的交付 deadline' },
  { entityType: 'project_creator', fieldKey: 'contract_no', label: '合同编号', dataType: 'string', description: '本项目下与该博主的合同号' }
];

function applyPreset(preset: CustomFieldPreset) {
  entityType.value = preset.entityType;
  Object.assign(editForm, {
    id: undefined,
    entityType: preset.entityType,
    fieldKey: preset.fieldKey,
    label: preset.label,
    dataType: preset.dataType,
    required: false,
    builtIn: false,
    orderNo: rows.value.length,
    options: preset.dataType === 'enum' ? '未确认,已签,拍摄中,已交付,已上线' : '',
    description: preset.description
  });
  editVisible.value = true;
}

const presetMenu = computed<DropdownOption[]>(() => [
  {
    label: '账号 (account) 推荐字段',
    key: 'group-account',
    type: 'group',
    children: PRESETS.filter(p => p.entityType === 'account').map(p => ({
      label: `${p.label}（${p.dataType}）`,
      key: `preset-${p.entityType}-${p.fieldKey}`,
      props: { onClick: () => applyPreset(p) }
    }))
  },
  {
    label: '项目名册 (project_creator) 推荐字段',
    key: 'group-pc',
    type: 'group',
    children: PRESETS.filter(p => p.entityType === 'project_creator').map(p => ({
      label: `${p.label}（${p.dataType}）`,
      key: `preset-${p.entityType}-${p.fieldKey}`,
      props: { onClick: () => applyPreset(p) }
    }))
  }
]);

function openCreate() {
  Object.assign(editForm, {
    id: undefined,
    entityType: entityType.value,
    fieldKey: '',
    label: '',
    dataType: 'string',
    required: false,
    builtIn: false,
    orderNo: rows.value.length,
    options: '',
    description: ''
  });
  editVisible.value = true;
}

function openEdit(row: Api.Creator.CustomField) {
  Object.assign(editForm, { ...row });
  editVisible.value = true;
}

async function saveField() {
  if (!editForm.fieldKey || !editForm.label) {
    window.$message?.warning('fieldKey 与 label 必填');
    return;
  }
  const { error } = await fetchCustomFieldUpsert({ ...editForm });
  if (!error) {
    window.$message?.success('已保存');
    editVisible.value = false;
    await load();
    emit('changed');
  }
}

async function handleDelete(row: Api.Creator.CustomField) {
  if (!row.id) return;
  const { error } = await fetchCustomFieldDelete(row.id);
  if (!error) {
    window.$message?.success('已删除');
    await load();
    emit('changed');
  }
}

const columns = computed<DataTableColumns<Api.Creator.CustomField>>(() => [
  { key: 'fieldKey', title: 'fieldKey', width: 160 },
  { key: 'label', title: 'label', width: 160 },
  { key: 'dataType', title: '类型', width: 100 },
  {
    key: 'required',
    title: '必填',
    width: 80,
    render: row => (row.required ? '是' : '否')
  },
  {
    key: 'builtIn',
    title: '内置',
    width: 80,
    render: row => (row.builtIn ? h(NTag, { size: 'small', type: 'info' }, { default: () => '内置' }) : '-')
  },
  { key: 'description', title: '说明', minWidth: 200 },
  {
    key: 'operate',
    title: '操作',
    width: 160,
    render: row => (
      <div class="flex gap-2">
        <NButton size="small" quaternary onClick={() => openEdit(row)} disabled={row.builtIn}>
          编辑
        </NButton>
        <NPopconfirm onPositiveClick={() => handleDelete(row)}>
          {{
            default: () => '确认删除？',
            trigger: () => (
              <NButton size="small" quaternary type="error" disabled={row.builtIn}>
                删除
              </NButton>
            )
          }}
        </NPopconfirm>
      </div>
    )
  }
]);

onMounted(load);
</script>

<template>
  <NDrawer v-model:show="show" :width="960" placement="right">
    <NDrawerContent title="自定义字段管理" closable>
      <NFlex vertical :size="12">
        <NAlert type="info" :show-icon="false" :bordered="false" style="background: rgba(59,130,246,.06)">
          <div class="text-sm leading-relaxed">
            <strong>使用建议：</strong>
            自定义字段最常用于「<b>账号 (account)</b>」与「<b>项目名册 (project_creator)</b>」两类——
            前者放企业沉淀的博主元数据（如蒲公英报价、本月折扣价、内部备注），
            后者放每个项目里这位博主的合作信息（如最终报价、交付状态、合同号）。
            其它实体（博主人、内容、项目）通常不需要再额外加字段。
          </div>
        </NAlert>

        <NTabs v-model:value="entityType" type="line">
          <NTabPane name="account" tab="账号字段（推荐）" />
          <NTabPane name="project_creator" tab="名册条目字段（推荐）" />
          <NTabPane name="creator" tab="博主(人)字段" />
          <NTabPane name="post" tab="内容字段" />
          <NTabPane name="project" tab="项目字段" />
        </NTabs>

        <div class="flex justify-end gap-2">
          <NDropdown :options="presetMenu" trigger="hover" placement="bottom-end">
            <NButton size="small" ghost>添加业务预设</NButton>
          </NDropdown>
          <NButton size="small" type="primary" @click="openCreate">新增字段</NButton>
        </div>

        <NDataTable
          :columns="columns"
          :data="rows"
          :loading="loading"
          size="small"
          :row-key="(row: Api.Creator.CustomField) => row.id ?? row.fieldKey"
        />
      </NFlex>

      <NModal
        v-model:show="editVisible"
        :title="editForm.id ? '编辑字段' : '新增字段'"
        preset="card"
        :style="{ width: '560px' }"
      >
        <NForm :model="editForm" label-placement="top">
          <div class="grid grid-cols-2 gap-x-4">
            <NFormItem label="fieldKey" required>
              <NInput v-model:value="editForm.fieldKey" :disabled="Boolean(editForm.id)" placeholder="snake_case" />
            </NFormItem>
            <NFormItem label="label" required>
              <NInput v-model:value="editForm.label" />
            </NFormItem>
            <NFormItem label="entityType">
              <NSelect
                v-model:value="editForm.entityType"
                :options="[
                  { label: 'account 账号', value: 'account' },
                  { label: 'creator 博主', value: 'creator' },
                  { label: 'post 内容', value: 'post' },
                  { label: 'project 项目', value: 'project' },
                  { label: 'project_creator 名册条目', value: 'project_creator' }
                ]"
                :disabled="Boolean(editForm.id)"
              />
            </NFormItem>
            <NFormItem label="dataType">
              <NSelect v-model:value="editForm.dataType" :options="dataTypeOptions" />
            </NFormItem>
            <NFormItem v-if="editForm.dataType === 'enum'" label="enum 选项（逗号分隔）">
              <NInput v-model:value="editForm.options as any" placeholder="A, B, C" />
            </NFormItem>
            <NFormItem label="排序">
              <NInputNumber v-model:value="editForm.orderNo as any" :min="0" class="w-full" />
            </NFormItem>
            <NFormItem label="必填">
              <NSwitch v-model:value="editForm.required as any" />
            </NFormItem>
          </div>
          <NFormItem label="说明">
            <NInput
              v-model:value="editForm.description as any"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 4 }"
            />
          </NFormItem>
        </NForm>
        <template #footer>
          <div class="flex justify-end gap-2">
            <NButton @click="editVisible = false">取消</NButton>
            <NButton type="primary" @click="saveField">保存</NButton>
          </div>
        </template>
      </NModal>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped></style>
