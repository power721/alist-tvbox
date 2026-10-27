package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.Index115CheckResult;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AutoUpdateService {
    private final AutoUpdateExecutor executor;
    private final SubscriptionService subscriptionService;
    private final IndexService indexService;
    private final DoubanService doubanService;
    private final Index115Service index115Service;
    private final PluginService pluginService;

    public AutoUpdateService(AutoUpdateExecutor executor,
                             SubscriptionService subscriptionService,
                             IndexService indexService,
                             DoubanService doubanService,
                             Index115Service index115Service,
                             PluginService pluginService) {
        this.executor = executor;
        this.subscriptionService = subscriptionService;
        this.indexService = indexService;
        this.doubanService = doubanService;
        this.index115Service = index115Service;
        this.pluginService = pluginService;
    }

    @Scheduled(cron = "0 0 20 * * ?")
    public void autoSyncCat() {
        executor.scheduleWithJitter(() -> subscriptionService.syncCat(true));
    }

    @Scheduled(cron = "0 0 22 * * ?")
    public void autoIndex() {
        executor.scheduleWithJitter(indexService::update);
    }

    @Scheduled(cron = "0 0 20,22 * * ?")
    public void autoDouban() {
        executor.scheduleWithJitter(doubanService::update);
    }

    @Scheduled(cron = "0 0 23 * * ?")
    public void autoUpdate115() {
        executor.scheduleWithJitter(this::update115);
    }

    // 订阅源(py 插件)每日自动对齐用户配置的仓库，未配置时服务内自行跳过
    @Scheduled(cron = "0 30 6 * * ?")
    public void autoSyncPlugins() {
        executor.scheduleWithJitter(pluginService::autoSyncFromConfiguredSource);
    }

    void update115() {
        Index115CheckResult result = index115Service.check();
        if (result.hasUpdate()) {
            try {
                index115Service.update();
            } catch (BadRequestException e) {
                log.debug("index115 auto-update skipped: {}", e.getMessage());
            }
        }
    }
}
