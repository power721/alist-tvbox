package cn.har01d.alist_tvbox.dto;

import lombok.Data;

/**
 * 批量重载失效资源的进度(内存态,单实例口径)。
 */
@Data
public class StorageReloadProgress {
    private volatile boolean running;
    private volatile boolean cancelled;
    private volatile int total;
    private volatile int processed;
    private volatile int success;
    private volatile int failed;
    private volatile long interval;
    private volatile long startedTime;
    private volatile long finishedTime;
    private volatile String error;
}
