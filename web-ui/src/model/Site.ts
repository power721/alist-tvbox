export interface Site {
  id: number
  name: string
  url: string
  password: string
  searchable: boolean
  indexFile: string
  disabled: boolean
  order: number
}
