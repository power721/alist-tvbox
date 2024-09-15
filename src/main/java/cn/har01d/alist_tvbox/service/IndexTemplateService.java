package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.IndexTemplateDto;
import cn.har01d.alist_tvbox.entity.IndexTemplate;
import cn.har01d.alist_tvbox.entity.IndexTemplateRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Slf4j
@Service
public class IndexTemplateService {
    public static final String AUTO_INDEX_VERSION = "auto_index_version";
    private static final int indexVersion = 32;
    public static final String paths =
            "\">/🈴我的阿里分享/Tacit0924\"," +
                    "\"/🈴我的阿里分享/Tacit0924/【剧a集】/【剧丨集】更新中和完结的/【更新中】电视剧::2\"," +
                    "\"/🈴我的阿里分享/Tacit0924/【剧a集】/【剧丨集】更新中和完结的/【更新中】电视剧/111/【已完结的】\"," +
                    "\"/🈴我的阿里分享/Tacit0924/【剧a集】/【剧丨集】更新中和完结的/【更新中】电视剧/【日丨韩印泰等剧】/【2024已完结的】\"," +
                    "\"/🈴我的阿里分享/Tacit0924/【剧a集】/【剧丨集】更新中和完结的/【更新中】电视剧/【欧丨美英法等剧】/【2024已完结的】\"," +
                    "\"/🈴我的阿里分享/Tacit0924/【动.漫.动.画电.影】/更新中的【动漫.动画电影】和完结的，还有一些大合集/还在【更新中】的动漫 4.2TB\"," +
                    "\"/🈴我的阿里分享/Tacit0924/【动.漫.动.画电.影】/更新中的【动漫.动画电影】和完结的，还有一些大合集/【近期完结的动漫】3TB(未整理国内外仅首字母)\"," +
                    "\"/🈴我的阿里分享/Tacit0924/【综艺 纪录片 节目 晚会】/更新中的【综丨艺纪丨录片节丨目晚丨会 】和完结的/还在【更新中】的综艺 5 TB\"," +
                    "\"/🈴我的阿里分享/Tacit0924/【综艺 纪录片 节目 晚会】/更新中的【综丨艺纪丨录片节丨目晚丨会 】和完结的/【近期的完结综艺】6TB(未整理国内外仅首字母)\"," +
                    "\"/🈴我的阿里分享/Tacit0924/【综艺 纪录片 节目 晚会】/更新中的【综丨艺纪丨录片节丨目晚丨会 】和完结的/一些近期【更新的】和完结的和纪录片合集 15TB/【一些近期更新的纪录片】(未整理国内外仅首字母)\"," +
                    "\"/🈴我的阿里分享/Tacit0924/【电a影】/近期热门【电影】和一些电影大合集/【近期一些热门的电影】22TB::2\"" ;

    private final IndexTemplateRepository indexTemplateRepository;
    private final SettingRepository settingRepository;
    private final Environment environment;

    public IndexTemplateService(IndexTemplateRepository indexTemplateRepository, SettingRepository settingRepository, Environment environment) {
        this.indexTemplateRepository = indexTemplateRepository;
        this.settingRepository = settingRepository;
        this.environment = environment;
    }

    @PostConstruct
    public void setup() {
        if (!environment.matchesProfiles("xiaoya")) {
            return;
        }

        long count = indexTemplateRepository.count();
        if (count > 0) {
            fixAutoIndex();
            return;
        }

        IndexTemplateDto dto = new IndexTemplateDto();
        dto.setSiteId(1);
        dto.setScheduled(true);
        dto.setScrape(true);
        dto.setScheduleTime("10|14|18|22");
        dto.setData("{\"siteId\":1,\"indexName\":\"custom_index\",\"excludeExternal\":false,\"includeFiles\":false,\"incremental\":true,\"compress\":false,\"maxDepth\":1,\"sleep\":5000,\"paths\":[" + paths + "],\"stopWords\":[\"获取更多分享内容\"],\"excludes\":[]}");
        IndexTemplate template = create(dto);
        log.info("auto index template created: {}", template.getId());
        settingRepository.save(new Setting("auto_index", String.valueOf(template.getId())));
        settingRepository.save(new Setting(AUTO_INDEX_VERSION, String.valueOf(indexVersion)));
    }

    private void fixAutoIndex() {
        Integer version = settingRepository.findById(AUTO_INDEX_VERSION).map(Setting::getValue).map(Integer::parseInt).orElse(0);
        if (version >= indexVersion) {
            return;
        }
        Integer id = settingRepository.findById("auto_index").map(Setting::getValue).map(Integer::parseInt).orElse(1);
        IndexTemplate template = indexTemplateRepository.findById(id).orElse(null);
        if (template != null) {
            log.info("update auto index template ");
            template.setData("{\"siteId\":1,\"indexName\":\"custom_index\",\"excludeExternal\":false,\"includeFiles\":false,\"incremental\":true,\"compress\":false,\"maxDepth\":1,\"sleep\":5000,\"paths\":[" + paths + "],\"stopWords\":[\"获取更多分享内容\"],\"excludes\":[]}");
            indexTemplateRepository.save(template);
        }
        settingRepository.save(new Setting(AUTO_INDEX_VERSION, String.valueOf(indexVersion)));
    }

    public Page<IndexTemplate> list(Pageable pageable) {
        return indexTemplateRepository.findAll(pageable);
    }

    public IndexTemplate getById(Integer id) {
        return indexTemplateRepository.findById(id).orElseThrow(() -> new NotFoundException("索引模板不存在"));
    }

    public IndexTemplate create(IndexTemplateDto dto) {
        if (StringUtils.isBlank(dto.getName())) {
            throw new BadRequestException("名称不能为空");
        }
        if (StringUtils.isBlank(dto.getData())) {
            throw new BadRequestException("数据不能为空");
        }

        IndexTemplate template = new IndexTemplate();
        template.setSiteId(dto.getSiteId());
        template.setName(dto.getName());
        template.setData(dto.getData());
        template.setSleep(dto.getSleep());
        template.setScrape(dto.isScrape());
        template.setScheduled(dto.isScheduled());
        template.setScheduleTime(dto.getScheduleTime());
        template.setCreatedTime(Instant.now());
        return indexTemplateRepository.save(template);
    }

    public IndexTemplate update(Integer id, IndexTemplateDto dto) {
        if (StringUtils.isBlank(dto.getName())) {
            throw new BadRequestException("名称不能为空");
        }
        if (StringUtils.isBlank(dto.getData())) {
            throw new BadRequestException("数据不能为空");
        }

        IndexTemplate template = getById(id);
        template.setSiteId(dto.getSiteId());
        template.setName(dto.getName());
        template.setData(dto.getData());
        template.setScrape(dto.isScrape());
        template.setScheduled(dto.isScheduled());
        template.setScheduleTime(dto.getScheduleTime());
        template.setCreatedTime(Instant.now());
        return indexTemplateRepository.save(template);
    }

    public void delete(Integer id) {
        indexTemplateRepository.deleteById(id);
    }
}