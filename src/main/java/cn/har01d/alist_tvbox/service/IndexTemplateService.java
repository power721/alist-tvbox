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
    private static final int indexVersion = 2;
    public static final String paths = "\"/\uD83C\uDE34我的阿里分享/Tacit0924/【更新中的】和完结的电视剧.动漫.电影.综艺纪录片/【电.视剧剧.集】/更新中的【电视剧】和完结的，还有一些大合集/近期【更新中】电视剧\", \"/\uD83C\uDE34我的阿里分享/Tacit0924/【更新中的】和完结的电视剧.动漫.电影.综艺纪录片/【动.漫.动.画电.影】/更新中的【动漫.动画电影】和完结的，还有一些大合集/还在【更新中】的动漫 4.2TB\", \"/\uD83C\uDE34我的阿里分享/Tacit0924/【更新中的】和完结的电视剧.动漫.电影.综艺纪录片/【综艺.纪录片.节目.晚会】/更新中的【综艺.纪录片.节目.晚会】和完结的/还在【更新中】的综艺 3TB\", \"/\uD83C\uDE34我的阿里分享/Tacit0924/【更新中的】和完结的电视剧.动漫.电影.综艺纪录片/【电.影】/近期热门【电影】和一些电影大合集/【近期一些热门的电影】18TB\", \"/\uD83C\uDE34我的阿里分享/Tacit0924/【更新中的】和完结的电视剧.动漫.电影.综艺纪录片/【综艺.纪录片.节目.晚会】/更新中的【综艺.纪录片.节目.晚会】和完结的/一些近期【更新的】和完结的和纪录片合集 15TB/【一些近期更新的纪录片】(未整理国内外仅首字母)\", \"/\uD83C\uDE34我的阿里分享/Tacit0924/【更新中的】和完结的电视剧.动漫.电影.综艺纪录片/【电.视剧剧.集】/更新中的【电视剧】和完结的，还有一些大合集/【近期完结的电视剧】(590多部剧)(23TB)(未整理国内外仅首字母)\", \"/\uD83C\uDE34我的阿里分享/Tacit0924/【更新中的】和完结的电视剧.动漫.电影.综艺纪录片/【动.漫.动.画电.影】/更新中的【动漫.动画电影】和完结的，还有一些大合集/【近期完结的动漫】3TB(未整理国内外仅首字母)\", \"/电视剧/中国/同步更新中\", \"/\uD83C\uDE34我的阿里分享/近期更新/01.电视剧.更新中\", \"/\uD83C\uDE34我的阿里分享/近期更新/02.电视剧.完结/2022年\", \"/\uD83C\uDE34我的阿里分享/近期更新/02.电视剧.完结/2023年\", \"/\uD83C\uDE34我的阿里分享/近期更新/03.电影/最新电影\", \"/\uD83C\uDE34我的阿里分享/近期更新/04.动漫剧集.更新中\", \"/\uD83C\uDE34我的阿里分享/近期更新/05.动漫剧集.完结\", \"/\uD83C\uDE34我的阿里分享/近期更新/06.综艺\", \"/\uD83C\uDE34我的阿里分享/近期更新/07.纪录片\"\n";

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

        long count = settingRepository.count();
        if (count > 0) {
            fixAutoIndex();
            return;
        }

        IndexTemplateDto dto = new IndexTemplateDto();
        dto.setSiteId(1);
        dto.setScheduled(true);
        dto.setScheduleTime("10|14|18|22");
        dto.setData("{\"siteId\":1,\"indexName\":\"custom_index\",\"excludeExternal\":false,\"includeFiles\":false,\"incremental\":true,\"compress\":false,\"maxDepth\":1,\"sleep\":1000,\"paths\":[" + paths + "],\"stopWords\":[\"获取更多分享内容\"],\"excludes\":[]}");
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
            template.setData("{\"siteId\":1,\"indexName\":\"custom_index\",\"excludeExternal\":false,\"includeFiles\":false,\"incremental\":true,\"compress\":false,\"maxDepth\":1,\"sleep\":1000,\"paths\":[" + paths + "],\"stopWords\":[\"获取更多分享内容\"],\"excludes\":[]}");
            indexTemplateRepository.save(template);
            settingRepository.save(new Setting(AUTO_INDEX_VERSION, String.valueOf(indexVersion)));
        } else {
            settingRepository.save(new Setting(AUTO_INDEX_VERSION, String.valueOf(indexVersion)));
        }
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
        template.setScheduled(dto.isScheduled());
        template.setScheduleTime(dto.getScheduleTime());
        template.setCreatedTime(Instant.now());
        return indexTemplateRepository.save(template);
    }

    public void delete(Integer id) {
        indexTemplateRepository.deleteById(id);
    }
}