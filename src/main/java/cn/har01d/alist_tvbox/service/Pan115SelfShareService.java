package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.entity.MediaSubscription;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.model.ShareCreateData;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.storage.Storage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 115 自有分享执行器:账号解析、目标目录、转存/建分享/删源的原子动作(§ 快照式自有化)。
 * 编排(批次收集/入账/挂载/事件/限频)在 {@link MediaSubscriptionCheckService} 的 selfShare* 方法,
 * 此处只做无状态执行,便于桩测。全部动作经内嵌 PowerList 的 HTTP API:
 * 转存 = /api/fs/share/save(115 分享→同族 cookie 账号服务端秒传),建分享 = /api/fs/share/create
 * (share/send + updateshare -1 串在驱动侧),删源 = /api/fs/remove(115 驱动 Remove = rb/delete 彻底删除)。
 */
@Service
public class Pan115SelfShareService {
    private static final Logger log = LoggerFactory.getLogger(Pan115SelfShareService.class);

    private final AListService aListService;
    private final DriverAccountRepository accountRepository;
    private final SettingRepository settingRepository;
    private final ObjectMapper objectMapper;

    public Pan115SelfShareService(AListService aListService,
                                  DriverAccountRepository accountRepository,
                                  SettingRepository settingRepository,
                                  ObjectMapper objectMapper) {
        this.aListService = aListService;
        this.accountRepository = accountRepository;
        this.settingRepository = settingRepository;
        this.objectMapper = objectMapper;
    }

    /** 永久分享创建结果(share_code + 自动分配的 4 位提取码)。 */
    public record ShareCreated(String shareCode, String receiveCode, String shareUrl, String shareTitle) {
        /** 资源行 link:与 ShareService 的 115 分享解析格式一致(https://115.com/s/{code}?password={rc})。 */
        public String link() {
            return "https://115.com/s/" + shareCode + (StringUtils.isBlank(receiveCode) ? "" : "?password=" + receiveCode);
        }
    }

    /**
     * 目标 115 账号:订阅转存目标(accountIds)里第一个 cookie 版 PAN115 —— 开放平台账号
     * 无分享 API 不考虑;无则 master PAN115;都没有返回 null(调用方记事件跳过)。
     */
    public DriverAccount resolveAccount(MediaSubscription subscription) {
        for (String id : accountIds(subscription)) {
            try {
                DriverAccount account = accountRepository
                        .findById(Integer.parseInt(id.startsWith("pan:") ? id.substring(4) : id)).orElse(null);
                if (account != null && account.getType() == DriverType.PAN115) {
                    return account;
                }
            } catch (NumberFormatException ignored) {
                // 非法目标 id 跳过
            }
        }
        return accountRepository.findByTypeAndMasterTrue(DriverType.PAN115).orElse(null);
    }

    /** 订阅目标 id 列表("pan:{id}"/"ali:{id}" JSON 数组;兼容旧单值 accountId)。 */
    private List<String> accountIds(MediaSubscription subscription) {
        if (StringUtils.isNotBlank(subscription.getAccountIds())) {
            try {
                var arr = objectMapper.readTree(subscription.getAccountIds());
                if (arr.isArray() && !arr.isEmpty()) {
                    List<String> ids = new ArrayList<>();
                    arr.forEach(node -> {
                        String value = node.isNumber() ? "pan:" + node.asInt() : node.asText();
                        if (StringUtils.isNotBlank(value)) {
                            ids.add(value);
                        }
                    });
                    return ids;
                }
            } catch (Exception ignored) {
                // 回退旧单值
            }
        }
        return subscription.getAccountId() == null ? List.of() : List.of("pan:" + subscription.getAccountId());
    }

    /**
     * 批次转存目录:{账号挂载根}/{transferRoot(默认 我的追剧)}/{剧名 季 元数据标签}。
     * 与 MediaSubscriptionTransferService 的转存目录同规格(自有化与 TRANSFER 互斥,不会共享)。
     */
    public String targetDir(MediaSubscription subscription, DriverAccount account) {
        return Storage.getMountPath(account) + "/" + transferRoot() + "/" + dirBaseName(subscription);
    }

    /** 转存固定根目录名(Setting msub_transfer_root,与 TRANSFER 共用同一配置)。 */
    private String transferRoot() {
        String root = settingRepository.findById("msub_transfer_root")
                .map(Setting::getValue).orElse("");
        root = root.replaceAll("[/\\\\]+", "").trim();
        return root.isEmpty() ? "我的追剧" : root;
    }

    /** 目录名:剧名 + 季 + 元数据 id 标签(与挂载目录命名一致,分享标题即目录名)。 */
    private static String dirBaseName(MediaSubscription subscription) {
        String base = sanitize(subscription.getName())
                + (subscription.getSeason() != null && subscription.getSeason() > 1 ? "-第" + subscription.getSeason() + "季" : "");
        String tag = MediaSubscriptionService.metaIdTag(subscription);
        return tag == null ? base : base + " " + tag;
    }

    static String sanitize(String name) {
        String slug = name.replaceAll("[\\s/\\\\:*?\"<>|#@$%\\.、,]+", "-");
        slug = StringUtils.strip(slug, "-");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        return slug.isEmpty() ? "sub" : slug;
    }

    /** 确保目录存在(已存在时 AList mkdir 返回错误,容忍)。 */
    public void prepareDir(Site site, String dir) {
        try {
            aListService.mkdir(site, dir);
        } catch (Exception e) {
            log.debug("mkdir {} tolerated (likely exists): {}", dir, e.getMessage());
        }
    }

    /** 服务端转存一组对象(每批 ≤10 控制同步请求时长,与 TRANSFER 同款分批)。 */
    public void transferObjects(Site site, String srcDir, List<String> names, String dstDir) {
        for (int i = 0; i < names.size(); i += 10) {
            aListService.shareSave(site, srcDir, names.subList(i, Math.min(i + 10, names.size())), dstDir);
        }
    }

    /** 列目录下的对象名(残留感知与删源清单)。 */
    public List<String> listNames(Site site, String dir) {
        FsResponse response = aListService.listFiles(site, dir, 1, 1000);
        List<String> names = new ArrayList<>();
        if (response != null && response.getContent() != null) {
            response.getContent().forEach(info -> names.add(info.getName()));
        }
        return names;
    }

    /** 创建永久分享(两步契约在 PowerList 驱动内串好)。 */
    public ShareCreated createShare(Site site, String dir) {
        ShareCreateData data = aListService.createShare(site, dir);
        if (data == null || StringUtils.isBlank(data.getShareCode())) {
            throw new IllegalStateException("创建分享无返回码");
        }
        return new ShareCreated(data.getShareCode(), data.getReceiveCode(), data.getShareUrl(), data.getShareTitle());
    }

    /** 删除目录下全部文件(分享快照已固化,盘内源文件即冗余;逐个提交 /api/fs/remove)。 */
    public void removeAll(Site site, String dir, List<String> names) {
        for (String name : names) {
            aListService.remove(site, dir + "/" + name);
        }
    }
}
