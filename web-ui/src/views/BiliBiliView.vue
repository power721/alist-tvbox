<template>
  <div class="sites">
    <h1>分类列表</h1>
    <el-row justify="end">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" :disabled="!changed" @click="handleSave">保存</el-button>
      <el-button type="primary" @click="handleAdd">添加</el-button>
    </el-row>
    <div class="space"></div>

    <el-table :data="list"
              :row-class-name="tableRowClassName"
              row-key="id"
              style="width: 100%">
      <el-table-column class-name="allowDrag" label="移动" width="100">
        <template #default="scope">
          <el-icon class="pointer">
            <Pointer/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="order" label="顺序" sortable width="100"/>
      <el-table-column prop="id" label="ID" sortable width="100"/>
      <el-table-column prop="name" label="名称" width="200"/>
      <el-table-column prop="value" label="值" sortable/>
      <el-table-column prop="type" label="类型" sortable width="180">
        <template #default="scope">
          <span v-if="scope.row.type==1">一级分类</span>
          <span v-if="scope.row.type==2">二级分类</span>
          <span v-if="scope.row.type==3">频道</span>
          <span v-if="scope.row.type==4">搜索</span>
        </template>
      </el-table-column>
      <el-table-column prop="parentId" label="父类ID" sortable width="180"/>
      <el-table-column prop="show" label="显示？" sortable width="140">
        <template #default="scope">
          <el-icon v-if="scope.row.show">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="reserved" label="保留的？" sortable width="140">
        <template #default="scope">
          <el-icon v-if="scope.row.reserved">
            <Check/>
          </el-icon>
          <el-icon v-else>
            <Close/>
          </el-icon>
        </template>
      </el-table-column>
      <el-table-column fixed="right" label="操作" width="200">
        <template #default="scope">
          <div v-if="!scope.row.reserved">
            <el-button link type="primary" size="small" @click="handleEdit(scope.row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="handleDelete(scope.row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="dialogTitle">
      <el-form :model="form">
        <el-form-item label="名称" label-width="140">
          <el-input v-model="form.name" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="值" label-width="140">
          <el-input v-model="form.value" autocomplete="off"/>
        </el-form-item>
        <el-form-item label="显示？" label-width="140">
          <el-switch v-model="form.show"/>
        </el-form-item>
        <el-form-item label="保留的？" label-width="140">
          <el-switch v-model="form.reserved"/>
        </el-form-item>
        <el-form-item label="类型" label-width="140">
          <el-radio-group v-model="form.type" class="ml-4">
            <el-radio :label="1" size="large">一级分类</el-radio>
            <el-radio :label="2" size="large">二级分类</el-radio>
            <el-radio :label="3" size="large">频道</el-radio>
            <el-radio :label="4" size="large">搜索</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="顺序" label-width="140">
          <el-input-number v-model="form.order" :min="1"/>
        </el-form-item>
        <el-form-item label="父类ID" label-width="140">
          <el-input-number v-model="form.parentId" :min="0"/>
        </el-form-item>
      </el-form>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="handleCancel">取消</el-button>
        <el-button type="primary" @click="handleConfirm">{{ updateAction ? '更新' : '添加' }}</el-button>
      </span>
      </template>
    </el-dialog>

    <el-dialog v-model="dialogVisible" title="删除分类" width="30%">
      <p>是否删除站点 - {{ form.name }}</p>
      <p>{{ form.value }}</p>
      <template #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" @click="deleteSite">删除</el-button>
      </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import Sortable from "sortablejs"
import {Check, Close, Pointer} from '@element-plus/icons-vue'
import axios from "axios"
import {ElMessage} from "element-plus";

const columns = [
  {title: "ID", key: "id", dataKey: "id"},
  {title: "姓名", key: "name", dataKey: "name"},
  {title: "值", key: "value", dataKey: "value"},
  {title: "类型", key: "type", dataKey: "type"},
  {title: "显示", key: "show", dataKey: "show"},
  {title: "保留的", key: "reserved", dataKey: "reserved"},
  {title: "顺序", key: "order", dataKey: "order"},
  {title: "父类ID", key: "parentId", dataKey: "parentId"},
]

interface Nav {
  id: number
  name: string
  value: string
  show: boolean
  reserved: boolean
  changed: boolean
  expanded: boolean
  order: number
  type: number
  parentId: number
  children: Nav[]
}

const tableRowClassName = ({row}: {
  row: Nav
  rowIndex: number
}) => {
  if (row.changed) {
    return 'warning-row'
  }
  return ''
}
const updateAction = ref(false)
const dialogTitle = ref('')
const list = ref<Nav[]>([])
const formVisible = ref(false)
const dialogVisible = ref(false)
const changed = ref(false)
const form = ref<Nav>({
  id: 0,
  name: '',
  value: '',
  type: 1,
  show: true,
  reserved: true,
  changed: false,
  expanded: false,
  order: 1,
  parentId: 0,
  children: []
})

const rowDrop = () => {
  const tbody = document.querySelector(".el-table__body-wrapper tbody") as HTMLElement;
  Sortable.create(tbody, {
    handle: ".allowDrag",
    draggable: ".el-table__row",
    onEnd: (event: any) => {
      console.log(event.oldIndex, event.newIndex)
      const item = list.value[event.oldIndex]
      if (!item) {
        ElMessage.error('不能移除到父类外')
        return
      }

      item.changed = true
      if (item.type === 2) {
        const parentIndex = list.value.findIndex(e => e.id === item.parentId)
        const parent = list.value[parentIndex]
        const items = parent.children
        if (event.newIndex < parentIndex || event.newIndex > parentIndex + items.length) {
          ElMessage.error('不能移除到父类外')
          return
        }
        const currRow = items.splice(event.oldIndex, 1)[0];
        items.splice(event.newIndex, 0, currRow);

        let order = 1
        items.forEach(e => {
          e.order = order++
        })
      } else {
        list.value[event.newIndex].changed = true
        const currRow = list.value.splice(event.oldIndex, 1)[0];
        list.value.splice(event.newIndex, 0, currRow);

        let order = 1
        list.value.forEach(e => {
          e.order = order++
        })
      }

      changed.value = true
    },
  });
}

const handleAdd = () => {
  dialogTitle.value = '添加分类'
  updateAction.value = false
  form.value = {
    id: 0,
    name: '',
    value: '',
    type: 1,
    show: true,
    reserved: true,
    changed: false,
    expanded: false,
    order: list.value.length + 1,
    parentId: 0,
    children: []
  }
  formVisible.value = true
}

const handleEdit = (data: any) => {
  dialogTitle.value = '更新分类 - ' + data.name
  updateAction.value = true
  form.value = Object.assign({}, data)
  formVisible.value = true
}

const handleDelete = (data: any) => {
  form.value = data
  dialogVisible.value = true
}

const deleteSite = () => {
  dialogVisible.value = false
  axios.delete('/nav/' + form.value.id).then(() => {
    load()
  })
}

const handleCancel = () => {
  formVisible.value = false
}

const handleConfirm = () => {
  const url = updateAction.value ? '/nav/' + form.value.id : '/nav'
  axios.post(url, form.value).then(() => {
    formVisible.value = false
    load()
  })
}

const handleSave = () => {
  axios.put('/nav', {list: list.value.map(e => ({id: e.id, order: e.order}))}).then(() => {
    load()
  })
}

const load = () => {
  axios.get('/nav').then(({data}) => {
    list.value = data
    list.value.sort((a, b) => a.order - b.order)
    changed.value = false
  })
}

onMounted(() => {
  rowDrop()
  load()
})
</script>

<style scoped>
::v-deep .el-table .cell {
  text-align: center;
}

::v-deep .sortable-chosen > td {
  background-color: #eff2f6 !important;
  color: #409eff;
}

::v-deep .el-table--enable-row-hover .el-table__body tr:hover > td {
  background-color: #fff;
}

.pointer {
  cursor: pointer;
}
</style>
