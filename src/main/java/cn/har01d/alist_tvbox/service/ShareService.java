package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.domain.DriverType;
import cn.har01d.alist_tvbox.dto.OpenApiDto;
import cn.har01d.alist_tvbox.dto.ShareLink;
import cn.har01d.alist_tvbox.dto.SharesDto;
import cn.har01d.alist_tvbox.entity.AListAlias;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.PikPakAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.entity.Site;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.FsResponse;
import cn.har01d.alist_tvbox.model.LoginRequest;
import cn.har01d.alist_tvbox.model.LoginResponse;
import cn.har01d.alist_tvbox.model.Response;
import cn.har01d.alist_tvbox.storage.AList;
import cn.har01d.alist_tvbox.storage.Alias;
import cn.har01d.alist_tvbox.storage.AliyunShare;
import cn.har01d.alist_tvbox.storage.BaiduShare;
import cn.har01d.alist_tvbox.storage.Local;
import cn.har01d.alist_tvbox.storage.Pan115Share;
import cn.har01d.alist_tvbox.storage.Pan123Share;
import cn.har01d.alist_tvbox.storage.Pan139Share;
import cn.har01d.alist_tvbox.storage.Pan189Share;
import cn.har01d.alist_tvbox.storage.PikPakShare;
import cn.har01d.alist_tvbox.storage.QuarkShare;
import cn.har01d.alist_tvbox.storage.Storage;
import cn.har01d.alist_tvbox.storage.ThunderShare;
import cn.har01d.alist_tvbox.storage.UCShare;
import cn.har01d.alist_tvbox.storage.UrlTree;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static cn.har01d.alist_tvbox.util.Constants.ALI_SECRET;
import static cn.har01d.alist_tvbox.util.Constants.ATV_PASSWORD;
import static cn.har01d.alist_tvbox.util.Constants.OPEN_TOKEN_URL;

@Slf4j
@Service

public class ShareService {

    private final AppProperties appProperties;
    private final ShareRepository shareRepository;
    private final MetaRepository metaRepository;
    private final AListAliasRepository aliasRepository;
    private final SettingRepository settingRepository;
    private final SiteRepository siteRepository;
    private final AccountRepository accountRepository;
    private final PikPakAccountRepository pikPakAccountRepository;
    private final DriverAccountRepository panAccountRepository;
    private final AccountService accountService;
    private final AListLocalService aListLocalService;
    private final AListService aListService;
    private final ConfigFileService configFileService;
    private final PikPakService pikPakService;
    private final DriverAccountService driverAccountService;
    private final RestTemplate restTemplate;
    private final Environment environment;

    private final int offset = 99900;
    private int shareId = 20000;

    public ShareService(AppProperties appProperties1,
                        ShareRepository shareRepository,
                        MetaRepository metaRepository,
                        AListAliasRepository aliasRepository,
                        SettingRepository settingRepository,
                        SiteRepository siteRepository,
                        AccountRepository accountRepository,
                        PikPakAccountRepository pikPakAccountRepository,
                        DriverAccountRepository panAccountRepository,
                        AListService aListService,
                        DriverAccountService driverAccountService,
                        AppProperties appProperties,
                        AccountService accountService,
                        AListLocalService aListLocalService,
                        ConfigFileService configFileService,
                        PikPakService pikPakService,
                        RestTemplateBuilder builder,
                        Environment environment) {
        this.appProperties = appProperties1;
        this.shareRepository = shareRepository;
        this.metaRepository = metaRepository;
        this.aliasRepository = aliasRepository;
        this.settingRepository = settingRepository;
        this.siteRepository = siteRepository;
        this.accountRepository = accountRepository;
        this.pikPakAccountRepository = pikPakAccountRepository;
        this.panAccountRepository = panAccountRepository;
        this.aListService = aListService;
        this.driverAccountService = driverAccountService;
        this.accountService = accountService;
        this.aListLocalService = aListLocalService;
        this.configFileService = configFileService;
        this.pikPakService = pikPakService;
        this.environment = environment;
        this.restTemplate = builder.rootUri("http://localhost:" + (appProperties.isHostmode() ? "5234" : "5244")).build();
    }

    @PostConstruct
    public void setup() {
        migrateId();
        updateAListDriverType();
        loadOpenTokenUrl();

        pikPakService.readPikPak();
        driverAccountService.loadStorages();

        cleanTempShares();

        List<Share> list = shareRepository.findAll();
        fixPath(list);

        if (list.isEmpty()) {
            list = loadSharesFromFile();
        }

        list = list.stream().filter(e -> e.getId() < offset).collect(Collectors.toList());
        var add = loadLatestShare();
        list.addAll(add);

        loadAListShares(list);
        loadAListAlias();
        loadSites();
        pikPakService.loadPikPak();
        configFileService.writeFiles();
        readTvTxt();

        if (environment.getProperty("INSTALL") == null
                || "new".equals(environment.getProperty("INSTALL"))
                || accountRepository.count() > 0
                || pikPakAccountRepository.count() > 0
                || panAccountRepository.count() > 0
                || shareRepository.count() > add.size()) {
            aListLocalService.startAListServer();
        }
    }

