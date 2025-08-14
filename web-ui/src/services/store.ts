import { reactive } from 'vue'

export const store = reactive({
  xiaoya: false,
  hostmode: false,
  docker: false,
  standalone: false,
  admin: false,
  installMode: '',
  baseUrl: '',
  token: '',
  role: 'USER',
  aListStatus: 0
})
