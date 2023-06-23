package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.AListLogin;
import cn.har01d.alist_tvbox.dto.AccountDto;
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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
@Profile("xiaoya")
public class AccountService {
    private final AccountRepository accountRepository;
    private final SettingRepository settingRepository;
    private final UserRepository userRepository;
    private final AListLocalService aListLocalService;
    private final RestTemplate restTemplate;
    private final TaskScheduler scheduler;
    private ScheduledFuture scheduledFuture;

    public AccountService(AccountRepository accountRepository,
                          SettingRepository settingRepository,
                          UserRepository userRepository,
                          AListLocalService aListLocalService,
                          TaskScheduler scheduler,
                          RestTemplateBuilder builder) {
        this.accountRepository = accountRepository;
        this.settingRepository = settingRepository;
        this.userRepository = userRepository;
        this.aListLocalService = aListLocalService;
        this.scheduler = scheduler;
        this.restTemplate = builder.build();
    }

    @PostConstruct
    public void setup() {
        scheduleAutoCheckinTime();

        if (accountRepository.count() == 0) {
            String refreshToken = settingRepository.findById("refresh_token").map(Setting::getValue).orElse("");
            String openToken = settingRepository.findById("open_token").map(Setting::getValue).orElse("");
            String folderId = settingRepository.findById("folder_id").map(Setting::getValue).orElse("");
            Account account = new Account();

            if (StringUtils.isAllBlank(refreshToken, openToken, folderId)) {
                log.info("load account from files");
                refreshToken = readRefreshToken();
                openToken = readOpenToken();
                folderId = readFolderId();
            } else {
                log.info("load account from settings");
                settingRepository.deleteById("refresh_token");
                settingRepository.deleteById("open_token");
                settingRepository.deleteById("folder_id");
                account.setRefreshTokenTime(settingRepository.findById("refresh_token_time").map(Setting::getValue).map(Instant::parse).orElse(null));
                account.setOpenTokenTime(settingRepository.findById("open_token_time").map(Setting::getValue).map(Instant::parse).orElse(null));
                account.setCheckinTime(settingRepository.findById("checkin_time").map(Setting::getValue).map(Instant::parse).orElse(null));
                account.setCheckinDays(settingRepository.findById("checkin_days").map(Setting::getValue).map(Integer::parseInt).orElse(0));
                account.setAutoCheckin(settingRepository.findById("auto_checkin").map(Setting::getValue).map(Boolean::valueOf).orElse(false));
                account.setShowMyAli(settingRepository.findById("show_my_ali").map(Setting::getValue).map(Boolean::valueOf).orElse(false));
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
        try (Connection connection = DriverManager.getConnection(Constants.DB_URL)) {
            Statement statement = connection.createStatement();
            String sql = "INSERT INTO x_users VALUES(4,'atv',\"" + generatePassword() + "\",'/',2,258,'',0,0);";
            statement.executeUpdate(sql);
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    private String generatePassword() {
        Setting setting = settingRepository.findById("atv_password").orElse(null);
        if (setting == null) {
            log.info("generate new password");
            setting = new Setting("atv_password", IdUtils.generate(12));
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
            String password = settingRepository.findById("alist_password").map(Setting::getValue).orElse(null);
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

            settingRepository.save(new Setting("alist_username", login.getUsername()));
            settingRepository.save(new Setting("alist_password", login.getPassword()));
            settingRepository.save(new Setting("alist_login", String.valueOf(login.isEnabled())));
        } catch (Exception e) {
            log.warn("", e);
        }
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
                localTime = Instant.parse(time).atZone(ZoneId.of("Asia/Shanghai")).toLocalTime();
            } catch (Exception e) {
                log.warn("", e);
                settingRepository.save(new Setting("schedule_time", "2023-06-20T00:00:00.000Z"));
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

        for (Account account : accountRepository.findAll()) {
            refreshTokens(account);
        }
    }

    private void refreshTokens(Account account) {
        Instant now = Instant.now();
        Instant time;
        try {
            time = account.getOpenTokenTime();
            if (time == null || time.plus(24, ChronoUnit.HOURS).isBefore(now)) {
                if (account.getOpenToken() != null) {
                    log.info("update open token: {}", time);
                    account.setOpenToken(getAliOpenToken(account.getOpenToken()));
                    account.setOpenTokenTime(Instant.now());
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }

        try {
            time = account.getRefreshTokenTime();
            if (time == null || time.plus(24, ChronoUnit.HOURS).isBefore(now)) {
                if (account.getRefreshToken() != null) {
                    log.info("update refresh token: {}", time);
                    account.setRefreshToken((String) getAliToken(account.getRefreshToken()).get("refresh_token"));
                    account.setRefreshTokenTime(Instant.now());
                }
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    public Map<Object, Object> getAliToken(String token) {
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

    public String getAliOpenToken(String token) {
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

    public void enableLogin() {
        AListLogin login = new AListLogin();
        login.setEnabled(settingRepository.findById("alist_login").map(Setting::getValue).orElse("").equals("true"));
        login.setUsername(settingRepository.findById("alist_username").map(Setting::getValue).orElse(""));
        login.setPassword(settingRepository.findById("alist_password").map(Setting::getValue).orElse(""));

        try (Connection connection = DriverManager.getConnection(Constants.DB_URL)) {
            Statement statement = connection.createStatement();
            String sql = "";
            if (login.isEnabled()) {
                log.info("enable AList login");
                sql = "update x_users set disabled = 1 where id = 2";
                statement.executeUpdate(sql);
                sql = "INSERT INTO x_users VALUES(3,'" + login.getUsername() + "','" + login.getPassword() + "','/',0,368,'',0,0);";
                statement.executeUpdate(sql);
            } else {
                log.info("enable AList guest");
                sql = "update x_users set disabled = 0, permission = '368' where id = 2;";
                statement.executeUpdate(sql);
                sql = "delete from x_users where id = 3;";
                statement.executeUpdate(sql);
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
        log.info("{} AList user {}", login.isEnabled() ? "enable" : "disable", login.getUsername());
    }

    public void enableMyAli() {
        List<Account> list = accountRepository.findAll().stream().filter(Account::isShowMyAli).collect(Collectors.toList());
        int id = 10000;
        try (Connection connection = DriverManager.getConnection(Constants.DB_URL)) {
            for (Account account : list) {
                try {
                    String sql;
                    Statement statement = connection.createStatement();
                    String name = "\uD83D\uDCC0我的阿里云盘";
                    if (id > 10000) {
                        name += (id - 9999);
                    }
                    if (account.isShowMyAli()) {
                        log.info("enable AList storage {}", id, name);
                        sql = "INSERT INTO x_storages VALUES(" + id + ",'/" + name + "',0,'AliyundriveOpen',30,'work','{\"root_folder_id\":\"root\",\"refresh_token\":\"" + account.getOpenToken() + "\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',0,'name','ASC','',0,'302_redirect','');";
                        log.info("add AList storage {} {}", id, name);
                    } else {
                        sql = "DELETE FROM x_storages WHERE id = " + id;
                        log.info("remove AList storage {} {}", id, name);
                    }
                    statement.executeUpdate(sql);
                    id++;
                } catch (Exception e) {
                    log.warn("", e);
                }
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
        // ignore
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

    public String login() {
        String username = "atv";
        String password = settingRepository.findById("atv_password").map(Setting::getValue).orElseThrow(BadRequestException::new);
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        LoginResponse response = restTemplate.postForObject("http://localhost:5244/api/auth/login", request, LoginResponse.class);
        log.info("AList login response: {}", response.getData());
        return response.getData().getToken();
    }

    private AListUser getUser(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<UserResponse> response = restTemplate.exchange("http://localhost:5244/api/admin/user/get?id=" + id, HttpMethod.GET, entity, UserResponse.class);
        log.info("get AList user {} response: {}", id, response.getBody());
        return response.getBody().getData();
    }

    private void deleteUser(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/user/delete?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("delete AList user {} response: {}", id, response.getBody());
    }

    private void updateUser(AListUser user, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<AListUser> entity = new HttpEntity<>(user, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/user/update", HttpMethod.POST, entity, String.class);
        log.info("update AList user {} response: {}", user.getId(), response.getBody());
    }

    private void createUser(AListUser user, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<AListUser> entity = new HttpEntity<>(user, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/user/create", HttpMethod.POST, entity, String.class);
        log.info("create AList user response: {}", response.getBody());
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

    public void checkin(boolean force) {
        for (Account account : accountRepository.findAll()) {
            if (account.isAutoCheckin()) {
                checkin(account, force);
            }
        }
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
        String accessToken = (String) map.get("access_token");
        String refreshToken = (String) map.get("refresh_token");
        account.setNickname((String) map.get("nick_name"));
        account.setRefreshToken(refreshToken);
        account.setRefreshTokenTime(Instant.now());

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
                headers.put("User-Agent", Collections.singletonList("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"));
                headers.put("Referer", Collections.singletonList("https://www.aliyundrive.com/"));
                headers.put("Authorization", Collections.singletonList("Bearer " + accessToken));
                entity = new HttpEntity<>(body, headers);
                ResponseEntity<RewardResponse> res = restTemplate.exchange("https://member.aliyundrive.com/v1/activity/sign_in_reward?_rx-s=mobile", HttpMethod.POST, entity, RewardResponse.class);
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
            LocalDate time = checkinTime.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
            if (LocalDate.now().isEqual(time)) {
                throw new BadRequestException("今日已签到");
            }
        }
    }


    public Instant updateScheduleTime(Instant time) {
        LocalTime localTime = time.atZone(ZoneId.of("Asia/Shanghai")).toLocalTime();
        settingRepository.save(new Setting("schedule_time", time.toString()));
        scheduledFuture.cancel(true);
        scheduledFuture = scheduler.schedule(this::autoCheckin, new CronTrigger(String.format("%d %d %d * * ?", localTime.getSecond(), localTime.getMinute(), localTime.getHour())));
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
        if (count == 0) {
            account.setMaster(true);
            updateAList(account);
            aListLocalService.startAListServer(false);
            showMyAli(account);
        } else {
            showMyAliWithAPI(account);
        }

        accountRepository.save(account);
        log.info("refresh tokens for account {}", account);
        refreshAccountTokens(account);
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
                account.setRefreshToken((String) getAliToken(account.getRefreshToken()).get("refresh_token"));
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

        try (Connection connection = DriverManager.getConnection(Constants.DB_URL)) {
            log.info("update AList storage driver tokens by account: {}", account.getId());
            Statement statement = connection.createStatement();
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
        // ignore
    }

    public Account update(Integer id, AccountDto dto) {
        validateUpdate(id, dto);

        Account account = accountRepository.findById(id).orElseThrow(NotFoundException::new);
        if (account.isShowMyAli() != dto.isShowMyAli()) {
            showMyAliWithAPI(account);
        }

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

        if (changed && account.isMaster()) {
            updateMaster();
            account.setMaster(true);
            updateAList(account);
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
        int storageId = 9999 + account.getId();
        try (Connection connection = DriverManager.getConnection(Constants.DB_URL)) {
            Statement statement = connection.createStatement();
            String name = "\uD83D\uDCC0我的阿里云盘";
            if (account.getId() > 1) {
                name += account.getId();
            }
            if (account.isShowMyAli()) {
                String sql = "INSERT INTO x_storages VALUES(" + storageId + ",'/" + name + "',0,'AliyundriveOpen',30,'work','{\"root_folder_id\":\"root\",\"refresh_token\":\"" + account.getOpenToken() + "\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                statement.executeUpdate(sql);
                log.info("add AList storage {}", name);
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
        // ignore
    }

    public void showMyAliWithAPI(Account account) {
        int status = aListLocalService.getAListStatus();
        if (status == 1) {
            throw new BadRequestException("AList服务启动中");
        }

        String token = status == 2 ? login() : "";
        int storageId = 9999 + account.getId();
        if (status == 2) {
            deleteStorage(storageId, token);
        }

        try (Connection connection = DriverManager.getConnection(Constants.DB_URL)) {
            Statement statement = connection.createStatement();
            String name = "\uD83D\uDCC0我的阿里云盘";
            if (account.getId() > 1) {
                name += account.getId();
            }
            if (account.isShowMyAli()) {
                String sql = "INSERT INTO x_storages VALUES(" + storageId + ",'/" + name + "',0,'AliyundriveOpen',30,'work','{\"root_folder_id\":\"root\",\"refresh_token\":\"" + account.getOpenToken() + "\",\"order_by\":\"name\",\"order_direction\":\"ASC\",\"oauth_token_url\":\"https://api.nn.ci/alist/ali_open/token\",\"client_id\":\"\",\"client_secret\":\"\"}','','2023-06-15 12:00:00+00:00',1,'name','ASC','',0,'302_redirect','');";
                statement.executeUpdate(sql);
                log.info("add AList storage {}", name);
                if (status == 2) {
                    enableStorage(storageId, token);
                }
            } else {
                log.info("remove AList storage {}", name);
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
        // ignore
    }

    private void enableStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/storage/enable?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("enable AList storage response: {}", response.getBody());
    }

    private void deleteStorage(Integer id, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.put("Authorization", Collections.singletonList(token));
        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        ResponseEntity<String> response = restTemplate.exchange("http://localhost:5244/api/admin/storage/delete?id=" + id, HttpMethod.POST, entity, String.class);
        log.info("delete AList storage response: {}", response.getBody());
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
}
