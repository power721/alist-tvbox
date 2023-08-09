package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.SharesDto;
import cn.har01d.alist_tvbox.entity.AListAlias;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.PikPakAccount;
import cn.har01d.alist_tvbox.entity.PikPakAccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static cn.har01d.alist_tvbox.util.Constants.OPEN_TOKEN_URL;

@Slf4j
@Service

public class ShareService {

    private final ObjectMapper objectMapper;
    private final ShareRepository shareRepository;
    private final AListAliasRepository aliasRepository;
    private final SettingRepository settingRepository;
    private final AccountRepository accountRepository;
    private final PikPakAccountRepository pikPakAccountRepository;
    private final AccountService accountService;
    private final AListLocalService aListLocalService;
    private final ConfigFileService configFileService;
    private final PikPakService pikPakService;
    private final RestTemplate restTemplate;

    private volatile int shareId = 5000;

    public ShareService(ObjectMapper objectMapper,
                        ShareRepository shareRepository,
                        AListAliasRepository aliasRepository,
                        SettingRepository settingRepository,
                        AccountRepository accountRepository,
                        PikPakAccountRepository pikPakAccountRepository,
                        AppProperties appProperties,
                        AccountService accountService,
                        AListLocalService aListLocalService,
                        ConfigFileService configFileService,
                        PikPakService pikPakService,
                        RestTemplateBuilder builder) {
        this.objectMapper = objectMapper;
        this.shareRepository = shareRepository;
        this.aliasRepository = aliasRepository;
        this.settingRepository = settingRepository;
        this.accountRepository = accountRepository;
        this.pikPakAccountRepository = pikPakAccountRepository;
        this.accountService = accountService;
        this.aListLocalService = aListLocalService;
        this.configFileService = configFileService;
        this.pikPakService = pikPakService;
        this.restTemplate = builder.rootUri("http://localhost:" + (appProperties.isHostmode() ? "5234" : "5244")).build();
    }

