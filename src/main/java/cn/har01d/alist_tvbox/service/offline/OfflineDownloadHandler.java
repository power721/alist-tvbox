package cn.har01d.alist_tvbox.service.offline;

import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface OfflineDownloadHandler {
    DriverType getDriverType();

    String ensureOfflineFolder(DriverAccount account);

    TaskResult submitAndWait(DriverAccount account, String url, String folderId);

    /** 定向等待版(追剧磁力提交用):waitSeconds 秒内轮询完成,超时抛「未在N秒内完成」;
     *  手动离线下载走三参默认等待(115=10 秒/迅雷、光鸭=30 秒),行为不变。 */
    default TaskResult submitAndWait(DriverAccount account, String url, String folderId, int waitSeconds) {
        return submitAndWait(account, url, folderId);
    }

    QuotaResult getQuota(DriverAccount account);

    /**
     * 离线任务活体状态(每日清理调度用):按 info_hash(缺失时按产物名)对账网盘侧任务列表。
     * 不支持的网盘默认 ABSENT(清理侧视作任务已不存在,幂等收尾)。
     */
    default TaskStatus taskStatus(DriverAccount account, String infoHash, String taskName) {
        return TaskStatus.ABSENT;
    }

    /**
     * 删除网盘侧离线任务记录,deleteFiles=true 时连同已下载文件一起删(空间回收的关键)。
     * 任务不存在视为成功(幂等);不支持的网盘默认 no-op。info_hash 缺失时实现方可按产物名解析。
     */
    default void deleteTask(DriverAccount account, String infoHash, String taskName, boolean deleteFiles) {
    }

    /**
     * 任务管理能力声明(离线清理用):true = 实现了 taskStatus 活体检查 + deleteTask 任务删除
     * (115/迅雷,任务记录留存会挡重提或占槽位,须删);false = 无任务删除契约(光鸭)——
     * 清理侧 PENDING 只按滞留天数兜底(防名字对不上误判查无)、删除改走 AList 删产物文件,
     * 任务记录留存(该盘重复提交直接建新任务,无「任务已存在」限制,不影响重提)。
     */
    default boolean supportsTaskManagement() {
        return false;
    }

    /**
     * deleteTask(deleteFiles=true) 是否连产物文件一并删(115 flag=1 / 迅雷 delete_files=true)。
     * false(123 任务删除无文件参数 / 光鸭无任务删除)时,清理调度在删任务记录后经内嵌
     * AList 删产物文件兜底回收空间。
     */
    default boolean deletesFilesWithTask() {
        return false;
    }

    record TaskResult(String taskName, String infoHash, boolean folder) {
    }

    record QuotaResult(boolean supported, String displayText) {
        public static QuotaResult unsupported() {
            return new QuotaResult(false, "");
        }
    }

    enum TaskStatus {
        /** 排队/下载中 */
        RUNNING,
        /** 已完成(产物已落盘) */
        SUCCEEDED,
        /** 网盘侧终态失败 */
        FAILED,
        /** 任务列表里已不存在(用户手清/别端删/从未建成) */
        ABSENT
    }

    /** magnet 的 btih 提取(40 位 hex 小写;非 magnet 或畸形返回空串)。 */
    static String extractInfoHash(String url) {
        if (StringUtils.isBlank(url)) {
            return "";
        }
        Matcher matcher = Pattern.compile("xt=urn:btih:([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (!matcher.find()) {
            return "";
        }
        String raw = matcher.group(1);
        return raw.matches("[0-9A-Fa-f]{40}") ? raw.toLowerCase(Locale.ROOT) : "";
    }
}
