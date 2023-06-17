package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.ShareInfo;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.LoginRequest;
import cn.har01d.alist_tvbox.model.LoginResponse;
import cn.har01d.alist_tvbox.util.IdUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ShareService {
    public static final String DB_URL = "jdbc:sqlite:/opt/alist/data/data.db";
    private final Environment environment;
    private final ObjectMapper objectMapper;
    private final ShareRepository shareRepository;
    private final SettingRepository settingRepository;
    private final RestTemplate restTemplate;

    private String accessToken;
    private String openToken;
    private String folderId;

    public ShareService(Environment environment, ObjectMapper objectMapper, ShareRepository shareRepository, SettingRepository settingRepository, RestTemplateBuilder builder) {
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.shareRepository = shareRepository;
        this.settingRepository = settingRepository;
        this.restTemplate = builder.build();
    }

    @PostConstruct
    public void setup() {
        if (getProfiles().contains("xiaoya")) {
            readAccessToken();
            readOpenToken();
            readFolderId();
            List<Share> list = shareRepository.findAll();
            new Thread(() -> loadShares(list)).start();
        }
    }

    private void loadShares(List<Share> list) {
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(DB_URL);
            Statement statement = connection.createStatement();
            String sql = "select count(*) from x_storages where driver = 'AliyundriveShare2Open'";
            for (int i = 0; i < 10000; ++i) {
                ResultSet rs = statement.executeQuery(sql);
                if (rs.getInt(1) > 0) {
                    break;
                }
            }

            for (Share share : list) {
                sql = "INSERT INTO x_storages VALUES(%d,\"%s\",0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                int count = statement.executeUpdate(String.format(sql, share.getId(), getMountPath(share.getPath()), accessToken, openToken, folderId, share.getShareId(), share.getPassword(), share.getFolderId()));
                log.info("insert {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share.getPath()), count);
            }

            sql = "INSERT INTO x_users VALUES(4,'atv',\"" + generatePassword() + "\",'/',2,258,'',0,0);";
            statement.executeUpdate(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // ignore
                }
            }
        }

    }

    private String generatePassword() {
        Setting setting = settingRepository.findById("user_password").orElse(null);
        if (setting == null) {
            log.info("generate new password");
            setting = new Setting("user_password", IdUtils.generate(12));
            settingRepository.save(setting);
        }
        return setting.getValue();
    }

    private String getMountPath(String path) {
        if (path.startsWith("/")) {
            return path;
        }
        return "\uD83C\uDE34我的阿里分享/" + path;
    }

    private void readAccessToken() {
        Path path = Paths.get("/data/mytoken.txt");
        if (Files.exists(path)) {
            try {
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    String token = lines.get(0).trim();
                    settingRepository.save(new Setting("access_token", token));
                    accessToken = token;
                }
            } catch (IOException e) {
                log.warn("", e);
            }
        }
    }

    private void readOpenToken() {
        Path path = Paths.get("/data/myopentoken.txt");
        if (Files.exists(path)) {
            try {
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    String token = lines.get(0).trim();
                    settingRepository.save(new Setting("open_token", token));
                    openToken = token;
                }
            } catch (IOException e) {
                log.warn("", e);
            }
        }
    }

    private void readFolderId() {
        Path path = Paths.get("/data/temp_transfer_folder_id.txt");
        if (Files.exists(path)) {
            try {
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    String token = lines.get(0).trim();
                    settingRepository.save(new Setting("folder_id", token));
                    folderId = token;
                }
            } catch (IOException e) {
                log.warn("", e);
            }
        }
    }

    public Page<Share> list(Pageable pageable) {
        return shareRepository.findAll(pageable);
    }

    public Share create(Share share) {
        validate(share);

        Connection connection = null;
        try {
            connection = DriverManager.getConnection(DB_URL);
            Statement statement = connection.createStatement();
            String sql = "select max(id) from x_storages";
            ResultSet rs = statement.executeQuery(sql);
            int id = rs.getInt(1) + 1;

            share.setId(id);
            shareRepository.save(share);

            // TODO: use AList API
            sql = "INSERT INTO x_storages VALUES(%d,\"%s\",0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
            int count = statement.executeUpdate(String.format(sql, id, getMountPath(share.getPath()), accessToken, openToken, folderId, share.getShareId(), share.getPassword(), share.getFolderId()));

            enableStorage(id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // ignore
                }
            }
        }
        return share;
    }

    public Share update(Integer id, Share share) {
        validate(share);

        share.setId(id);
        shareRepository.save(share);

        if (StringUtils.isAnyBlank(accessToken, openToken, folderId)) {
            throw new BadRequestException("token参数缺失");
        }

        Connection connection = null;

        try {
            connection = DriverManager.getConnection(DB_URL);
            Statement statement = connection.createStatement();
            deleteStorage(id);
            // TODO: use AList API
            String sql = "INSERT INTO x_storages VALUES(%d,\"%s\",0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
            int count = statement.executeUpdate(String.format(sql, id, getMountPath(share.getPath()), accessToken, openToken, folderId, share.getShareId(), share.getPassword(), share.getFolderId()));

            enableStorage(id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // ignore
                }
            }
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
        if (StringUtils.isAnyBlank(accessToken, openToken, folderId)) {
            throw new BadRequestException("token参数缺失");
        }
        if (StringUtils.isBlank(share.getFolderId())) {
            share.setFolderId("root");
        }
    }

    private String login() {
        String username = "atv";
        String password = settingRepository.findById("user_password").map(Setting::getValue).orElseThrow(BadRequestException::new);
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        LoginResponse response = restTemplate.postForObject("http://localhost:5244/api/auth/login", request, LoginResponse.class);
        return response.getData().getToken();
    }

    private void enableStorage(Integer id) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(login()));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/storage/enable?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("enable storage response: {}", response.getBody());
    }

    public void delete(Integer id) {
        shareRepository.deleteById(id);
        deleteStorage(id);
    }

    private void deleteStorage(Integer id) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(login()));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/storage/delete?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("delete storage response: {}", response.getBody());
    }

    public List<String> getProfiles() {
        return Arrays.asList(environment.getActiveProfiles());
    }

    public Page<ShareInfo> listResources(Pageable pageable) {
        if (!getProfiles().contains("xiaoya")) {
            return new PageImpl<>(new ArrayList<>());
        }

        int total = 0;
        List<ShareInfo> list = new ArrayList<>();
        Connection connection = null;
        int size = pageable.getPageSize();
        int offset = pageable.getPageNumber() * size;
        try {
            connection = DriverManager.getConnection(DB_URL);
            Statement statement = connection.createStatement();
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
            throw new RuntimeException(e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // ignore
                }
            }
        }

        return new PageImpl<>(list, pageable, total);
    }
}
