<script setup lang="ts">
import { computed, ref } from 'vue'
import { View } from '@element-plus/icons-vue'
import { inputStages, type PluginInputSummary } from './pluginInput'

const props = defineProps<{ input: PluginInputSummary; currentSha256: string }>()
const visible = ref(false)
const rows = computed(() => inputStages(props.input, props.currentSha256))
</script>

<template>
  <div class="plugin-input-evidence">
    <el-tag :type="input.artifactSha256 === currentSha256 ? 'info' : 'warning'">
      {{ input.evidence.inputFormat }} ·
      {{ input.artifactSha256 === currentSha256 ? '已保存输入身份' : '仅对应历史明文' }}
    </el-tag>
    <el-tooltip content="拆解插件包与明文身份">
      <el-button :icon="View" circle aria-label="拆解插件输入" @click="visible = true" />
    </el-tooltip>
    <el-drawer v-model="visible" title="插件输入身份" size="min(760px, 100vw)"
               append-to-body class="plugin-input-drawer">
      <el-table :data="rows" row-key="stage">
        <el-table-column prop="stage" label="环节" width="88" />
        <el-table-column prop="status" label="状态" min-width="128" />
        <el-table-column label="SHA-256 / 字节" min-width="110">
          <template #default="{ row }"><code>{{ row.sha256 || '不适用' }}</code>
            <small v-if="row.bytes !== null">{{ row.bytes.toLocaleString() }} 字节</small></template>
        </el-table-column>
      </el-table>
      <dl>
        <template v-if="input.evidence.atSource">
          <dt>AT 插件</dt><dd>{{ input.evidence.atSource.pluginId }} · {{ input.evidence.atSource.authority }}</dd>
          <dt>AT 观察 SHA-256</dt><dd><code>{{ input.evidence.atSource.observationSha256 }}</code></dd>
          <dt>生产修改</dt><dd>{{ input.evidence.atSource.productionModified ? '异常：已修改' : '未修改' }}</dd>
        </template>
        <dt>输入回执 SHA-256</dt><dd><code>{{ input.evidenceSha256 }}</code></dd>
        <dt>预处理器 SHA-256</dt><dd><code>{{ input.evidence.processorSha256 }}</code></dd>
        <dt v-if="input.evidence.runtimeSha256">运行时清单 SHA-256</dt>
        <dd v-if="input.evidence.runtimeSha256"><code>{{ input.evidence.runtimeSha256 }}</code></dd>
        <dt>证据范围</dt><dd>{{ input.evidence.authority }}</dd>
        <dt>导入时源码执行</dt><dd>{{ input.evidence.sourceExecuted ? '异常：已执行' : '未执行' }}</dd>
        <dt>业务与发布</dt><dd>未验证 · 未授权发布</dd>
      </dl>
    </el-drawer>
  </div>
</template>

<style scoped>
.plugin-input-evidence { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 8px 0 16px; }
code, dd { overflow-wrap: anywhere; word-break: break-all; }
small { display: block; color: #647080; margin-top: 4px; }
dt { font-size: 12px; margin-top: 18px; color: #647080; }
dd { margin: 4px 0 0; }
</style>