    private void fixPath(List<Share> shares) {
        if (!settingRepository.existsByName("fix_share_path")) {
            List<Share> changed = new ArrayList<>();
            for (Share share : shares) {
                String path = share.getPath();
                share.setPath(Storage.getMountPath(share));
                if (!path.equals(share.getPath())) {
                    changed.add(share);
                }
            }
            log.info("fix_share_path {}", changed.size());
            shareRepository.saveAll(changed);
            settingRepository.save(new Setting("fix_share_path", ""));
        }
    }

    private void migrateId() {
        if (!settingRepository.existsByName("migrate_share_ids")) {
            List<Share> list = shareRepository.findAll();
            for (Share share : list) {
                share.setId(shareId++);
            }
            shareRepository.deleteAll();
            shareRepository.saveAll(list);
            settingRepository.save(new Setting("migrate_share_ids", ""));
        }
    }

    @Scheduled(cron = "0 30 * * * *")
    public void cleanShares() {
        cleanTempShares();
        cleanInvalidShares();
    }

    private void cleanTempShares() {
        List<Share> list = shareRepository.findByTempTrue();
        Instant time = Instant.now().minus(appProperties.getTempShareExpiration(), ChronoUnit.HOURS);
        for (Share share : list) {
            if (share.isTemp() && share.getTime() != null && share.getTime().isBefore(time)) {
                log.info("Delete temp share: {} {}", share.getId(), share.getPath());
                shareRepository.delete(share);
            }
        }
    }

    public void cleanInvalidShares() {
        if (appProperties.isCleanInvalidShares()) {
            cleanStorages();
        }
    }

    @Scheduled(cron = "0 0 */4 * * *")
    public void validateShares() {
        if (appProperties.isCleanInvalidShares()) {
            validateStorages();
        }
    }