    @PostConstruct
    public void setup() {
        updateAListDriverType();
        loadOpenTokenUrl();

        pikPakService.readPikPak();

        List<Share> list = shareRepository.findAll();
        if (list.isEmpty()) {
            list = loadSharesFromFile();
        }

        loadAListShares(list);
        loadAListAlias();
        pikPakService.loadPikPak();
        configFileService.writeFiles();
        readTvTxt();

        if (accountRepository.count() > 0 || pikPakAccountRepository.count() > 0) {
            aListLocalService.startAListServer();
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
                    String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Alias',0,'work','{\"paths\":\"%s\"}','','2023-06-20 12:00:00+00:00',0,'name','asc','front',0,'302_redirect','');";
                    int count = Utils.executeUpdate(String.format(sql, alias.getId(), alias.getPath(), Utils.getPaths(alias.getContent())));
                    log.info("insert Alias {}: {}, result: {}", alias.getId(), alias.getPath(), count);
                } catch (Exception e) {
                    log.warn("{}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void loadOpenTokenUrl() {
        try {
            String url = null;
            try {
                Path file = Paths.get("/data/open_token_url.txt");
                if (Files.exists(file)) {
                    url = Files.readString(file).trim();
                    log.debug("loadOpenTokenUrl {}", url);
                    settingRepository.save(new Setting(OPEN_TOKEN_URL, url));
                }
            } catch (Exception e) {
                log.warn("", e);
            }

            Path path = Paths.get("/opt/alist/data/config.json");
            if (Files.exists(path)) {
                String text = Files.readString(path);
                Map<String, Object> json = objectMapper.readValue(text, Map.class);
                if (url != null) {
                    json.put("opentoken_auth_url", url);
                    text = objectMapper.writeValueAsString(json);
                    Files.writeString(path, text);
                } else {
                    settingRepository.save(new Setting(OPEN_TOKEN_URL, (String) json.get("opentoken_auth_url")));
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    public void updateOpenTokenUrl(String url) {
        try {
            Path path = Paths.get("/opt/alist/data/config.json");
            if (Files.exists(path)) {
                String text = Files.readString(path);
                Map<String, Object> json = objectMapper.readValue(text, Map.class);
                json.put("opentoken_auth_url", url);
                settingRepository.save(new Setting(OPEN_TOKEN_URL, url));
                text = objectMapper.writeValueAsString(json);
                Files.writeString(path, text);
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        try {
            Path file = Paths.get("/data/open_token_url.txt");
            Files.writeString(file, url);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private List<Share> loadSharesFromFile() {
        List<Share> list = new ArrayList<>();
        Path path = Paths.get("/data/alishare_list.txt");
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
        Path path = Paths.get("/data/pikpakshare_list.txt");
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
                    share.setPath(parts[0]);
                    share.setShareId(parts[1]);
                    if (parts.length > 2) {
                        share.setFolderId(parts[2]);
                    } else if (share.getType() == 1) {
                        share.setFolderId("");
                    }
                    share.setType(dto.getType());
                    share.setPath(getMountPath(share));
                    if (shareRepository.existsByPath(share.getPath())) {
                        continue;
                    }
                    create(share);
                    count++;
                    shareId++;
                } catch (Exception e) {
                    log.warn("{}", e.getMessage());
                }
            }
        }

        log.info("loaded {} shares", count);
        return count;
    }

    public String exportShare(HttpServletResponse response, int type) {
        List<Share> list = shareRepository.findByType(type);
        StringBuilder sb = new StringBuilder();
        String fileName;
        if (type == 1) {
            fileName = "pikpakshare_list.txt";
        } else {
            fileName = "alishare_list.txt";
        }

        for (Share share : list) {
            sb.append(share.getPath()).append("  ").append(share.getShareId()).append("  ").append(share.getFolderId()).append("\n");
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
            Account account1 = accountRepository.getFirstByMasterTrue().orElse(new Account());
            PikPakAccount account2 = pikPakAccountRepository.getFirstByMasterTrue().orElse(new PikPakAccount());
            for (Share share : list) {
                try {
                    if (share.getType() == null || share.getType() == 0) {
                        String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"root\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                        int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), account1.getRefreshToken(), account1.getOpenToken(), share.getShareId(), share.getPassword(), share.getFolderId()));
                        log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                    } else if (share.getType() == 1) {
                        String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'PikPakShare',30,'work','{\"root_folder_id\":\"%s\",\"username\":\"%s\",\"password\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                        int count = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getFolderId(), account2.getUsername(), account2.getPassword(), share.getShareId(), share.getPassword()));
                        pikpak = true;
                        log.info("insert Share {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share), count);
                    }
                    shareId = Math.max(shareId, share.getId() + 1);
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

    private void updateAListDriverType() {
        try {
            log.info("update storage driver type");
            Utils.executeUpdate("update x_storages set driver = 'AliyundriveShare2Open' where driver = 'AliyundriveShare'");
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    private String getMountPath(Share share) {
        String path = share.getPath();
        if (path.startsWith("/")) {
            return path;
        }
        if (share.getType() == null || share.getType() == 0) {
            return "/\uD83C\uDE34我的阿里分享/" + path;
        } else if (share.getType() == 1) {
            return "/\uD83D\uDD78️我的PikPak分享/" + path;
        }
        return path;
    }

    private void readTvTxt() {
        Path file = Paths.get("/data/tv.txt");
        if (Files.exists(file)) {
            log.info("read tv from file");
            try {
                StringBuilder sb = parseTvFile(file);
                Utils.executeUpdate("INSERT INTO x_storages VALUES(2050,'/\uD83C\uDDF9\uD83C\uDDFB直播/我的自选',0,'UrlTree',0,'work','{\"url_structure\":\"" + sb + "\",\"head_size\":false}','','2023-06-20 12:00:00+00:00',0,'name','','',0,'302_redirect','');");
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

    public Page<Share> list(Pageable pageable) {
        return shareRepository.findAll(pageable);
    }

    private void parseShare(Share share) {
        String url = share.getShareId();
        if (url.startsWith("https://www.aliyundrive.com/s/")) {
            url = url.substring(30);
        }
        String[] parts = url.split("/");
        if (parts.length == 3 && "folder".equals(parts[1])) {
            share.setShareId(parts[0]);
            share.setFolderId(parts[2]);
        } else {
            share.setShareId(parts[0]);
        }
    }

    public Share create(Share share) {
        aListLocalService.validateAListStatus();
        validate(share);
        parseShare(share);

        try {
            String token = accountService.login();
            share.setId(shareId++);

            int result = 0;
            if (share.getType() == null || share.getType() == 0) {
                Account account = accountRepository.getFirstByMasterTrue().orElseThrow(BadRequestException::new);
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"root\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0);";
                result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), account.getRefreshToken(), account.getOpenToken(), share.getShareId(), share.getPassword(), share.getFolderId()));
            } else if (share.getType() == 1) {
                PikPakAccount account = pikPakAccountRepository.getFirstByMasterTrue().orElseThrow(BadRequestException::new);
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'PikPakShare',30,'work','{\"root_folder_id\":\"%s\",\"username\":\"%s\",\"password\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0);";
                result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getFolderId(), account.getUsername(), account.getPassword(), share.getShareId(), share.getPassword()));
            }
            log.info("insert result: {}", result);

            shareRepository.save(share);

            enableStorage(share.getId(), token);
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

        share.setId(id);
        shareRepository.save(share);

        String token = accountService.login();
        try {
            deleteStorage(id, token);

            int result = 0;
            if (share.getType() == null || share.getType() == 0) {
                Account account = accountRepository.getFirstByMasterTrue().orElseThrow(BadRequestException::new);
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"root\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0);";
                result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), account.getRefreshToken(), account.getOpenToken(), share.getShareId(), share.getPassword(), share.getFolderId()));
            } else if (share.getType() == 1) {
                PikPakAccount account = pikPakAccountRepository.getFirstByMasterTrue().orElseThrow(BadRequestException::new);
                String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'PikPakShare',30,'work','{\"root_folder_id\":\"%s\",\"username\":\"%s\",\"password\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','',0);";
                result = Utils.executeUpdate(String.format(sql, share.getId(), getMountPath(share), share.getFolderId(), account.getUsername(), account.getPassword(), share.getShareId(), share.getPassword()));
            }
            log.info("insert result: {}", result);

            enableStorage(id, token);
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
        return share;
    }

    private void validate(Share share) {
        if (share.getType() == 1 && (StringUtils.isBlank(share.getFolderId()))) {
            throw new BadRequestException("文件夹ID不能为空");
        }
        if (StringUtils.isBlank(share.getShareId())) {
            throw new BadRequestException("分享ID不能为空");
        }
        if (StringUtils.isBlank(share.getPath())) {
            throw new BadRequestException("挂载路径不能为空");
        }
        if (StringUtils.isBlank(share.getFolderId())) {
            share.setFolderId("root");
        }
    }

    public void enableStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/storage/enable?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("enable storage response: {}", response.getBody());
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
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/storage/delete?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("delete storage response: {}", response.getBody());
    }

    private Integer getType(String driver) {
        switch (driver) {
            case "PikPakShare":
                return 1;
            case "Quark":
                return 2;
            default:
                return 0;
        }
    }

    public Object listStorages(Pageable pageable) {
        aListLocalService.validateAListStatus();
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(accountService.login()));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(null, headers);
        ResponseEntity<Object> response = restTemplate.exchange("/api/admin/storage/list?page=" + pageable.getPageNumber() + "&per_page=" + pageable.getPageSize(), HttpMethod.GET, entity, Object.class);
        return response.getBody();
    }

}
