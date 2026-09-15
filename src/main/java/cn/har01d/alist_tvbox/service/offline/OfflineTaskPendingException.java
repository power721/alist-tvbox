package cn.har01d.alist_tvbox.service.offline;

import cn.har01d.alist_tvbox.exception.BadRequestException;

/**
 * 离线任务已建但等待超时(网盘侧仍在下载):消息保持「未在N秒内完成」形态(提交侧按超时
 * 分类落 PENDING 行),额外携带网盘侧任务标识(如 123 的数字任务 id)——落行时存入
 * info_hash 列,清理调度的活体检查/任务删除直接按它对账,不必退回预测名匹配。
 */
public class OfflineTaskPendingException extends BadRequestException {
    private final String taskId;

    public OfflineTaskPendingException(String message, String taskId) {
        super(message);
        this.taskId = taskId;
    }

    /** 网盘侧任务标识(123 任务 id;115/迅雷无此值,btih 由提交侧从链接提取)。 */
    public String getTaskId() {
        return taskId;
    }
}
