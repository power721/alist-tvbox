package cn.har01d.alist_tvbox.dto;

import java.util.Set;

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
    /** 风控/限流跳过数:触发的条目及其后同网盘(同驱动)的条目均不再请求 */
    private volatile int throttled;
    /** 已触发风控被整盘跳过的驱动名(如 BaiduShare2) */
    private volatile Set<String> throttledDrivers;
    private volatile long interval;
    private volatile long startedTime;
    private volatile long finishedTime;
    private volatile String error;
}
