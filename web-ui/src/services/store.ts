import { reactive } from 'vue'

export const store = reactive({
  xiaoya: false,
  hostmode: false,
  docker: false,
  standalone: false,
  installMode: '',
  baseUrl: '',
  aListStatus: 0
})
