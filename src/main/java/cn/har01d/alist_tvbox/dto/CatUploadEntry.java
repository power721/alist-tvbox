package cn.har01d.alist_tvbox.dto;

/**
 * 猫源上传中单个文件的落盘结果(path 为相对 cat 根路径,custom/ 前缀=自定义爬虫)。
 */
public record CatUploadEntry(String path, boolean overwritten) {
}
