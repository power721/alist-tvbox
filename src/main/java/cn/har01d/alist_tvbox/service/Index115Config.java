package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.SettingRepository;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class Index115Config {
    private static final String VERSION_URL = "https://d.har01d.cn/115.version.txt";

    @Bean
    public RestTemplate index115RestTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }

    @Bean
    public Index115VersionClient index115VersionClient(RestTemplate index115RestTemplate) {
        return new Index115VersionClient(index115RestTemplate, VERSION_URL);
    }

    @Bean
    public Index115Extractor index115Extractor() {
        return new Index115Extractor();
    }

    @Bean
    public Index115Downloader index115Downloader(AListLocalService aListLocalService,
                                                  ShareService shareService,
                                                  AccountService accountService) {
        return new AListIndex115Downloader(aListLocalService, shareService, accountService);
    }

    @Bean
    public Index115Service index115Service(TaskService taskService,
                                           SettingRepository settingRepository,
                                           Index115VersionClient versionClient,
                                           Index115Downloader downloader,
                                           Index115Extractor extractor) {
        return new Index115Service(taskService, settingRepository, versionClient, downloader, extractor);
    }
}
