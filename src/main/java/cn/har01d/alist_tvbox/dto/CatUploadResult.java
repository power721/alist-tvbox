package cn.har01d.alist_tvbox.dto;

import java.util.List;

/**
 * 猫源自定义爬虫上传结果。
 */
public record CatUploadResult(List<CatUploadEntry> entries) {
}
