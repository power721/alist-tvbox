package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.dto.AListLogin;
import cn.har01d.alist_tvbox.dto.AccountDto;
import cn.har01d.alist_tvbox.dto.AliBatchRequest;
import cn.har01d.alist_tvbox.dto.AliBatchResponse;
import cn.har01d.alist_tvbox.dto.AliFileItem;
import cn.har01d.alist_tvbox.dto.AliFileList;
import cn.har01d.alist_tvbox.dto.AliRequest;
import cn.har01d.alist_tvbox.dto.AliResponse;
import cn.har01d.alist_tvbox.dto.CheckinResponse;
import cn.har01d.alist_tvbox.dto.CheckinResult;
import cn.har01d.alist_tvbox.dto.RewardResponse;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.UserRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.model.AListUser;
import cn.har01d.alist_tvbox.model.LoginRequest;
import cn.har01d.alist_tvbox.model.LoginResponse;
import cn.har01d.alist_tvbox.model.UserResponse;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.IdUtils;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

import static cn.har01d.alist_tvbox.util.Constants.ACCESS_TOKEN;
import static cn.har01d.alist_tvbox.util.Constants.ALIST_LOGIN;
import static cn.har01d.alist_tvbox.util.Constants.ALIST_PASSWORD;
import static cn.har01d.alist_tvbox.util.Constants.ALIST_RESTART_REQUIRED;
import static cn.har01d.alist_tvbox.util.Constants.ALIST_USERNAME;
import static cn.har01d.alist_tvbox.util.Constants.ALI_SECRET;
import static cn.har01d.alist_tvbox.util.Constants.ATV_PASSWORD;
import static cn.har01d.alist_tvbox.util.Constants.AUTO_CHECKIN;
import static cn.har01d.alist_tvbox.util.Constants.CHECKIN_DAYS;
import static cn.har01d.alist_tvbox.util.Constants.CHECKIN_TIME;
import static cn.har01d.alist_tvbox.util.Constants.FOLDER_ID;
import static cn.har01d.alist_tvbox.util.Constants.OPEN_TOKEN;
import static cn.har01d.alist_tvbox.util.Constants.OPEN_TOKEN_TIME;
import static cn.har01d.alist_tvbox.util.Constants.REFRESH_TOKEN;
import static cn.har01d.alist_tvbox.util.Constants.REFRESH_TOKEN_TIME;
import static cn.har01d.alist_tvbox.util.Constants.SCHEDULE_TIME;
import static cn.har01d.alist_tvbox.util.Constants.SHOW_MY_ALI;
import static cn.har01d.alist_tvbox.util.Constants.USER_AGENT;
import static cn.har01d.alist_tvbox.util.Constants.ZONE_ID;

@Slf4j
@Service
@Profile("xiaoya")
public class AccountService {
    private final AccountRepository accountRepository;
    private final SettingRepository settingRepository;
    private final UserRepository userRepository;
    private final AListLocalService aListLocalService;
    private final IndexService indexService;
    private final RestTemplate restTemplate;
    private final RestTemplate restTemplate1;
    private final TaskScheduler scheduler;
    private ScheduledFuture scheduledFuture;

    public AccountService(AccountRepository accountRepository,
                          SettingRepository settingRepository,
                          UserRepository userRepository,
                          AListLocalService aListLocalService,
                          IndexService indexService,
                          AppProperties appProperties,
                          TaskScheduler scheduler,
                          RestTemplateBuilder builder) {
        this.accountRepository = accountRepository;
        this.settingRepository = settingRepository;
        this.userRepository = userRepository;
        this.aListLocalService = aListLocalService;
        this.indexService = indexService;
        this.scheduler = scheduler;
        this.restTemplate = builder.rootUri("http://localhost:" + (appProperties.isHostmode() ? "5234" : "5244")).build();
        this.restTemplate1 = builder.build();
    }

