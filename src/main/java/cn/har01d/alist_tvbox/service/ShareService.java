package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.AListLogin;
import cn.har01d.alist_tvbox.dto.CheckinResponse;
import cn.har01d.alist_tvbox.dto.CheckinResult;
import cn.har01d.alist_tvbox.dto.RewardResponse;
import cn.har01d.alist_tvbox.dto.ShareInfo;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Share;
import cn.har01d.alist_tvbox.entity.ShareRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.model.AListUser;
import cn.har01d.alist_tvbox.model.LoginRequest;
import cn.har01d.alist_tvbox.model.LoginResponse;
import cn.har01d.alist_tvbox.model.SettingResponse;
import cn.har01d.alist_tvbox.model.StorageInfo;
import cn.har01d.alist_tvbox.model.UserResponse;
import cn.har01d.alist_tvbox.util.IdUtils;
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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@Profile("xiaoya")
public class ShareService {
    public static final String DB_URL = "jdbc:sqlite:/opt/alist/data/data.db";
    private final ObjectMapper objectMapper;
    private final ShareRepository shareRepository;
    private final SettingRepository settingRepository;
    private final RestTemplate restTemplate;
    private final TaskScheduler scheduler;
    private ScheduledFuture scheduledFuture;

    private String refreshToken;
    private String openToken;
    private String folderId;
    private volatile boolean started;
    private volatile int shareId = 5000;

    public ShareService(ObjectMapper objectMapper,
                        ShareRepository shareRepository,
                        SettingRepository settingRepository,
                        RestTemplateBuilder builder,
                        TaskScheduler scheduler) {
        this.objectMapper = objectMapper;
        this.shareRepository = shareRepository;
        this.settingRepository = settingRepository;
        this.restTemplate = builder.build();
        this.scheduler = scheduler;
    }

    @PostConstruct
    public void setup() {
        scheduleAutoCheckinTime();

        readAccessToken();
        readOpenToken();
        readFolderId();

        boolean auto = settingRepository.findById("auto_checkin").map(Setting::getValue).map(Boolean::valueOf).orElse(false);
        if (!auto) {
            new Thread(() -> checkin(false)).start();
        }

        List<Share> list = shareRepository.findAll();
        if (list.isEmpty()) {
            list = loadShares();
        }
        loadAList(list);
    }

