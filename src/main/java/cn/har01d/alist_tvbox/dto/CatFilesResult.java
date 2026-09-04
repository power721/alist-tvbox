package cn.har01d.alist_tvbox.dto;

import java.util.List;

/**
 * 猫源文件管理列表(自定义爬虫与依赖文件)。
 */
public record CatFilesResult(List<CatFileEntry> files) {
}
