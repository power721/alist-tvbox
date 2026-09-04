package cn.har01d.alist_tvbox.dto;

/**
 * 猫源覆盖层(/data/cat)内单个文件(custom/ 爬虫与 lib/ 依赖)。
 */
public record CatFileEntry(String path, long size, long lastModified) {
}
