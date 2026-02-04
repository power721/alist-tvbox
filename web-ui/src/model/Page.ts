import type { Task } from '@/model/Task'

export interface TaskPage {
  totalElements: number
  totalPages: number
  size: number
  number: number
  content: Task[]
}
