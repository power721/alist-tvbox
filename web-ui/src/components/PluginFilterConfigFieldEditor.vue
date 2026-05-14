<template>
  <div class="filter-config-field">
    <div class="filter-config-field-label">
      <span>{{ field.label || field.key }}</span>
      <span v-if="field.required" class="required-mark">*</span>
      <span class="field-key">{{ displayPath }}</span>
    </div>
    <div v-if="field.description" class="filter-config-field-desc">{{ field.description }}</div>

    <el-input
      v-if="field.type === 'string'"
      :model-value="stringValue"
      :placeholder="field.placeholder || field.key"
      @update:model-value="setScalarValue($event)"
    />

    <el-input-number
      v-else-if="field.type === 'number'"
      :model-value="numberValue"
      controls-position="right"
      style="width: 220px"
      @update:model-value="setNumberValue"
    />

    <el-switch
      v-else-if="field.type === 'boolean'"
      :model-value="booleanValue"
      @update:model-value="setBooleanValue"
    />

    <div v-else-if="field.type === 'object' && field.children?.length" class="filter-config-nested">
      <PluginFilterConfigFieldEditor
        v-for="child in field.children"
        :key="`${field.key}.${child.key}`"
        :field="child"
        :model-value="modelValue"
        :container-path="childContainerPath"
        @update:model-value="emit('update:modelValue', $event)"
      />
    </div>

    <el-input
      v-else-if="field.type === 'object'"
      type="textarea"
      :rows="6"
      :model-value="objectValue"
      :placeholder="field.placeholder || '{ }'"
      @update:model-value="setObjectValue"
    />

    <el-input
      v-else
      :model-value="stringValue"
      :placeholder="field.placeholder || field.key"
      @update:model-value="setScalarValue($event)"
    />
  </div>
</template>

<script setup lang="ts">
import {computed} from 'vue'

defineOptions({name: 'PluginFilterConfigFieldEditor'})

interface PluginFilterConfigField {
  key: string
  label: string
  type: string
  required: boolean
  description: string
  defaultValue?: any
  placeholder: string
  aliases: string[]
  children: PluginFilterConfigField[]
}

const props = withDefaults(defineProps<{
  field: PluginFilterConfigField
  modelValue: Record<string, any>
  containerPath?: string[]
}>(), {
  containerPath: () => []
})

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, any>]
}>()

const displayPath = computed(() => [...props.containerPath, props.field.key].join('.'))
const childContainerPath = computed(() => [...props.containerPath, props.field.key])

const cloneValue = <T>(value: T): T => JSON.parse(JSON.stringify(value))

const getObjectAtPath = (root: Record<string, any>, path: string[]) => {
  let current: any = root
  for (const segment of path) {
    if (!current || typeof current !== 'object' || Array.isArray(current)) {
      return {}
    }
    current = current[segment]
  }
  return current && typeof current === 'object' && !Array.isArray(current) ? current : {}
}

const getContainerObject = () => getObjectAtPath(props.modelValue, props.containerPath)

const getValueByAliases = () => {
  const container = getContainerObject()
  const keys = [props.field.key, ...(props.field.aliases || [])].filter(Boolean)
  for (const key of keys) {
    const value = container[key]
    if (value !== undefined && value !== null) {
      return value
    }
  }
  return props.field.defaultValue
}

const patchRootValue = (nextFieldValue: any) => {
  const nextRoot = cloneValue(props.modelValue || {})
  let current: any = nextRoot
  for (const segment of props.containerPath) {
    const nested = current[segment]
    if (!nested || typeof nested !== 'object' || Array.isArray(nested)) {
      current[segment] = {}
    }
    current = current[segment]
  }

  if (nextFieldValue === '' || nextFieldValue === undefined || nextFieldValue === null) {
    delete current[props.field.key]
  } else {
    current[props.field.key] = nextFieldValue
  }
  emit('update:modelValue', nextRoot)
}

const stringValue = computed(() => {
  const value = getValueByAliases()
  return value == null ? '' : String(value)
})

const numberValue = computed(() => {
  const value = getValueByAliases()
  return typeof value === 'number' ? value : undefined
})

const booleanValue = computed(() => !!getValueByAliases())

const objectValue = computed(() => {
  const value = getValueByAliases()
  if (value && typeof value === 'object') {
    return JSON.stringify(value, null, 2)
  }
  return value == null ? '' : String(value)
})

const setScalarValue = (value: string) => {
  patchRootValue(value.trim() ? value : '')
}

const setNumberValue = (value?: number) => {
  patchRootValue(typeof value === 'number' ? value : '')
}

const setBooleanValue = (value: boolean) => {
  patchRootValue(value)
}

const setObjectValue = (value: string) => {
  const text = value.trim()
  if (!text) {
    patchRootValue('')
    return
  }
  try {
    const parsed = JSON.parse(text)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      patchRootValue(parsed)
      return
    }
  } catch {
    // Keep raw text so the user can continue editing invalid or non-object content.
  }
  patchRootValue(text)
}
</script>

<style scoped>
.filter-config-field {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 12px;
}

.filter-config-field-label {
  align-items: center;
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
  font-weight: 600;
}

.filter-config-field-desc {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-bottom: 10px;
}

.field-key {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.required-mark {
  color: var(--el-color-danger);
}

.filter-config-nested {
  background: var(--el-fill-color-lighter);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
}
</style>
