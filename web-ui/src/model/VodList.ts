import type {VodItem} from "@/model/VodItem";

export interface VodList {
  "page": number,
  "pagecount": number,
  "limit": number,
  "total": number,
  list: VodItem[]
}