    @PostConstruct
    public void setup() {
        if (!settingRepository.existsById(ALI_SECRET)) {
            settingRepository.save(new Setting(ALI_SECRET, UUID.randomUUID().toString().replace("-", "")));
        }
        scheduleAutoCheckinTime();

        if (accountRepository.count() == 0) {
            String refreshToken = settingRepository.findById(REFRESH_TOKEN).map(Setting::getValue).orElse("");
            String openToken = settingRepository.findById(OPEN_TOKEN).map(Setting::getValue).orElse("");
            String folderId = settingRepository.findById(FOLDER_ID).map(Setting::getValue).orElse("");
            Account account = new Account();

            if (StringUtils.isAllBlank(refreshToken, openToken, folderId)) {
                log.info("load account from files");
                refreshToken = readRefreshToken();
                openToken = readOpenToken();
                folderId = readFolderId();
            } else {
                log.info("load account from settings");
                settingRepository.deleteById(REFRESH_TOKEN);
                settingRepository.deleteById(OPEN_TOKEN);
                settingRepository.deleteById(FOLDER_ID);
                account.setRefreshTokenTime(settingRepository.findById(REFRESH_TOKEN_TIME).map(Setting::getValue).map(Instant::parse).orElse(null));
                account.setOpenTokenTime(settingRepository.findById(OPEN_TOKEN_TIME).map(Setting::getValue).map(Instant::parse).orElse(null));
                account.setCheckinTime(settingRepository.findById(CHECKIN_TIME).map(Setting::getValue).map(Instant::parse).orElse(null));
                account.setCheckinDays(settingRepository.findById(CHECKIN_DAYS).map(Setting::getValue).map(Integer::parseInt).orElse(0));
                account.setAutoCheckin(settingRepository.findById(AUTO_CHECKIN).map(Setting::getValue).map(Boolean::valueOf).orElse(false));
                account.setShowMyAli(settingRepository.findById(SHOW_MY_ALI).map(Setting::getValue).map(Boolean::valueOf).orElse(false));
            }

            account.setRefreshToken(refreshToken);
            account.setOpenToken(openToken);
            account.setFolderId(folderId);
            account.setMaster(true);
            account.setUser(userRepository.findById(1).orElse(null));

            if (!StringUtils.isAllBlank(refreshToken, openToken, folderId)) {
                accountRepository.save(account);
            }
            readLogin();
        }

        if (accountRepository.count() > 0) {
            try {
                updateTokens();
            } catch (Exception e) {
                log.warn("", e);
            }

            try {
                enableMyAli();
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        enableLogin();
        addAdminUser();
    }

    private void addAdminUser() {
        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            String sql = "INSERT INTO x_users VALUES(4,'atv',\"" + generatePassword() + "\",'/',2,258,'',0,0);";
            statement.executeUpdate(sql);
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private String generatePassword() {
        Setting setting = settingRepository.findById(ATV_PASSWORD).orElse(null);
        if (setting == null) {
            log.info("generate new password");
            setting = new Setting(ATV_PASSWORD, IdUtils.generate(12));
            settingRepository.save(setting);
        }
        return setting.getValue();
    }

    private String readRefreshToken() {
        Path path = Paths.get("/data/mytoken.txt");
        if (Files.exists(path)) {
            try {
                log.info("read refresh token from file");
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    return lines.get(0).trim();
                }
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        return "";
    }

    private String readOpenToken() {
        Path path = Paths.get("/data/myopentoken.txt");
        if (Files.exists(path)) {
            try {
                log.info("read open token from file");
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    return lines.get(0).trim();
                }
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        return "";
    }

    private String readFolderId() {
        Path path = Paths.get("/data/temp_transfer_folder_id.txt");
        if (Files.exists(path)) {
            try {
                log.info("read temp transfer folder id from file");
                List<String> lines = Files.readAllLines(path);
                if (!lines.isEmpty()) {
                    return lines.get(0).trim();
                }
            } catch (Exception e) {
                log.warn("", e);
            }
        }
        return "";
    }

    private void readLogin() {
        try {
            String password = settingRepository.findById(ALIST_PASSWORD).map(Setting::getValue).orElse(null);
            if (password != null) {
                return;
            }

            AListLogin login = new AListLogin();
            login.setUsername("guest");
            login.setPassword("guest_Api789");
            Path pass = Paths.get("/data/guestpass.txt");
            if (Files.exists(pass)) {
                log.info("read guest password from file");
                List<String> lines = Files.readAllLines(pass);
                if (!lines.isEmpty()) {
                    login.setUsername("guest");
                    login.setPassword(lines.get(0));
                    login.setEnabled(true);
                }
            }

            Path guest = Paths.get("/data/guestlogin.txt");
            if (Files.exists(guest)) {
                log.info("guestlogin.txt");
                login.setUsername("dav");
                login.setEnabled(true);
            }

            settingRepository.save(new Setting(ALIST_USERNAME, login.getUsername()));
            settingRepository.save(new Setting(ALIST_PASSWORD, login.getPassword()));
            settingRepository.save(new Setting(ALIST_LOGIN, String.valueOf(login.isEnabled())));
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private void scheduleAutoCheckinTime() {
        try {
            LocalTime localTime = getScheduleTime();
            log.info("schedule time: {}", localTime);
            scheduledFuture = scheduler.schedule(this::handleScheduleTask, new CronTrigger(String.format("%d %d %d * * ?", localTime.getSecond(), localTime.getMinute(), localTime.getHour())));
            if (LocalTime.now().isAfter(localTime)) {
                autoCheckin();
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    public void autoCheckin() {
        for (Account account : accountRepository.findAll()) {
            if (account.isAutoCheckin()) {
                try {
                    checkin(account, false);
                } catch (Exception e) {
                    log.warn("{}", e.getMessage());
                }
            }
        }
    }

    private LocalTime getScheduleTime() {
        String time = settingRepository.findById(SCHEDULE_TIME).map(Setting::getValue).orElse(null);
        LocalTime localTime = LocalTime.of(9, 0, 0);
        if (time != null) {
            try {
                localTime = Instant.parse(time).atZone(ZoneId.of(ZONE_ID)).toLocalTime();
            } catch (Exception e) {
                log.warn("", e);
                settingRepository.save(new Setting(SCHEDULE_TIME, "2023-06-20T00:00:00.000Z"));
            }
        }
        return localTime;
    }

    public void handleScheduleTask() {
        log.info("auto checkin");
        List<Account> accounts = accountRepository.findAll();
        autoCheckin(accounts);

        indexService.getRemoteVersion();
    }

    @Scheduled(cron = "0 30 * * * ?")
    public void clean() {
        for (Account account : accountRepository.findAll()) {
            try {
                Map<Object, Object> response = refreshTokens(account);
                if (account.isClean()) {
                    clean(account, response);
                }
            } catch (Exception e) {
                log.warn("", e);
            }
        }
    }

    public void autoCheckin(List<Account> accounts) {
        for (Account account : accounts) {
            if (account.isAutoCheckin()) {
                try {
                    checkin(account, true);
                } catch (Exception e) {
                    log.warn("", e);
                }
            }
        }
    }

    private Map<Object, Object> refreshTokens(Account account) {
        boolean changed = false;
        Map<Object, Object> response = null;
        Instant now = Instant.now().plusSeconds(60);
        Instant time;
        try {
            time = account.getOpenTokenTime();
            if (time == null || time.plus(3, ChronoUnit.DAYS).isBefore(now) && (account.getOpenToken() != null)) {
                log.info("update open token {}: {}", account.getId(), time);
                account.setOpenToken(getAliOpenToken(account.getOpenToken()));
                account.setOpenTokenTime(Instant.now());
                changed = true;
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        try {
            time = account.getRefreshTokenTime();
            if (time == null || time.plus(1, ChronoUnit.DAYS).isBefore(now) && (account.getRefreshToken() != null)) {
                log.info("update refresh token {}: {}", account.getId(), time);
                response = getAliToken(account.getRefreshToken());
                account.setRefreshToken((String) response.get(REFRESH_TOKEN));
                account.setRefreshTokenTime(Instant.now());
                changed = true;
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        if (changed) {
            accountRepository.save(account);
        }

        return response;
    }

    public Map<Object, Object> getAliToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("User-Agent", Collections.singletonList(USER_AGENT));
        headers.put("Referer", Collections.singletonList("https://www.aliyundrive.com/"));
        Map<String, String> body = new HashMap<>();
        body.put(REFRESH_TOKEN, token);
        body.put("grant_type", REFRESH_TOKEN);
        log.debug("body: {}", body);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate1.exchange("https://auth.aliyundrive.com/v2/account/token", HttpMethod.POST, entity, Map.class);
        log.debug("get Ali token response: {}", response.getBody());
        return response.getBody();
    }

    public String getAliOpenToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("User-Agent", Collections.singletonList(USER_AGENT));
        headers.put("Referer", Collections.singletonList("https://www.aliyundrive.com/"));
        Map<String, String> body = new HashMap<>();
        body.put(REFRESH_TOKEN, token);
        body.put("grant_type", REFRESH_TOKEN);
        log.debug("body: {}", body);
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate1.exchange("https://api.xhofe.top/alist/ali_open/token", HttpMethod.POST, entity, Map.class);
        log.debug("get open token response: {}", response.getBody());
        return (String) response.getBody().get(REFRESH_TOKEN);
    }

    public void enableLogin() {
        AListLogin login = new AListLogin();
        login.setEnabled(settingRepository.findById(ALIST_LOGIN).map(Setting::getValue).orElse("").equals("true"));
        login.setUsername(settingRepository.findById(ALIST_USERNAME).map(Setting::getValue).orElse(""));
        login.setPassword(settingRepository.findById(ALIST_PASSWORD).map(Setting::getValue).orElse(""));

        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            String sql = "";
            if (login.isEnabled()) {
                log.info("enable AList login: {}", login.getUsername());
                if (login.getUsername().equals("guest")) {
                    sql = "delete from x_users where id = 3;";
                    statement.executeUpdate(sql);
                    sql = "update x_users set disabled = 0, username = '" + login.getUsername() + "' where id = 2";
                    statement.executeUpdate(sql);
                } else {
                    sql = "update x_users set disabled = 1 where id = 2";
                    statement.executeUpdate(sql);
                    sql = "delete from x_users where id = 3;";
                    statement.executeUpdate(sql);
                    sql = "INSERT INTO x_users VALUES(3,'" + login.getUsername() + "','" + login.getPassword() + "','/',0,368,'',0,0);";
                    statement.executeUpdate(sql);
                }
            } else {
                log.info("enable AList guest");
                sql = "update x_users set disabled = 0, permission = '368', password = 'guest_Api789' where id = 2;";
                statement.executeUpdate(sql);
                sql = "delete from x_users where id = 3;";
                statement.executeUpdate(sql);
            }
        } catch (Exception e) {
            log.warn("", e);
        }
        log.info("{} AList user {}", login.isEnabled() ? "enable" : "disable", login.getUsername());
    }

    public void enableMyAli() {
        List<Account> list = accountRepository.findAll().stream().filter(Account::isShowMyAli).collect(Collectors.toList());
        int id = 10000;
        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            for (Account account : list) {
                try {
                    String sql;
                    String name = account.getNickname();
                    if (StringUtils.isBlank(name)) {
                        name = String.valueOf(account.getId());
                    }
                    if (account.isShowMyAli()) {
                        log.info("enable AList storage {}", id, name);
                        sql = "INSERT INTO x_storages VALUES(" + id + ",'/\uD83D\uDCC0我的阿里云盘/" + name + "/资源盘',0,'AliyundriveOpen',30,'work','{\"root_folder_id\":\"root\",\"refresh_token\":\"" + account.getOpenToken() + "\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\",\"rorb\":\"r\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                        statement.executeUpdate(sql);
                        sql = "INSERT INTO x_storages VALUES(" + (id + 1) + ",'/\uD83D\uDCC0我的阿里云盘/" + name + "/备份盘',0,'AliyundriveOpen',30,'work','{\"root_folder_id\":\"root\",\"refresh_token\":\"" + account.getOpenToken() + "\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\",\"rorb\":\"b\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                        statement.executeUpdate(sql);
                        log.info("add AList storage {} {}", id, name);
                    } else {
                        sql = "DELETE FROM x_storages WHERE id = " + id;
                        statement.executeUpdate(sql);
                        sql = "DELETE FROM x_storages WHERE id = " + id + 1;
                        statement.executeUpdate(sql);
                        log.info("remove AList storage {} {}", id, name);
                    }
                    id += 2;
                } catch (Exception e) {
                    log.warn("", e);
                }
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    public void updateTokens() {
        List<Account> list = accountRepository.findAll();
        for (Account account : list) {
            if (account.isMaster()) {
                updateAList(account);
                return;
            }
        }
    }

    public void updateLogin(AListLogin login) {
        aListLocalService.validateAListStatus();
        if (login.isEnabled()) {
            if (StringUtils.isBlank(login.getUsername())) {
                throw new BadRequestException("缺少用户名");
            }
            if (StringUtils.isBlank(login.getPassword())) {
                throw new BadRequestException("缺少密码");
            }
            if (login.getUsername().equals("atv") || login.getUsername().equals("admin")) {
                throw new BadRequestException("用户名已被使用");
            }
        }

        settingRepository.save(new Setting(ALIST_USERNAME, login.getUsername()));
        settingRepository.save(new Setting(ALIST_PASSWORD, login.getPassword()));
        settingRepository.save(new Setting(ALIST_LOGIN, String.valueOf(login.isEnabled())));

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

    public String login() {
        String username = "atv";
        String password = settingRepository.findById(ATV_PASSWORD).map(Setting::getValue).orElseThrow(BadRequestException::new);
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        LoginResponse response = restTemplate.postForObject("/api/auth/login", request, LoginResponse.class);
        log.info("AList login response: {}", response.getData());
        return response.getData().getToken();
    }

    private AListUser getUser(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<UserResponse> response = restTemplate.exchange("/api/admin/user/get?id=" + id, HttpMethod.GET, entity, UserResponse.class);
        log.info("get AList user {} response: {}", id, response.getBody());
        return response.getBody().getData();
    }

    private void deleteUser(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/user/delete?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("delete AList user {} response: {}", id, response.getBody());
    }

    private void updateUser(AListUser user, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<AListUser> entity = new HttpEntity<>(user, headers);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/user/update", HttpMethod.POST, entity, String.class);
        log.info("update AList user {} response: {}", user.getId(), response.getBody());
    }

    private void createUser(AListUser user, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<AListUser> entity = new HttpEntity<>(user, headers);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/user/create", HttpMethod.POST, entity, String.class);
        log.info("create AList user response: {}", response.getBody());
    }

    public AListLogin getLoginInfo() {
        String username = settingRepository.findById(ALIST_USERNAME).map(Setting::getValue).orElse("");
        String password = settingRepository.findById(ALIST_PASSWORD).map(Setting::getValue).orElse("");
        String enabled = settingRepository.findById(ALIST_LOGIN).map(Setting::getValue).orElse("");
        AListLogin login = new AListLogin();
        login.setUsername(username);
        login.setPassword(password);
        login.setEnabled("true".equals(enabled));
        return login;
    }

    public CheckinResult checkin(Integer id, boolean force) {
        Account account = accountRepository.findById(id).orElseThrow(NotFoundException::new);
        return checkin(account, force);
    }

    public CheckinResult checkin(Account account, boolean force) {
        if (StringUtils.isBlank(account.getRefreshToken())) {
            return null;
        }
        if (!force) {
            validateCheckinTime(account);
        }

        log.info("checkin for account {}:{}", account.getId(), account.getNickname());
        Map<Object, Object> map = getAliToken(account.getRefreshToken());
        String accessToken = (String) map.get(ACCESS_TOKEN);
        String refreshToken = (String) map.get(REFRESH_TOKEN);
        account.setNickname((String) map.get("nick_name"));
        account.setRefreshToken(refreshToken);
        account.setRefreshTokenTime(Instant.now());

        settingRepository.save(new Setting(REFRESH_TOKEN_TIME, Instant.now().toString()));

        Map<String, Object> body = new HashMap<>();
        body.put(REFRESH_TOKEN, refreshToken);
        body.put("grant_type", REFRESH_TOKEN);
        log.debug("body: {}", body);

        HttpHeaders headers = new HttpHeaders();
        headers.put("User-Agent", Collections.singletonList(USER_AGENT));
        headers.put("Referer", Collections.singletonList("https://www.aliyundrive.com/"));
        headers.put("Authorization", Collections.singletonList("Bearer " + accessToken));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<CheckinResponse> response = restTemplate1.exchange("https://member.aliyundrive.com/v1/activity/sign_in_list", HttpMethod.POST, entity, CheckinResponse.class);

        CheckinResult result = response.getBody().getResult();
        Instant now = Instant.now();
        result.setCheckinTime(now);
        account.setCheckinTime(Instant.now());

        for (Map<String, Object> signInLog : result.getSignInLogs()) {
            if (signInLog.get("status").equals("normal") && !signInLog.get("isReward").equals(true)) {
                body = new HashMap<>();
                body.put("signInDay", signInLog.get("day"));
                log.debug("body: {}", body);

                headers = new HttpHeaders();
                headers.put("User-Agent", Collections.singletonList(USER_AGENT));
                headers.put("Referer", Collections.singletonList("https://www.aliyundrive.com/"));
                headers.put("Authorization", Collections.singletonList("Bearer " + accessToken));
                entity = new HttpEntity<>(body, headers);
                ResponseEntity<RewardResponse> res = restTemplate1.exchange("https://member.aliyundrive.com/v1/activity/sign_in_reward?_rx-s=mobile", HttpMethod.POST, entity, RewardResponse.class);
                log.info("今日签到获得 {} {}", res.getBody().getResult().getName(), res.getBody().getResult().getDescription());
            }
        }

        account.setCheckinDays(result.getSignInCount());
        log.info("{}  签到成功, 本月累计{}天", account.getNickname(), account.getCheckinDays());
        result.setSignInLogs(null);
        result.setNickname(account.getNickname());
        accountRepository.save(account);
        return result;
    }

    private void validateCheckinTime(Account account) {
        Instant checkinTime = account.getCheckinTime();
        if (checkinTime != null) {
            LocalDate time = checkinTime.atZone(ZoneId.of(ZONE_ID)).toLocalDate();
            if (LocalDate.now().isEqual(time)) {
                throw new BadRequestException(account.getNickname() + " 今日已签到");
            }
        }
    }

    public Instant updateScheduleTime(Instant time) {
        LocalTime localTime = time.atZone(ZoneId.of(ZONE_ID)).toLocalTime();
        settingRepository.save(new Setting(SCHEDULE_TIME, time.toString()));
        scheduledFuture.cancel(true);
        scheduledFuture = scheduler.schedule(this::handleScheduleTask, new CronTrigger(String.format("%d %d %d * * ?", localTime.getSecond(), localTime.getMinute(), localTime.getHour())));
        log.info("update schedule time: {}", localTime);
        return time;
    }

    public Account create(AccountDto dto) {
        long count = validateCreate(dto);
        Account account = new Account();
        account.setId((int) count + 1);
        account.setRefreshToken(dto.getRefreshToken().trim());
        account.setOpenToken(dto.getOpenToken().trim());
        account.setFolderId(dto.getFolderId().trim());
        account.setAutoCheckin(dto.isAutoCheckin());
        account.setShowMyAli(dto.isShowMyAli());
        account.setClean(dto.isClean());
        if (count == 0) {
            account.setMaster(true);
            updateAList(account);
            aListLocalService.startAListServer();
            showMyAli(account);
        } else {
            showMyAliWithAPI(account);
        }

        log.info("refresh tokens for account {}", account);
        refreshAccountTokens(account);
        accountRepository.save(account);
        return account;
    }

    private long validateCreate(AccountDto dto) {
        long count = validate(dto);
        if (StringUtils.isNotBlank(dto.getRefreshToken()) && (accountRepository.existsByRefreshToken(dto.getRefreshToken()))) {
            throw new BadRequestException("阿里token重复");
        }
        return count;
    }

    private void refreshAccountTokens(Account account) {
        try {
            if (StringUtils.isNotBlank(account.getOpenToken())) {
                log.info("update open token: {}", account.getId());
                account.setOpenToken(getAliOpenToken(account.getOpenToken()));
                account.setOpenTokenTime(Instant.now());
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw e;
        } catch (Exception e) {
            log.warn("", e);
        }

        try {
            if (StringUtils.isNotBlank(account.getRefreshToken())) {
                log.info("update refresh token: {}", account.getId());
                Map<Object, Object> map = getAliToken(account.getRefreshToken());
                account.setRefreshToken((String) map.get(REFRESH_TOKEN));
                account.setRefreshTokenTime(Instant.now());
            }
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw e;
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private long validate(AccountDto dto) {
        long count = accountRepository.count();
        if (count == 0) {
            if (StringUtils.isBlank(dto.getRefreshToken())) {
                throw new BadRequestException("阿里token不能为空");
            }
            if (StringUtils.isBlank(dto.getOpenToken())) {
                throw new BadRequestException("开放token不能为空");
            }
            if (StringUtils.isBlank(dto.getFolderId())) {
                throw new BadRequestException("转存文件夹ID不能为空");
            }
        }

        if (StringUtils.isNotBlank(dto.getRefreshToken()) && dto.getRefreshToken().length() > 128) {
            throw new BadRequestException("阿里token长度太长");
        }
        if (StringUtils.isNotBlank(dto.getOpenToken()) && dto.getOpenToken().length() < 128) {
            throw new BadRequestException("开放token长度太短");
        }
        if (StringUtils.isNotBlank(dto.getFolderId()) && dto.getFolderId().length() > 64) {
            throw new BadRequestException("转存文件夹ID长度太长");
        }
        return count;
    }

    private void updateAList(Account account) {
        if (account == null || StringUtils.isAnyBlank(account.getRefreshToken(), account.getOpenToken(), account.getFolderId())) {
            log.warn("cannot update AList: {}", account);
            return;
        }

        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            log.info("update AList storage driver tokens by account: {}", account.getId());
            statement.executeUpdate("update x_storages set driver = 'AliyundriveShare2Open' where driver = 'AliyundriveShare'");

            String sql = "update x_storages set addition = json_set(addition, '$.RefreshToken', '" + account.getRefreshToken() + "') where driver = 'AliyundriveShare2Open'";
            statement.executeUpdate(String.format(sql));
            sql = "update x_storages set addition = json_set(addition, '$.RefreshTokenOpen', '" + account.getOpenToken() + "') where driver = 'AliyundriveShare2Open'";
            statement.executeUpdate(String.format(sql));
            sql = "update x_storages set addition = json_set(addition, '$.TempTransferFolderID', '" + account.getFolderId() + "') where driver = 'AliyundriveShare2Open'";
            statement.executeUpdate(String.format(sql));
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    public Account update(Integer id, AccountDto dto, HttpServletResponse response) {
        validateUpdate(id, dto);

        Account account = accountRepository.findById(id).orElseThrow(NotFoundException::new);
        boolean aliChanged = account.isShowMyAli() != dto.isShowMyAli();
        boolean tokenChanged = !Objects.equals(account.getRefreshToken(), dto.getRefreshToken()) || !Objects.equals(account.getOpenToken(), dto.getOpenToken());
        boolean changed = tokenChanged || account.isMaster() != dto.isMaster();
        if (!Objects.equals(account.getFolderId(), dto.getFolderId())) {
            changed = true;
        }

        account.setRefreshToken(dto.getRefreshToken().trim());
        account.setOpenToken(dto.getOpenToken().trim());
        account.setFolderId(dto.getFolderId().trim());
        account.setAutoCheckin(dto.isAutoCheckin());
        account.setShowMyAli(dto.isShowMyAli());
        account.setMaster(dto.isMaster());
        account.setClean(dto.isClean());

        if (changed && account.isMaster()) {
            updateMaster();
            account.setMaster(true);
            updateAList(account);
            settingRepository.save(new Setting(ALIST_RESTART_REQUIRED, "true"));
            response.addHeader(ALIST_RESTART_REQUIRED, "true");
        }

        if (aliChanged) {
            showMyAliWithAPI(account);
        }

        if (tokenChanged) {
            log.info("refresh tokens for account {}", id);
            refreshAccountTokens(account);
        }

        return accountRepository.save(account);
    }

    private void updateMaster() {
        log.info("reset account master");
        List<Account> list = accountRepository.findAll();
        for (Account a : list) {
            a.setMaster(false);
        }
        accountRepository.saveAll(list);
    }

    private void validateUpdate(Integer id, AccountDto dto) {
        validate(dto);
        if (StringUtils.isNotBlank(dto.getRefreshToken())) {
            Account other = accountRepository.findByRefreshToken(dto.getRefreshToken());
            if (other != null && !id.equals(other.getId())) {
                throw new BadRequestException("阿里token重复");
            }
        }
    }

    public void showMyAli(Account account) {
        int storageId = 10000 + (account.getId() - 1) * 2;
        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            String name = account.getNickname();
            if (StringUtils.isBlank(name)) {
                name = String.valueOf(account.getId());
            }
            if (account.isShowMyAli()) {
                String sql = "INSERT INTO x_storages VALUES(" + storageId + ",'/\uD83D\uDCC0我的阿里云盘/" + name + "/资源盘',0,'AliyundriveOpen',30,'work','{\"root_folder_id\":\"root\",\"refresh_token\":\"" + account.getOpenToken() + "\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\",\"rorb\":\"r\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                statement.executeUpdate(sql);
                storageId++;
                sql = "INSERT INTO x_storages VALUES(" + storageId + ",'/\uD83D\uDCC0我的阿里云盘/" + name + "/备份盘',0,'AliyundriveOpen',30,'work','{\"root_folder_id\":\"root\",\"refresh_token\":\"" + account.getOpenToken() + "\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\",\"rorb\":\"b\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                statement.executeUpdate(sql);
                log.info("add AList storage {}", name);
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    public void showMyAliWithAPI(Account account) {
        int status = aListLocalService.getAListStatus();
        if (status == 1) {
            throw new BadRequestException("AList服务启动中");
        }

        String token = status == 2 ? login() : "";
        int storageId = 10000 + (account.getId() - 1) * 2;
        if (status == 2) {
            deleteStorage(storageId, token);
            deleteStorage(storageId + 1, token);
        }

        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            String name = account.getNickname();
            if (StringUtils.isBlank(name)) {
                name = String.valueOf(account.getId());
            }
            if (account.isShowMyAli()) {
                String sql = "INSERT INTO x_storages VALUES(" + storageId + ",'/\uD83D\uDCC0我的阿里云盘/" + name + "/资源盘',0,'AliyundriveOpen',30,'work','{\"root_folder_id\":\"root\",\"refresh_token\":\"" + account.getOpenToken() + "\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\",\"rorb\":\"r\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                statement.executeUpdate(sql);
                sql = "INSERT INTO x_storages VALUES(" + (storageId + 1) + ",'/\uD83D\uDCC0我的阿里云盘/" + name + "/备份盘',0,'AliyundriveOpen',30,'work','{\"root_folder_id\":\"root\",\"refresh_token\":\"" + account.getOpenToken() + "\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\",\"rorb\":\"b\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                statement.executeUpdate(sql);
                log.info("add AList storage {}", name);
                if (status == 2) {
                    enableStorage(storageId, token);
                    enableStorage(storageId + 1, token);
                }
            } else {
                log.info("remove AList storage {}", name);
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    public void enableStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/storage/enable?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("enable AList storage {} response: {}", id, response.getBody());
    }

    public void deleteStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("/api/admin/storage/delete?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("delete AList storage {} response: {}", id, response.getBody());
    }

    public void delete(Integer id) {
        Account account = accountRepository.findById(id).orElse(null);
        if (account != null) {
            if (account.isMaster()) {
                throw new BadRequestException("不能删除主账号");
            }
            accountRepository.deleteById(id);
            account.setShowMyAli(false);
            showMyAliWithAPI(account);
        }
    }

    public int clean(Integer id) {
        Account account = accountRepository.findById(id).orElseThrow(NotFoundException::new);
        return clean(account);
    }

    private int clean(Account account) {
        Map<Object, Object> map = getAliToken(account.getRefreshToken());
        return clean(account, map);
    }

    private int clean(Account account, Map<Object, Object> map) {
        log.info("clean files for account {}:{}", account.getId(), account.getNickname());
        if (map == null) {
            map = getAliToken(account.getRefreshToken());
        }
        String accessToken = (String) map.get(ACCESS_TOKEN);
        String driveId;

        AliFileList list;
        try {
            driveId = (String) getUserInfo(accessToken).get("resource_drive_id");
            log.debug("use resource_drive_id {}", driveId);
            list = getFileList(driveId, account.getFolderId(), accessToken);
        } catch (Exception e) {
            log.warn("{}", e.getMessage());
            driveId = (String) map.get("default_drive_id");
            log.debug("use default_drive_id {}", driveId);
            list = getFileList(driveId, account.getFolderId(), accessToken);
        }

        log.debug("AliFileList: {}", list);
        List<AliFileItem> files = list.getItems().stream().filter(file -> !file.isHidden() && "file".equals(file.getType())).collect(Collectors.toList());
        return deleteFiles(driveId, files, accessToken);
    }

    private Map<String, Object> getUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("User-Agent", Collections.singletonList(USER_AGENT));
        headers.put("Referer", Collections.singletonList("https://www.aliyundrive.com/"));
        headers.put("Authorization", Collections.singletonList("Bearer " + accessToken));
        Map<String, Object> body = new HashMap<>();
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate1.exchange("https://user.aliyundrive.com/v2/user/get", HttpMethod.POST, entity, Map.class);
        return response.getBody();
    }

    private AliFileList getFileList(String driveId, String fileId, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("User-Agent", Collections.singletonList(USER_AGENT));
        headers.put("Referer", Collections.singletonList("https://www.aliyundrive.com/"));
        headers.put("Authorization", Collections.singletonList("Bearer " + accessToken));
        Map<String, Object> body = new HashMap<>();
        body.put("drive_id", driveId);
        body.put("parent_file_id", fileId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<AliFileList> response = restTemplate1.exchange("https://api.aliyundrive.com/adrive/v3/file/list", HttpMethod.POST, entity, AliFileList.class);
        return response.getBody();
    }

    private int deleteFiles(String driveId, List<AliFileItem> files, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("User-Agent", Collections.singletonList(USER_AGENT));
        headers.put("Referer", Collections.singletonList("https://www.aliyundrive.com/"));
        headers.put("Authorization", Collections.singletonList("Bearer " + accessToken));

        Instant now = Instant.now();
        Map<String, AliFileItem> map = new HashMap<>();
        AliBatchRequest body = new AliBatchRequest();
        int hours = settingRepository.findById("file_expire_hour").map(Setting::getValue).map(Integer::parseInt).orElse(6);
        hours = hours > 0 ? hours : 1;
        log.info("expire time: {} hours", hours);
        for (AliFileItem file : files) {
            if (file.getUpdatedAt().plus(hours, ChronoUnit.HOURS).isAfter(now)) {
                log.info("跳过文件'{}'，更新于{}", file.getName(), file.getUpdatedAt());
                continue;
            }
            map.put(file.getFileId(), file);
            AliRequest request = new AliRequest();
            request.setId(file.getFileId());
            request.getBody().put("drive_id", driveId);
            request.getBody().put("file_id", file.getFileId());
            body.getRequests().add(request);
        }

        int count = 0;
        HttpEntity<AliBatchRequest> entity = new HttpEntity<>(body, headers);
        ResponseEntity<AliBatchResponse> response = restTemplate1.exchange("https://api.aliyundrive.com/v3/batch", HttpMethod.POST, entity, AliBatchResponse.class);
        for (AliResponse item : response.getBody().getResponses()) {
            AliFileItem file = map.get(item.getId());
            if (item.getStatus() == 204) {
                count++;
            }
            LocalDateTime time = file.getCreatedAt().atZone(ZoneId.of(ZONE_ID)).toLocalDateTime();
            log.info("删除文件'{}'{}, 创建于{}, 文件大小：{}", file.getName(), item.getStatus() == 204 ? "成功" : "失败", time, Utils.byte2size(file.getSize()));
        }
        return count;
    }

    public String getAliRefreshToken(String id) {
        String aliSecret = settingRepository.findById(ALI_SECRET).map(Setting::getValue).orElse("");
        if (aliSecret.equals(id)) {
            return accountRepository.getFirstByMasterTrue()
                    .map(Account::getRefreshToken)
                    .orElseThrow(NotFoundException::new);
        }
        return null;
    }
}
