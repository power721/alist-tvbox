package cn.har01d.alist_tvbox.model;

/**
 * 磁力提交三态结果(追剧磁力兜底用)。
 * <p>
 * COMPLETED:同步等待内完成,taskName 可用,产物路径 = 挂载根/alist-tvbox-offline/{taskName};
 * SUBMITTED:网盘侧任务已创建但未完成(submitAndWait 超时)——产物未落,等巡检下轮扫描收割,
 * 期间不得重复提交(网盘侧会重复建任务烧配额);FAILED:提交被网盘拒绝,可换候选重试。
 */
public record MagnetSubmitResult(String status, String taskName, String message) {
    public static final String COMPLETED = "COMPLETED";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String FAILED = "FAILED";

    public static MagnetSubmitResult completed(String taskName) {
        return new MagnetSubmitResult(COMPLETED, taskName, null);
    }

    public static MagnetSubmitResult submitted(String message) {
        return new MagnetSubmitResult(SUBMITTED, null, message);
    }

    public static MagnetSubmitResult failed(String message) {
        return new MagnetSubmitResult(FAILED, null, message);
    }
}
