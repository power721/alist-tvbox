package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.ShareInfo;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static cn.har01d.alist_tvbox.util.Constants.OPEN_TOKEN_URL;

@Slf4j
@Service
@Profile("xiaoya")
public class ShareService {

    private final ObjectMapper objectMapper;
    private final ShareRepository shareRepository;
    private final SettingRepository settingRepository;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final AListLocalService aListLocalService;
    private final RestTemplate restTemplate;

    private volatile int shareId = 5000;

    public ShareService(ObjectMapper objectMapper,
                        ShareRepository shareRepository,
                        SettingRepository settingRepository,
                        AccountRepository accountRepository,
                        AccountService accountService,
                        AListLocalService aListLocalService,
                        RestTemplateBuilder builder) {
        this.objectMapper = objectMapper;
        this.shareRepository = shareRepository;
        this.settingRepository = settingRepository;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.aListLocalService = aListLocalService;
        this.restTemplate = builder.build();
    }

    @PostConstruct
    public void setup() {
        updateAListDriverType();
        loadOpenTokenUrl();

        List<Share> list = shareRepository.findAll();
        if (list.isEmpty()) {
            list = loadSharesFromFile();
        }

        loadAListShares(list);

        if (accountRepository.count() > 0) {
            aListLocalService.startAListServer();
        }
    }

    private void loadOpenTokenUrl() {
        try {
            Path path = Paths.get("/opt/alist/data/config.json");
            if (Files.exists(path)) {
                String text = Files.readString(path);
                Map<String, Object> json = objectMapper.readValue(text, Map.class);
                settingRepository.save(new Setting(OPEN_TOKEN_URL, (String) json.get("opentoken_auth_url")));
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
                    if (parts.length == 3) {
                        try {
                            Share share = new Share();
                            share.setId(shareId++);
                            share.setPath(parts[0]);
                            share.setShareId(parts[1]);
                            share.setFolderId(parts[2]);
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

    public int importShares(MultipartFile file) throws IOException {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()))) {
            log.info("import share list from file");
            while (reader.ready()) {
                String line = reader.readLine();
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 3) {
                    try {
                        Share share = new Share();
                        share.setId(shareId);
                        share.setPath(parts[0]);
                        share.setShareId(parts[1]);
                        share.setFolderId(parts[2]);
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
        }

        log.info("loaded {} shares", count);
        return count;
    }

    private void loadAListShares(List<Share> list) {
        if (list.isEmpty()) {
            return;
        }

        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            Account account = accountRepository.findById(1).orElse(new Account());
            for (Share share : list) {
                try {
                    String sql = "INSERT INTO x_storages VALUES(%d,\"%s\",0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                    int count = statement.executeUpdate(String.format(sql, share.getId(), getMountPath(share.getPath()), account.getRefreshToken(), account.getOpenToken(), account.getFolderId(), share.getShareId(), share.getPassword(), share.getFolderId()));
                    log.info("insert {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share.getPath()), count);
                    shareId = Math.max(shareId, share.getId() + 1);
                } catch (Exception e) {
                    log.warn("{}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void updateAListDriverType() {
        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            log.info("update storage driver type");
            statement.executeUpdate("update x_storages set driver = 'AliyundriveShare2Open' where driver = 'AliyundriveShare'");
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    private String getMountPath(String path) {
        if (path.startsWith("/")) {
            return path;
        }
        return "\uD83C\uDE34我的阿里分享/" + path;
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
        Account account = accountRepository.findById(1).orElseThrow(BadRequestException::new);
        parseShare(share);

        String token = accountService.login();
        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            share.setId(shareId++);
            shareRepository.save(share);

            String sql = "INSERT INTO x_storages VALUES(%d,\"%s\",0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
            statement.executeUpdate(String.format(sql, share.getId(), getMountPath(share.getPath()), account.getRefreshToken(), account.getOpenToken(), account.getFolderId(), share.getShareId(), share.getPassword(), share.getFolderId()));

            enableStorage(share.getId(), token);
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
        return share;
    }

    public Share update(Integer id, Share share) {
        aListLocalService.validateAListStatus();
        validate(share);
        Account account = accountRepository.findById(1).orElseThrow(BadRequestException::new);
        parseShare(share);

        share.setId(id);
        shareRepository.save(share);

        String token = accountService.login();
        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            deleteStorage(id, token);

            String sql = "INSERT INTO x_storages VALUES(%d,\"%s\",0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
            statement.executeUpdate(String.format(sql, id, getMountPath(share.getPath()), account.getRefreshToken(), account.getOpenToken(), account.getFolderId(), share.getShareId(), share.getPassword(), share.getFolderId()));

            enableStorage(id, token);
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
        return share;
    }

    private void validate(Share share) {
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

    private void enableStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/storage/enable?id=" + id, HttpMethod.POST, entity, String.class);
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

    private void deleteStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/storage/delete?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("delete storage response: {}", response.getBody());
    }

    public Page<ShareInfo> listResources(Pageable pageable) {
        int total = 0;
        List<ShareInfo> list = new ArrayList<>();
        int size = pageable.getPageSize();
        int offset = pageable.getPageNumber() * size;
        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            String sql = "select count(*) from x_storages where driver = 'AliyundriveShare2Open'";
            ResultSet rs = statement.executeQuery(sql);
            total = rs.getInt(1);
            sql = "select * from x_storages where driver = 'AliyundriveShare2Open' LIMIT " + size + " OFFSET " + offset;
            rs = statement.executeQuery(sql);
            while (rs.next()) {
                ShareInfo shareInfo = new ShareInfo();
                shareInfo.setId(rs.getInt("id"));
                shareInfo.setPath(rs.getString("mount_path"));
                shareInfo.setStatus(rs.getString("status"));
                String addition = rs.getString("addition");
                if (StringUtils.isNotBlank(addition)) {
                    Map<String, String> map = objectMapper.readValue(addition, Map.class);
                    shareInfo.setShareId(map.get("share_id"));
                    shareInfo.setPassword(map.get("share_pwd"));
                    shareInfo.setFolderId(map.get("root_folder_id"));
                }
                list.add(shareInfo);
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }

        return new PageImpl<>(list, pageable, total);
    }

    public Object listStorages(Pageable pageable) {
        aListLocalService.validateAListStatus();
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(accountService.login()));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(null, headers);
        ResponseEntity<Object> response = restTemplate.exchange("http://localhost:5244/api/admin/storage/list?page=" + pageable.getPageNumber() + "&per_page=" + pageable.getPageSize(), HttpMethod.GET, entity, Object.class);
        return response.getBody();
    }

}