    public void loadAListAlias() {
        List<AListAlias> list = aliasRepository.findAll();
        if (list.isEmpty()) {
            return;
        }

        try {
            for (AListAlias alias : list) {
                try {
                    Alias storage = new Alias(alias);
                    aListLocalService.saveStorage(storage);
                } catch (Exception e) {
                    log.warn("{}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void loadSites() {
        for (Site site : siteRepository.findAll()) {
            if (site.getId() == 1 || site.isDisabled() || site.getUrl().startsWith("http://localhost")) {
                continue;
            }
            try {
                AList storage = new AList(site);
                aListLocalService.saveStorage(storage);
            } catch (Exception e) {
                log.warn("{}", e.getMessage());
            }
        }
    }

    private void loadOpenTokenUrl() {
        if (settingRepository.existsById(OPEN_TOKEN_URL)) {
            return;
        }

        try {
            String url = null;
            try {
                Path file = Utils.getDataPath("open_token_url.txt");
                if (Files.exists(file)) {
                    url = Files.readString(file).trim();
                    log.debug("loadOpenTokenUrl {}", url);
                    settingRepository.save(new Setting(OPEN_TOKEN_URL, url));
                }
            } catch (Exception e) {
                log.warn("", e);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    public void updateOpenTokenUrl(OpenApiDto dto) {
        String url = dto.getUrl();
        settingRepository.save(new Setting(OPEN_TOKEN_URL, url));
        settingRepository.save(new Setting("open_api_client_id", dto.getClientId() == null ? "" : dto.getClientId().trim()));
        settingRepository.save(new Setting("open_api_client_secret", dto.getClientSecret() == null ? "" : dto.getClientSecret().trim()));
        aListLocalService.setSetting("open_token_url", url, "string");
        aListLocalService.setSetting("open_api_client_id", dto.getClientId().trim(), "string");
        aListLocalService.setSetting("open_api_client_secret", dto.getClientSecret().trim(), "string");
    }

    private List<Share> loadSharesFromFile() {
        List<Share> list = new ArrayList<>();
        Path path = Utils.getDataPath("alishare_list.txt");
        if (Files.exists(path)) {
            try {
                log.info("loading share list from file");
                List<String> lines = Files.readAllLines(path);
                for (String line : lines) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 1) {
                        try {
                            Share share = new Share();
                            share.setId(shareId++);
                            share.setPath(parts[0]);
                            share.setShareId(parts[1]);
                            if (parts.length > 2) {
                                share.setFolderId(parts[2]);
                            }
                            share.setType(0);
                            list.add(share);
                        } catch (Exception e) {
                            log.warn("", e);
                        }
                    }
                }
                shareRepository.saveAll(list);
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        list.addAll(loadPikPakFromFile());
        return list;
    }

    private List<Share> loadPikPakFromFile() {
        List<Share> list = new ArrayList<>();
        Path path = Utils.getDataPath("pikpakshare_list.txt");
        if (Files.exists(path)) {
            try {
                log.info("loading PikPak share list from file");
                List<String> lines = Files.readAllLines(path);
                for (String line : lines) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 1) {
                        try {
                            Share share = new Share();
                            share.setId(shareId++);
                            share.setPath(parts[0]);
                            share.setShareId(parts[1]);
                            if (parts.length > 2) {
                                share.setFolderId(parts[2]);
                            } else {
                                share.setFolderId("");
                            }
                            share.setType(1);
                            list.add(share);
                        } catch (Exception e) {
                            log.warn("", e);
                        }
                    }
                }
                shareRepository.saveAll(list);
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        return list;
    }

    public int importShares(SharesDto dto) {
        int count = 0;
        log.info("import share list");
        for (String line : dto.getContent().split("\n")) {
            String[] parts = line.trim().split("\\s+");
            log.debug("import {} {}", parts.length, line);
            if (parts.length > 1) {
                try {
                    Share share = new Share();
                    share.setId(shareId);
                    share.setType(dto.getType());
                    share.setPath(parts[0]);
                    String[] id = parts[1].split(":", 2);
                    if (id.length > 1) {
                        share.setType(Integer.parseInt(id[0]));
                        share.setShareId(id[1]);
                    } else {
                        share.setShareId(id[0]);
                    }
                    if (parts.length > 2) {
                        share.setFolderId(parts[2]);
                    }
                    if (parts.length > 3) {
                        share.setPassword(parts[3]);
                    }
                    share.setPath(getMountPath(share));
                    if (shareRepository.existsByPath(share.getPath())) {
                        continue;
                    }
                    create(share);
                    count++;
                } catch (Exception e) {
                    log.warn("{}", e.getMessage());
                }
            }
        }

        log.info("loaded {} shares", count);
        return count;
    }

    public String exportShare(HttpServletResponse response, int type) {
        List<Share> list = type < 0 ? shareRepository.findAll() : shareRepository.findByType(type);
        StringBuilder sb = new StringBuilder();
        String fileName = "shares.txt";
        if (type == 1) {
            fileName = "pikpak_shares.txt";
        } else if (type == 5) {
            fileName = "quark_shares.txt";
        } else if (type == 7) {
            fileName = "uc_shares.txt";
        } else if (type == 8) {
            fileName = "115_shares.txt";
        } else if (type == 9) {
            fileName = "189_shares.txt";
        } else if (type == 6) {
            fileName = "139_shares.txt";
        } else if (type == 2) {
            fileName = "thunder_shares.txt";
        } else if (type == 3) {
            fileName = "123_shares.txt";
        } else if (type == 0) {
            fileName = "ali_shares.txt";
        } else if (type == 4) {
            fileName = "baidu_shares.txt";
        }

        for (Share share : list) {
            if (share.isTemp()) {
                continue;
            }
            sb.append(getMountPath(share).replace(" ", "")).append("  ")
                    .append(share.getType()).append(":")
                    .append(share.getShareId()).append("  ")
                    .append(StringUtils.isBlank(share.getFolderId()) ? "root" : share.getFolderId()).append("  ")
                    .append(share.getPassword())
                    .append("\n");
        }

        log.info("export {} shares to file: {}", list.size(), fileName);
        response.addHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        response.setContentType("text/plain");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        return sb.toString();
    }

    private void loadAListShares(List<Share> list) {
        if (list.isEmpty()) {
            return;
        }

        boolean pikpak = false;
        try {
            for (Share share : list) {
                try {
                    Storage storage = saveStorage(share, false);
                    if (storage != null && storage.getDriver().equals("PikPakShare")) {
                        pikpak = true;
                    }
                    if (share.getId() < offset) {
                        shareId = Math.max(shareId, share.getId() + 1);
                    }
                    if (share.getType() == null) {
                        share.setType(0);
                        shareRepository.save(share);
                    }
                } catch (Exception e) {
                    log.warn("{}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        if (pikpak) {
            pikPakService.updateIndexFile();
        }
    }

    private Storage saveStorage(Share share, boolean disabled) {
        Storage storage = null;
        if (share.getType() == null || share.getType() == 0) {
            storage = new AliyunShare(share);
        } else if (share.getType() == 1) {
            storage = new PikPakShare(share);
        } else if (share.getType() == 8) {
            storage = new Pan115Share(share);
        } else if (share.getType() == 4) {
            storage = new Local(share);
        } else if (share.getType() == 5) {
            storage = new QuarkShare(share);
        } else if (share.getType() == 7) {
            storage = new UCShare(share);
        } else if (share.getType() == 9) {
            storage = new Pan189Share(share);
        } else if (share.getType() == 2) {
            storage = new ThunderShare(share);
        } else if (share.getType() == 3) {
            storage = new Pan123Share(share);
        } else if (share.getType() == 6) {
            storage = new Pan139Share(share);
        } else if (share.getType() == 10) {
            storage = new BaiduShare(share);
        }

        if (storage != null) {
            storage.setDisabled(disabled);
            aListLocalService.saveStorage(storage);
        }

        return storage;
    }

    private void updateAListDriverType() {
        try {
            log.info("update storage driver type");
            Utils.executeUpdate("update x_storages set driver = 'AliyunShare' where driver = 'AliyundriveShare'");
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    private void updateCookieByApi(String key, String cookie) {
        int status = aListLocalService.checkStatus();
        if (status == 1) {
            aListLocalService.setSetting(key, cookie, "text");
            throw new BadRequestException("AList服务启动中");
        }

        String token = status >= 2 ? login() : "";
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        Map<String, Object> body = new HashMap<>();
        body.put("key", key);
        body.put("type", "text");
        body.put("flag", 1);
        body.put("value", cookie);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/setting/update", HttpMethod.POST, entity, String.class);
        log.info("updateCookieByApi {} response: {}", response.getBody());
    }

    public String login() {
        String username = "atv";
        String password = settingRepository.findById(ATV_PASSWORD).map(Setting::getValue).orElseThrow(BadRequestException::new);
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        LoginResponse response = restTemplate.postForObject("/api/auth/login", request, LoginResponse.class);
        log.debug("AList login response: {}", response);
        return response.getData().getToken();
    }

    private String getMountPath(Share share) {
        return Storage.getMountPath(share);
    }

    private void readTvTxt() {
        Path file = Utils.getDataPath("tv.txt");
        if (Files.exists(file)) {
            log.info("read tv from file");
            try {
                StringBuilder sb = parseTvFile(file);
                Storage storage = new UrlTree(2050, "/\uD83C\uDDF9\uD83C\uDDFB直播/我的自选", sb.toString());
                aListLocalService.saveStorage(storage);
            } catch (Exception e) {
                log.warn("", e);
            }
        }
    }

    private static StringBuilder parseTvFile(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String[] parts = line.trim().split(",");
            if ((parts.length == 1 || "#genre#".equals(parts[1])) && StringUtils.isNotBlank(parts[0])) {
                sb.append(parts[0]).append(":\\n");
            } else if (parts.length == 2 && !StringUtils.isAnyBlank(parts[0], parts[1])) {
                sb.append("  ").append(parts[0]).append(".m3u8:").append(parts[1]).append("\\n");
            } else {
                sb.append("\\n");
            }
        }
        return sb;
    }

    public Page<Share> list(Pageable pageable, Integer type, String keyword) {
        if (type != null && type > -1) {
            if (StringUtils.isBlank(keyword)) {
                return shareRepository.findByType(type, pageable);
            } else {
                return shareRepository.findByTypeAndPathContains(type, keyword, pageable);
            }
        }

        if (StringUtils.isBlank(keyword)) {
            return shareRepository.findAll(pageable);
        } else {
            return shareRepository.findByPathContains(keyword, pageable);
        }
    }

    public String getQuarkCookie(String id) {
        String aliSecret = settingRepository.findById(ALI_SECRET).map(Setting::getValue).orElse("");
        if (aliSecret.equals(id)) {
            return panAccountRepository.findByTypeAndMasterTrue(DriverType.QUARK).map(DriverAccount::getCookie).orElse("").trim();
        }
        return "";
    }

    public String getUcCookie(String id) {
        String aliSecret = settingRepository.findById(ALI_SECRET).map(Setting::getValue).orElse("");
        if (aliSecret.equals(id)) {
            return panAccountRepository.findByTypeAndMasterTrue(DriverType.UC).map(DriverAccount::getCookie).orElse("").trim();
        }
        return "";
    }

    public String get115Cookie(String id) {
        String aliSecret = settingRepository.findById(ALI_SECRET).map(Setting::getValue).orElse("");
        if (aliSecret.equals(id)) {
            return panAccountRepository.findByTypeAndMasterTrue(DriverType.PAN115).map(DriverAccount::getCookie).orElse("").trim();
        }
        return "";
    }

    public String getBaiduCookie(String id) {
        String aliSecret = settingRepository.findById(ALI_SECRET).map(Setting::getValue).orElse("");
        if (aliSecret.equals(id)) {
            return panAccountRepository.findByTypeAndMasterTrue(DriverType.BAIDU).map(DriverAccount::getCookie).orElse("").trim();
        }
        return "";
    }

    private static final Pattern SHARE_115_LINK = Pattern.compile("https://(?:115|115cdn).com/s/([\\w-]+)(?:\\?password=([\\w-]+))?");
    private static final Pattern SHARE_XL_LINK = Pattern.compile("https://pan.xunlei.com/s/([\\w-]+)(?:\\?pwd=([\\w-]+))?");
    private static final Pattern SHARE_BD_LINK1 = Pattern.compile("https://pan.baidu.com/s/([\\w-]+)(?:\\?pwd=([\\w-]+))?");
    private static final Pattern SHARE_BD_LINK2 = Pattern.compile("https://pan.baidu.com/share/init\\?surl=([\\w-]+)(?:&pwd=([\\w-]+))?");
    private static final Pattern SHARE_PK_LINK = Pattern.compile("https://mypikpak.com/s/([\\w-]+)(?:\\?pwd=([\\w-]+))?");
    private static final Pattern SHARE_189_LINK1 = Pattern.compile("https://cloud.189.cn/web/share\\?code=([\\w-]+)");
    private static final Pattern SHARE_189_LINK2 = Pattern.compile("https://cloud.189.cn/t/([\\w-]+)(?:（访问码：(\\w+)）)?");
    private static final Pattern SHARE_189_LINK3 = Pattern.compile("https://h5.cloud.189.cn/share.html#/t/([\\w-]+)");
    private static final Pattern SHARE_139_LINK1 = Pattern.compile("https://caiyun.139.com/m/i\\?([\\w-]+)");
    private static final Pattern SHARE_139_LINK2 = Pattern.compile("https://yun.139.com/shareweb/#/w/i/([\\w-]+)");
    private static final Pattern SHARE_139_LINK3 = Pattern.compile("https://caiyun.139.com/w/i/([\\w-]+)");
    private static final Pattern SHARE_QUARK_LINK = Pattern.compile("https://pan.quark.cn/s/([\\w-]+)");
    private static final Pattern SHARE_UC_LINK = Pattern.compile("https://(?:drive|fast).uc.cn/s/([\\w-]+)(?:\\?password=(\\w+))?");
    private static final Pattern SHARE_ALI_LINK1 = Pattern.compile("https://www.(?:alipan|aliyundrive).com/s/([\\w-]+)/folder/([\\w-]+)(?:\\?password=(\\w+))?");
    private static final Pattern SHARE_ALI_LINK2 = Pattern.compile("https://www.(?:alipan|aliyundrive).com/s/([\\w-]+)(?:\\?password=(\\w+))?");
    private static final Pattern SHARE_123_LINK1 = Pattern.compile("https://www.(?:123pan|123684|123865|123912).com/s/([\\w-]+)提取码[:：](\\w+)");
    private static final Pattern SHARE_123_LINK2 = Pattern.compile("https://www.(?:123pan|123684|123865|123912).com/s/([\\w-]+)(?:\\.html)?(?:\\??提取码[:：](\\w+))?");

    public boolean parseLink(Share share) {
        String url = share.getShareId();
        if (!url.startsWith("http")) {
            String[] parts = url.split("@");
            if (parts.length == 3 || (parts.length == 2 && url.endsWith("@"))) {
                share.setType(Integer.parseInt(parts[0]));
                share.setShareId(parts[1]);
                if (parts.length > 2) {
                    share.setPassword(parts[2]);
                }
                return true;
            }
            return false;
        }

        var m = SHARE_115_LINK.matcher(url);
        if (m.find()) {
            share.setType(8);
            share.setShareId(m.group(1));
            share.setPassword(m.group(2));
            return true;
        }

        m = SHARE_XL_LINK.matcher(url);
        if (m.find()) {
            share.setType(2);
            share.setShareId(m.group(1));
            share.setPassword(m.group(2));
            return true;
        }

        m = SHARE_189_LINK1.matcher(url);
        if (m.find()) {
            share.setType(9);
            share.setShareId(m.group(1));
            return true;
        }

        m = SHARE_189_LINK2.matcher(url);
        if (m.find()) {
            share.setType(9);
            share.setShareId(m.group(1));
            String code = m.group(2);
            if (code != null) {
                share.setPassword(code);
            }
            return true;
        }

        m = SHARE_189_LINK3.matcher(url);
        if (m.find()) {
            share.setType(9);
            share.setShareId(m.group(1));
            return true;
        }

        m = SHARE_139_LINK1.matcher(url);
        if (m.find()) {
            share.setType(6);
            share.setShareId(m.group(1));
            return true;
        }

        m = SHARE_139_LINK2.matcher(url);
        if (m.find()) {
            share.setType(6);
            share.setShareId(m.group(1));
            return true;
        }

        m = SHARE_139_LINK3.matcher(url);
        if (m.find()) {
            share.setType(6);
            share.setShareId(m.group(1));
            return true;
        }

        m = SHARE_123_LINK1.matcher(url);
        if (m.find()) {
            share.setType(3);
            share.setShareId(m.group(1));
            String code = m.group(2);
            if (code != null) {
                share.setPassword(code);
            }
            return true;
        }

        m = SHARE_123_LINK2.matcher(url);
        if (m.find()) {
            share.setType(3);
            share.setShareId(m.group(1));
            String code = m.group(2);
            if (code != null) {
                share.setPassword(code);
            }
            return true;
        }

        m = SHARE_QUARK_LINK.matcher(url);
        if (m.find()) {
            share.setType(5);
            share.setShareId(m.group(1));
            return true;
        }

        m = SHARE_UC_LINK.matcher(url);
        if (m.find()) {
            share.setType(7);
            share.setShareId(m.group(1));
            String code = m.group(2);
            if (code != null) {
                share.setPassword(code);
            }
            return true;
        }

        m = SHARE_ALI_LINK1.matcher(url);
        if (m.find()) {
            share.setType(0);
            share.setShareId(m.group(1));
            share.setFolderId(m.group(2));
            String code = m.group(3);
            if (code != null) {
                share.setPassword(code);
            }
            return true;
        }

        m = SHARE_ALI_LINK2.matcher(url);
        if (m.find()) {
            share.setType(0);
            share.setShareId(m.group(1));
            String code = m.group(2);
            if (code != null) {
                share.setPassword(code);
            }
            return true;
        }

        m = SHARE_BD_LINK1.matcher(url);
        if (m.find()) {
            share.setType(10);
            share.setShareId(m.group(1));
            String code = m.group(2);
            if (code != null) {
                share.setPassword(code);
            }
            return true;
        }

        m = SHARE_BD_LINK2.matcher(url);
        if (m.find()) {
            share.setType(10);
            share.setShareId(m.group(1));
            String code = m.group(2);
            if (code != null) {
                share.setPassword(code);
            }
            return true;
        }

        m = SHARE_PK_LINK.matcher(url);
        if (m.find()) {
            share.setType(1);
            share.setShareId(m.group(1));
            String code = m.group(2);
            if (code != null) {
                share.setPassword(code);
            }
            return true;
        }
        return false;
    }

    private void parseShare(Share share) {
        String url = share.getShareId();
        if (StringUtils.isBlank(url)) {
            return;
        }

        if (parseLink(share)) {
            return;
        }

        int index = url.indexOf("/s/");
        if (index > 0) {
            url = url.substring(index + 3);
        }
        index = url.indexOf("/t/");
        if (index > 0) {
            url = url.substring(index + 3);
        }
        index = url.indexOf("#/list/share/");
        if (index > 0) {
            String path = url.substring(index + 13);
            String[] parts = path.split("/");
            path = parts[parts.length - 1].split("-")[0];
            share.setFolderId(path);
            url = url.substring(0, index);
        }
        index = url.indexOf('?');
        if (index > 0) {
            url = url.substring(0, index);
        }

        String[] parts = url.split("/");
        if (parts.length == 3 && "folder".equals(parts[1])) {
            share.setShareId(parts[0]);
            share.setFolderId(parts[2]);
        } else if (parts.length == 2) {
            share.setShareId(parts[0]);
            share.setFolderId(parts[1]);
        } else {
            share.setShareId(parts[0]);
        }
        share.setPath(Storage.getMountPath(share));
    }

    public String add(ShareLink dto) {
        Share share = new Share();
        share.setShareId(URLDecoder.decode(dto.getLink(), StandardCharsets.UTF_8));
        share.setPassword(dto.getCode());
        if (!parseLink(share)) {
            log.warn("无法识别的分享链接: {}", share.getShareId());
            throw new BadRequestException("无法识别的分享链接");
        }
        if (StringUtils.isBlank(dto.getPath())) {
            share.setTemp(true);
            share.setPath("temp/" + share.getType() + "@" + share.getShareId() + "@" + share.getPassword());
        } else {
            share.setPath(dto.getPath());
        }
        share.setPath(getMountPath(share));
        String path = share.getPath();
        if (!shareRepository.existsByPath(path)) {
            create(share);
            if (StringUtils.isNotBlank(share.getError())) {
                String error = share.getError()
                        .replace("failed load storage:", "")
                        .replace("failed init storage:", "")
                        .trim();
                throw new BadRequestException(error);
            }
        }
        Site site = siteRepository.findById(1).orElseThrow();

        for (int i = 0; i < 3; i++) {
            FsResponse response = aListService.listFiles(site, path, 1, 1);
            if (response.getTotal() == 1 && response.getFiles().get(0).getType() == 1) {
                path = path + "/" + response.getFiles().get(0).getName();
            } else {
                break;
            }
        }

        return path;
    }

    public Share create(Share share) {
        aListLocalService.validateAListStatus();
        validate(share);
        parseShare(share);
        fixFolderId(share);

        try {
            String token = accountService.login();
            synchronized (this) {
                share.setId(shareId++);
            }

            saveStorage(share, true);

            shareRepository.save(share);

            String error = enableStorage(share.getId(), token);
            share.setError(error);
            if (appProperties.isCleanInvalidShares() && invalid(error)) {
                shareRepository.delete(share);
                deleteStorage(share.getId(), token);
            }
        } catch (Exception e) {
            log.warn("", e);
            throw new BadRequestException(e);
        }
        return share;
    }

    public Share update(Integer id, Share share) {
        aListLocalService.validateAListStatus();
        validate(share);
        parseShare(share);
        fixFolderId(share);

        share.setId(id);
        share.setTemp(false);
        shareRepository.save(share);

        String token = accountService.login();
        try {
            deleteStorage(id, token);
            saveStorage(share, true);
            enableStorage(id, token);
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
        return share;
    }

    private void validate(Share share) {
        if (StringUtils.isBlank(share.getPath())) {
            throw new BadRequestException("挂载路径不能为空");
        }
        if (share.getPath().equals("/")) {
            throw new BadRequestException("挂载路径不能为/");
        }
        if (share.getPath().contains(" ")) {
            throw new BadRequestException("挂载路径不能包含空格");
        }

        if (share.getType() != 4) {
            if (StringUtils.isBlank(share.getShareId())) {
                throw new BadRequestException("分享ID不能为空");
            }
        }

        if (share.getCookie() != null) {
            share.setCookie(share.getCookie().trim());
        }
    }

    private static void fixFolderId(Share share) {
        if (StringUtils.isBlank(share.getFolderId())) {
            if (share.getType() == 3 || share.getType() == 5 || share.getType() == 7) {
                share.setFolderId("0");
            } else if (share.getType() == 0) {
                share.setFolderId("root");
            } else if (share.getType() == 4) {
                share.setFolderId("/");
            }
        } else if ("root".equals(share.getFolderId())) {
            if ((share.getType() == 1 || share.getType() == 2 || share.getType() == 9)) {
                share.setFolderId("");
            } else if (share.getType() == 3 || share.getType() == 5 || share.getType() == 7 || share.getType() == 8) {
                share.setFolderId("0");
            } else if (share.getType() == 10) {
                share.setFolderId("/");
            }
        }
    }

    public String enableStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<Map> response = restTemplate.exchange("/api/admin/storage/enable?id=" + id, HttpMethod.POST, entity, Map.class);
        log.info("enable storage response: {}", response.getBody());
        int code = (int) response.getBody().get("code");
        if (code >= 400) {
            return (String) response.getBody().get("message");
        } else {
            return null;
        }
    }

    public void deleteShares(List<Integer> ids) {
        aListLocalService.validateAListStatus();
        for (Integer id : ids) {
            try {
                shareRepository.deleteById(id);
                String token = accountService.login();
                deleteStorage(id, token);
            } catch (Exception e) {
                log.warn("{}", e.getMessage());
            }
        }
    }

    public void deleteShare(Integer id) {
        aListLocalService.validateAListStatus();
        shareRepository.deleteById(id);
        String token = accountService.login();
        deleteStorage(id, token);
    }

    public void deleteStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, token);
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/storage/delete?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("delete storage response: {}", response.getBody());
    }

    public JsonNode listStorages(Pageable pageable) {
        aListLocalService.validateAListStatus();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, accountService.login());
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(null, headers);
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/admin/storage/failed?page=" + pageable.getPageNumber() + "&per_page=" + pageable.getPageSize(), HttpMethod.GET, entity, JsonNode.class);
        return response.getBody();
    }

    public void validateStorages() {
        aListLocalService.validateAListStatus();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, accountService.login());
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(null, headers);
        ResponseEntity<JsonNode> response = restTemplate.exchange("/api/admin/storage/failed", HttpMethod.POST, entity, JsonNode.class);
    }

    public int cleanStorages() {
        int count = 0;
        int size = 500;
        int retry = 1;
        while (retry++ < 10) {
            Pageable pageable = PageRequest.of(1, size);
            JsonNode result = listStorages(pageable);
            JsonNode content = result.get("data").get("content");
            if (content instanceof ArrayNode) {
                for (int i = 0; i < content.size(); i++) {
                    JsonNode item = content.get(i);
                    String status = item.get("status").asText();
                    if (invalid(status)) {
                        int id = item.get("id").asInt();
                        String path = item.get("mount_path").asText();
                        log.warn("delete invalid share: {} {} reason: {}", id, path, status);
                        deleteShare(id);
                        count++;
                    }
                }
                if (content.size() < size) {
                    break;
                }
            }
        }
        return count;
    }

    private static boolean invalid(String status) {
        if (status == null) {
            return false;
        }
        return status.contains("分享码错误或者分享地址错误")
                || status.contains("share_link is forbidden")
                || status.contains("share_link is expired")
                || status.contains("share_link is cancelled by the creator")
                || status.contains("share_link cannot be found")
                || status.contains("share_pwd is not valid")
                || status.contains("guest missing pwd_id or stoken")
                || status.contains("获取天翼网盘分享信息为空")
                || status.contains("文件涉及违规内容")
                || status.contains("分享者用户封禁链接查看受限")
                || status.contains("链接已失效")
                || status.contains("分享地址已失效")
                || status.contains("好友已取消了分享")
                || status.contains("分享已取消")
                || status.contains("分享不存在")
                || status.contains("文件不存在")
                || status.contains("文件没有被分享")
                ;
    }

    public Response reloadStorage(Integer id) {
        aListLocalService.validateAListStatus();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, accountService.login());
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(null, headers);
        ResponseEntity<Response> response = restTemplate.exchange("/api/admin/storage/reload?id=" + id, HttpMethod.POST, entity, Response.class);
        log.debug("reload storage {}: {}", id, response.getBody());
        return response.getBody();
    }

    private List<Share> loadLatestShare() {
        List<Share> shares = new ArrayList<>();
        if (!environment.matchesProfiles("xiaoya")) {
            return shares;
        }

        if (!settingRepository.existsByName("fix_share_id")) {
            shareRepository.deleteById(7000);
            shareRepository.deleteById(7001);
            shareRepository.deleteById(7002);
            shareRepository.deleteById(7003);
            settingRepository.save(new Setting("fix_share_id", ""));
        }

        if (!settingRepository.existsByName("fix_share_id1")) {
            shareRepository.deleteById(9900);
            shareRepository.deleteById(9901);
            shareRepository.deleteById(9902);
            settingRepository.save(new Setting("fix_share_id1", ""));
        }

        int id = offset;
        try {
            var resource = new ClassPathResource("shares.txt");
            String lines = resource.getContentAsString(StandardCharsets.UTF_8);
            for (String line : lines.split("\n")) {
                Share share = importShare(line, id++);
                if (share != null) {
                    shares.add(share);
                }
            }
        } catch (IOException e) {
            log.warn("load shares.txt failed", e);
        }

        if (driverAccountService.countByType(DriverType.PAN115) > 0) {
            try {
                var resource = new ClassPathResource("115_shares.txt");
                String lines = resource.getContentAsString(StandardCharsets.UTF_8);
                for (String line : lines.split("\n")) {
                    Share share = importShare(line, id++);
                    if (share != null) {
                        shares.add(share);
                    }
                }
            } catch (IOException e) {
                log.warn("load 115_shares.txt failed", e);
            }
            metaRepository.enableByTid(8);
        } else {
            log.info("disable 115 shares data");
            metaRepository.disableByTid(8);
        }

        log.info("load {} shares", shares.size());
        return shares;
    }

    private Share importShare(String line, int id) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length > 1) {
            try {
                Share share = new Share();
                share.setId(id);
                share.setPath(parts[0]);
                String[] sid = parts[1].split(":", 2);
                if (sid.length > 1) {
                    share.setType(Integer.parseInt(sid[0]));
                    share.setShareId(sid[1]);
                } else {
                    share.setType(0);
                    share.setShareId(sid[0]);
                }
                if (parts.length > 2) {
                    share.setFolderId(parts[2]);
                }
                if (parts.length > 3) {
                    share.setPassword(parts[3]);
                }
                return shareRepository.save(share);
            } catch (Exception e) {
                log.warn("{}", e.getMessage());
            }
        }
        return null;
    }

}