    private void scheduleAutoCheckinTime() {
        try {
            LocalTime localTime = getScheduleTime();
            log.info("schedule time: {}", localTime);
            scheduledFuture = scheduler.schedule(this::autoCheckin, new CronTrigger(String.format("%d %d %d * * ?", localTime.getSecond(), localTime.getMinute(), localTime.getHour())));
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private LocalTime getScheduleTime() {
        String time = settingRepository.findById("schedule_time").map(Setting::getValue).orElse(null);
        LocalTime localTime = LocalTime.of(9, 0, 0);
        if (time != null) {
            try {
                localTime = LocalTime.parse(time);
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        return localTime;
    }

    public void autoCheckin() {
        boolean auto = settingRepository.findById("auto_checkin").map(Setting::getValue).map(Boolean::valueOf).orElse(false);
        if (auto) {
            log.info("auto checkin");
            try {
                checkin(true);
            } catch (Exception e) {
                log.warn("", e);
            }
        }

        refreshTokens();
    }

    private void refreshTokens() {
        Instant now = Instant.now();
        Instant time;
        try {
            time = settingRepository.findById("open_token_time").map(Setting::getValue).map(Instant::parse).orElse(null);
            if (time == null || time.plus(24, ChronoUnit.HOURS).isBefore(now)) {
                if (openToken != null) {
                    log.info("update open token: {}", time);
                    openToken = getAliOpenToken(openToken);
                    settingRepository.save(new Setting("open_token_time", Instant.now().toString()));
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        try {
            time = settingRepository.findById("refresh_token_time").map(Setting::getValue).map(Instant::parse).orElse(null);
            if (time == null || time.plus(24, ChronoUnit.HOURS).isBefore(now)) {
                if (refreshToken != null) {
                    log.info("update refresh token: {}", time);
                    refreshToken = (String) getAliToken(refreshToken).get("refresh_token");
                    settingRepository.save(new Setting("refresh_token_time", Instant.now().toString()));
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private List<Share> loadShares() {
        List<Share> list = new ArrayList<>();

        Path path = Paths.get("/data/alishare_list.txt");
        if (Files.exists(path)) {
            try {
                log.info("loading share list from file");
                List<String> lines = Files.readAllLines(path);
                for (String line : lines) {
                    String[] parts = line.trim().split("\\s+", 3);
                    Share share = new Share();
                    share.setId(shareId++);
                    share.setPath(parts[0]);
                    share.setShareId(parts[1]);
                    share.setFolderId(parts[2]);
                    list.add(share);
                }
                shareRepository.saveAll(list);
            } catch (IOException e) {
                log.warn("", e);
            }
        }

        return list;
    }

    private void loadAList(List<Share> list) {
        if (list.isEmpty()) {
            return;
        }

        Connection connection = null;
        try {
            connection = DriverManager.getConnection(DB_URL);
            Statement statement = connection.createStatement();
            String sql = "";
            for (Share share : list) {
                sql = "INSERT INTO x_storages VALUES(%d,\"%s\",0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                int count = statement.executeUpdate(String.format(sql, share.getId(), getMountPath(share.getPath()), refreshToken, openToken, folderId, share.getShareId(), share.getPassword(), share.getFolderId()));
                log.info("insert {} {}: {}, result: {}", share.getId(), share.getShareId(), getMountPath(share.getPath()), count);
                shareId++;
            }

            sql = "INSERT INTO x_users VALUES(4,'atv',\"" + generatePassword() + "\",'/',2,258,'',0,0);";
            statement.executeUpdate(sql);

            try {
                enableMyAli(connection);
            } catch (Exception e) {
                log.warn("", e);
            }

            try {
                enableLogin(connection);
            } catch (Exception e) {
                log.warn("", e);
            }

            if (!StringUtils.isAnyBlank(openToken, refreshToken, folderId)) {
                try {
                    updateTokens(connection);
                } catch (Exception e) {
                    log.warn("", e);
                }
                startAListServer(true);
            }
        } catch (Exception e) {
            log.warn("", e);
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

    private void updateTokens(Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        statement.executeUpdate("update x_storages set driver = 'AliyundriveShare2Open' where driver = 'AliyundriveShare'");
        statement.executeUpdate("update x_storages set addition = json_set(addition, '$.RefreshToken', '" + refreshToken + "') where driver = 'AliyundriveShare2Open'");
        statement.executeUpdate("update x_storages set addition = json_set(addition, '$.RefreshTokenOpen', '" + openToken + "') where driver = 'AliyundriveShare2Open'");
        statement.executeUpdate("update x_storages set addition = json_set(addition, '$.TempTransferFolderID', '" + folderId + "') where driver = 'AliyundriveShare2Open'");
        log.info("update tokens to AList");
    }

    private void startAListServer(boolean wait) {
        try {
            log.info("start AList server");
            ProcessBuilder builder = new ProcessBuilder();
            builder.inheritIO();
            builder.command("/opt/alist/alist", "server", "--no-prefix");
            builder.directory(new File("/opt/alist"));
            Process process = builder.start();
            if (wait) {
                process.waitFor(30, TimeUnit.SECONDS);
                waitAListStart();
            }
            log.info("AList server started");
            started = true;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void waitAListStart() throws InterruptedException {
        for (int i = 0; i < 60; ++i) {
            ResponseEntity<SettingResponse> response = restTemplate.getForEntity("http://localhost:5244/api/public/settings", SettingResponse.class);
            if (response.getBody() != null && response.getBody().getCode() == 200) {
                break;
            }
            Thread.sleep(500);
        }
    }

    private void enableMyAli(Connection connection) {
        String show = settingRepository.findById("show_my_ali").map(Setting::getValue).orElse("");
        if (show.equals("true") || show.equals("false")) {
            boolean enabled = Boolean.valueOf(show);

            try {
                String sql;
                Statement statement = connection.createStatement();
                if (enabled) {
                    sql = "INSERT INTO x_storages VALUES(10000,'/\uD83D\uDCC0我的阿里云盘',0,'AliyundriveOpen',30,'work','{\"root_folder_id\":\"root\",\"refresh_token\":\"" + openToken + "\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                    log.info("add storage \uD83D\uDCC0我的阿里云盘");
                } else {
                    sql = "DELETE FROM x_storages WHERE id = 10000;";
                    log.info("remove storage \uD83D\uDCC0我的阿里云盘");
                }
                statement.executeUpdate(sql);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void enableLogin(Connection connection) {
        AListLogin login = new AListLogin();
        login.setEnabled(settingRepository.findById("alist_login").map(Setting::getValue).orElse("").equals("true"));
        login.setUsername(settingRepository.findById("alist_username").map(Setting::getValue).orElse(""));
        login.setPassword(settingRepository.findById("alist_password").map(Setting::getValue).orElse(""));

        try {
            Statement statement = connection.createStatement();
            String sql = "";
            if (login.isEnabled()) {
                sql = "update x_users set disabled = 1 where id = 2";
                statement.executeUpdate(sql);
                sql = "INSERT INTO x_users VALUES(3,'" + login.getUsername() + "','" + login.getPassword() + "','/',0,368,'',0,0);";
                statement.executeUpdate(sql);
            } else {
                sql = "update x_users set disabled = 0, permission = '368' where id = 2;";
                statement.executeUpdate(sql);
                sql = "delete from x_users where id = 3;";
                statement.executeUpdate(sql);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        log.info("{} user {}", login.isEnabled() ? "enable" : "disable", login.getUsername());
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
        refreshToken = settingRepository.findById("refresh_token").map(Setting::getValue).orElse(null);
        if (refreshToken != null) {
            return;
        }

        Path path = Paths.get("/data/mytoken.txt");
        if (Files.exists(path)) {
            try {
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    String token = lines.get(0).trim();
                    settingRepository.save(new Setting("refresh_token", token));
                    refreshToken = token;
                }
            } catch (IOException e) {
                log.warn("", e);
            }
        }
    }

    private void readOpenToken() {
        openToken = settingRepository.findById("open_token").map(Setting::getValue).orElse(null);
        if (openToken != null) {
            return;
        }

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
        folderId = settingRepository.findById("folder_id").map(Setting::getValue).orElse(null);
        if (folderId != null) {
            return;
        }

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
        String token = login();
        try {
            connection = DriverManager.getConnection(DB_URL);
            Statement statement = connection.createStatement();
            share.setId(shareId++);
            shareRepository.save(share);

            String sql = "INSERT INTO x_storages VALUES(%d,\"%s\",0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
            statement.executeUpdate(String.format(sql, share.getId(), getMountPath(share.getPath()), refreshToken, openToken, folderId, share.getShareId(), share.getPassword(), share.getFolderId()));

            enableStorage(share.getId(), token);
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

        if (StringUtils.isAnyBlank(refreshToken, openToken, folderId)) {
            throw new BadRequestException("token参数缺失");
        }

        Connection connection = null;

        String token = login();
        try {
            connection = DriverManager.getConnection(DB_URL);
            Statement statement = connection.createStatement();

            deleteStorage(id, token);

            String sql = "INSERT INTO x_storages VALUES(%d,\"%s\",0,'AliyundriveShare2Open',30,'work','{\"RefreshToken\":\"%s\",\"RefreshTokenOpen\":\"%s\",\"TempTransferFolderID\":\"%s\",\"share_id\":\"%s\",\"share_pwd\":\"%s\",\"root_folder_id\":\"%s\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
            statement.executeUpdate(String.format(sql, id, getMountPath(share.getPath()), refreshToken, openToken, folderId, share.getShareId(), share.getPassword(), share.getFolderId()));

            enableStorage(id, token);
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
        if (StringUtils.isAnyBlank(refreshToken, openToken, folderId)) {
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
        log.info("login response: {}", response.getData());
        return response.getData().getToken();
    }

    private void enableStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/storage/enable?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("enable storage response: {}", response.getBody());
    }

    public void delete(Integer id) {
        shareRepository.deleteById(id);
        String token = login();
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
        Connection connection = null;
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

    private AListUser getUser(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<UserResponse> response = restTemplate.exchange("http://localhost:5244/api/admin/user/get?id=" + id, HttpMethod.GET, entity, UserResponse.class);
        log.info("get user {} response: {}", id, response.getBody());
        return response.getBody().getData();
    }

    private void deleteUser(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/user/delete?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("delete user {} response: {}", id, response.getBody());
    }

    private void updateUser(AListUser user, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<AListUser> entity = new HttpEntity<>(user, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/user/update", HttpMethod.POST, entity, String.class);
        log.info("update user {} response: {}", user.getId(), response.getBody());
    }

    private void createUser(AListUser user, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<AListUser> entity = new HttpEntity<>(user, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/user/create", HttpMethod.POST, entity, String.class);
        log.info("create user response: {}", response.getBody());
    }

    public void updateLogin(AListLogin login) {
        if (login.isEnabled()) {
            if (StringUtils.isBlank(login.getUsername())) {
                throw new BadRequestException("缺少用户名");
            }
            if (StringUtils.isBlank(login.getPassword())) {
                throw new BadRequestException("缺少密码");
            }
        }

        settingRepository.save(new Setting("alist_username", login.getUsername()));
        settingRepository.save(new Setting("alist_password", login.getPassword()));
        settingRepository.save(new Setting("alist_login", String.valueOf(login.isEnabled())));

        String token = login();
        AListUser guest = getUser(2, token);
        guest.setDisabled(login.isEnabled());
        updateUser(guest, token);

        deleteUser(3, token);
        if (login.isEnabled()) {
            AListUser user = new AListUser();
            user.setId(3);
            user.setUsername(login.getUsername());
            user.setPassword(login.getPassword());
            createUser(user, token);
        }
    }

    public AListLogin getLoginInfo() {
        String username = settingRepository.findById("alist_username").map(Setting::getValue).orElse("");
        String password = settingRepository.findById("alist_password").map(Setting::getValue).orElse("");
        String enabled = settingRepository.findById("alist_login").map(Setting::getValue).orElse("");
        AListLogin login = new AListLogin();
        login.setUsername(username);
        login.setPassword(password);
        login.setEnabled("true".equals(enabled));
        return login;
    }

    public StorageInfo getStorageInfo() {
        StorageInfo storageInfo = new StorageInfo();
        storageInfo.setRefreshToken(refreshToken);
        storageInfo.setOpenToken(openToken);
        storageInfo.setFolderId(folderId);
        String time = settingRepository.findById("refresh_token_time").map(Setting::getValue).orElse(null);
        if (time != null) {
            storageInfo.setRefreshToken(time);
        }
        time = settingRepository.findById("open_token_time").map(Setting::getValue).orElse(null);
        if (time != null) {
            storageInfo.setOpenTokenTime(time);
        }
        return storageInfo;
    }

    private Map<Object, Object> getAliToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("User-Agent", Collections.singletonList("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"));
        headers.put("Referer", Collections.singletonList("https://www.aliyundrive.com/"));
        Map<String, String> body = new HashMap<>();
        body.put("refresh_token", token);
        body.put("grant_type", "refresh_token");
        log.debug("body: {}", body);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange("https://auth.aliyundrive.com/v2/account/token", HttpMethod.POST, entity, Map.class);
        log.debug("get Ali token response: {}", response.getBody());
        String driveID = (String) response.getBody().get("default_drive_id");
        settingRepository.save(new Setting("ali_drive_id", driveID));
        return response.getBody();
    }

    private String getAliOpenToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("User-Agent", Collections.singletonList("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"));
        headers.put("Referer", Collections.singletonList("https://www.aliyundrive.com/"));
        Map<String, String> body = new HashMap<>();
        body.put("refresh_token", token);
        body.put("grant_type", "refresh_token");
        log.debug("body: {}", body);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.exchange("https://api.xhofe.top/alist/ali_open/token", HttpMethod.POST, entity, Map.class);
        log.debug("get open token response: {}", response.getBody());
        return (String) response.getBody().get("refresh_token");
    }

    public StorageInfo updateStorageInfo(StorageInfo dto) {
        if (StringUtils.isBlank(dto.getRefreshToken())) {
            throw new BadRequestException("阿里token不能为空");
        }
        if (StringUtils.isBlank(dto.getOpenToken())) {
            throw new BadRequestException("开放token不能为空");
        }
        if (StringUtils.isBlank(dto.getFolderId())) {
            throw new BadRequestException("转存文件夹ID不能为空");
        }

        refreshToken = (String) getAliToken(dto.getRefreshToken()).get("refresh_token");
        openToken = getAliOpenToken(dto.getOpenToken());
        folderId = dto.getFolderId();

        String now = saveSettings();
        updateAList();

        if (!started) {
            startAListServer(false);
        }

        dto.setRefreshToken(refreshToken);
        dto.setOpenToken(openToken);
        dto.setFolderId(folderId);
        dto.setRefreshToken(now);
        dto.setOpenTokenTime(now);
        return dto;
    }

    private String saveSettings() {
        String now = Instant.now().toString();
        settingRepository.save(new Setting("refresh_token", refreshToken));
        settingRepository.save(new Setting("refresh_token_time", now));
        settingRepository.save(new Setting("open_token", openToken));
        settingRepository.save(new Setting("open_token_time", now));
        settingRepository.save(new Setting("folder_id", folderId));
        return now;
    }

    private void updateAList() {
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(DB_URL);
            Statement statement = connection.createStatement();
            String sql = "update x_storages set addition = json_set(addition, '$.RefreshToken', '" + refreshToken + "') where driver = 'AliyundriveShare2Open'";
            statement.executeUpdate(String.format(sql));
            sql = "update x_storages set addition = json_set(addition, '$.RefreshTokenOpen', '" + openToken + "') where driver = 'AliyundriveShare2Open'";
            statement.executeUpdate(String.format(sql));
            sql = "update x_storages set addition = json_set(addition, '$.TempTransferFolderID', '" + folderId + "') where driver = 'AliyundriveShare2Open'";
            statement.executeUpdate(String.format(sql));
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

    public Object listStorages(Pageable pageable) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(login()));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(null, headers);
        ResponseEntity<Object> response = restTemplate.exchange("http://localhost:5244/api/admin/storage/list?page=" + pageable.getPageNumber() + "&per_page=" + pageable.getPageSize(), HttpMethod.GET, entity, Object.class);
        return response.getBody();
    }

    public CheckinResult checkin(boolean force) {
        if (!force) {
            validateCheckinTime();
        }

        Map<Object, Object> map = getAliToken(refreshToken);
        String accessToken = (String) map.get("access_token");
        String nickName = (String) map.get("nick_name");

        refreshToken = (String) map.get("refresh_token");
        settingRepository.save(new Setting("refresh_token", refreshToken));
        settingRepository.save(new Setting("refresh_token_time", Instant.now().toString()));

        Map<String, Object> body = new HashMap<>();
        body.put("refresh_token", refreshToken);
        body.put("grant_type", "refresh_token");
        log.debug("body: {}", body);

        HttpHeaders headers = new HttpHeaders();
        headers.put("User-Agent", Collections.singletonList("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"));
        headers.put("Referer", Collections.singletonList("https://www.aliyundrive.com/"));
        headers.put("Authorization", Collections.singletonList("Bearer " + accessToken));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<CheckinResponse> response = restTemplate.exchange("https://member.aliyundrive.com/v1/activity/sign_in_list", HttpMethod.POST, entity, CheckinResponse.class);

        Instant now = Instant.now();
        response.getBody().getResult().setCheckinTime(now);
        settingRepository.save(new Setting("checkin_time", now.toString()));

        for (Map<String, Object> signInLog : response.getBody().getResult().getSignInLogs()) {
            if (signInLog.get("status").equals("normal") && !signInLog.get("isReward").equals(true)) {
                body = new HashMap<>();
                body.put("signInDay", signInLog.get("day"));
                log.debug("body: {}", body);

                headers = new HttpHeaders();
                headers.put("User-Agent", Collections.singletonList("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"));
                headers.put("Referer", Collections.singletonList("https://www.aliyundrive.com/"));
                headers.put("Authorization", Collections.singletonList("Bearer " + accessToken));
                entity = new HttpEntity<>(body, headers);
                ResponseEntity<RewardResponse> res = restTemplate.exchange("https://member.aliyundrive.com/v1/activity/sign_in_reward?_rx-s=mobile", HttpMethod.POST, entity, RewardResponse.class);
                log.info("今日签到获得 {} {}", res.getBody().getResult().getName(), res.getBody().getResult().getDescription());
            }
        }

        log.info("{}  签到成功, 本月累计{}天", nickName, response.getBody().getResult().getSignInCount());
        response.getBody().getResult().setSignInLogs(null);
        return response.getBody().getResult();
    }

    private void validateCheckinTime() {
        Instant checkinTime = getCheckinTime();
        if (checkinTime != null) {
            LocalDate time = checkinTime.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
            if (LocalDate.now().isEqual(time)) {
                throw new BadRequestException("今日已签到");
            }
        }
    }

    public Instant getCheckinTime() {
        return settingRepository.findById("checkin_time").map(Setting::getValue).map(Instant::parse).orElse(null);
    }

    public void showMyAli(boolean enabled) {
        showMyAli(enabled, false);
    }

    public void showMyAli(boolean enabled, boolean start) {
        settingRepository.save(new Setting("show_my_ali", String.valueOf(enabled)));

        String token = start ? "" : login();
        if (!start) {
            deleteStorage(10000, token);
        }

        Connection connection = null;

        try {
            connection = DriverManager.getConnection(DB_URL);
            Statement statement = connection.createStatement();

            if (enabled) {
                String sql = "INSERT INTO x_storages VALUES(10000,'/\uD83D\uDCC0我的阿里云盘',0,'AliyundriveOpen',30,'work','{\"root_folder_id\":\"root\",\"refresh_token\":\"" + openToken + "\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                statement.executeUpdate(sql);
                log.info("add storage \uD83D\uDCC0我的阿里云盘");
                if (!start) {
                    enableStorage(10000, token);
                }
            } else {
                log.info("remove storage \uD83D\uDCC0我的阿里云盘");
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
    }

    public LocalTime updateScheduleTime(Instant time) {
        LocalTime localTime = time.atZone(ZoneId.of("Asia/Shanghai")).toLocalTime();
        settingRepository.save(new Setting("schedule_time", localTime.toString()));
        scheduledFuture.cancel(true);
        scheduledFuture = scheduler.schedule(this::autoCheckin, new CronTrigger(String.format("%d %d %d * * ?", localTime.getSecond(), localTime.getMinute(), localTime.getHour())));
        log.info("update schedule time: {}", localTime);
        return localTime;
    }
}
