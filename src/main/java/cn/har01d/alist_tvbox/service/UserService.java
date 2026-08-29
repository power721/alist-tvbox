package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.auth.TokenService;
import cn.har01d.alist_tvbox.auth.UserToken;
import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.dto.UserDto;
import cn.har01d.alist_tvbox.dto.SessionDto;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.entity.DriverAccountRepository;
import cn.har01d.alist_tvbox.entity.PikPakAccount;
import cn.har01d.alist_tvbox.entity.PikPakAccountRepository;
import cn.har01d.alist_tvbox.entity.Session;
import cn.har01d.alist_tvbox.entity.SessionRepository;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.entity.UserRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import cn.har01d.alist_tvbox.service.backup.RestoreState;
import cn.har01d.alist_tvbox.util.UserAgentParser;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static cn.har01d.alist_tvbox.util.Constants.USER_TOKEN_PREFIX;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RestoreState restoreState;
    private final DriverAccountRepository driverAccountRepository;
    private final AccountRepository accountRepository;
    private final PikPakAccountRepository pikPakAccountRepository;
    private final JdbcTemplate jdbcTemplate;
    // ObjectProvider:账号服务依赖链较深,延迟解析避免启动期循环依赖
    private final ObjectProvider<DriverAccountService> driverAccountService;
    private final ObjectProvider<AccountService> accountService;
    private final ObjectProvider<PikPakService> pikPakService;

    private final Set<String> usernames = new HashSet<>();

    @PostConstruct
    public void init() {
        if (restoreState.shouldSkipInitializationWrites()) {
            log.info("Skip user initialization during startup JSON restore");
            return;
        }
        ensureAdminOccupiesIdOne();
        try {
            initializeAdminUser();
        } catch (Exception e) {
            log.error("Failed to initialize admin user", e);
            throw new IllegalStateException("Critical failure - admin user initialization failed", e);
        }

        fixUserRole();
        loadUsernames();
    }

    /**
     * Guarantee the admin occupies id=1. The IDENTITY-backed {@link User} id cannot be preserved across a
     * JSON restore (the handler falls back to DB auto-increment), so a restored admin may land at id≠1.
     * {@code initializeAdminUser}/{@code resetAdminPassword}/{@code delete} all key on id=1, and a missing
     * id=1 makes {@code createNewAdmin()} fire every boot — silently producing duplicate {@code admin}
     * rows (no unique constraint) that crash {@code findByUsername} on login. If id=1 is empty but an
     * ADMIN exists elsewhere, move the lowest-id admin into id=1 via native SQL. Idempotent; no-op on a
     * fresh DB (no admin yet — {@code createNewAdmin} then seeds id=1 naturally).
     */
    public void ensureAdminOccupiesIdOne() {
        if (userRepository.findById(1).isPresent()) {
            return;
        }
        userRepository.findFirstByRoleOrderByIdAsc(Role.ADMIN).ifPresent(admin -> {
            Integer oldId = admin.getId();
            if (oldId == null || oldId == 1) {
                return;
            }
            log.warn("Moving admin user from id={} to id=1 to restore the id=1 invariant", oldId);
            jdbcTemplate.update("update x_user set id = 1 where id = ?", oldId);
        });
    }

    private void fixUserRole() {
        userRepository.findById(1).ifPresent(user -> {
            if (user.getRole() == null) {
                user.setRole(Role.ADMIN);
                userRepository.save(user);
            }
        });
    }

    public boolean isUsernameExist(String username) {
        return usernames.contains(username);
    }

    /**
     * 用户级 vod token = "u-{username}"。前缀 u- 保留给用户 token,全局 tokens 在保存时过滤该前缀,
     * 两个空间永不撞车,解析无需再查全局列表。裸用户名不再是合法 token。
     */
    public static String userVodToken(String username) {
        return USER_TOKEN_PREFIX + username;
    }

    /** 解析用户级 vod token:非 u- 前缀或空用户名返回 null。 */
    public String usernameOfUserVodToken(String token) {
        if (token == null || !token.startsWith(USER_TOKEN_PREFIX)) {
            return null;
        }
        String username = token.substring(USER_TOKEN_PREFIX.length());
        return StringUtils.isBlank(username) ? null : username;
    }

    /** vod token → 用户:仅识别 u-{username} 且用户存在;共享 token/空返回 null。 */
    public User findByUserVodToken(String token) {
        String username = usernameOfUserVodToken(token);
        return username == null ? null : findByUsername(username);
    }

    /**
     * 凭证形态 token(u-{username}-{vodSecret})→ 用户:密钥验真通过才返回;
     * 裸 u-{username} 无熵(用户名可猜测),不能作为凭证权威,返回 null。
     * 用户名已禁含 '-'(validateUsername),split 三段无歧义。
     */
    public User findUserByCredentialToken(String token) {
        if (token == null || !token.startsWith(USER_TOKEN_PREFIX)) {
            return null;
        }
        String[] parts = token.split("-");
        if (parts.length != 3 || parts[1].isEmpty() || parts[2].isEmpty()) {
            return null;
        }
        User user = findByUsername(parts[1]);
        return user == null ? null : parts[2].equals(vodSecretOf(user)) ? user : null;
    }

    /** 裸 u-{username}(用户存在)→ 拼上密钥的凭证形态,供配置嵌入;其它形态原样返回。 */
    public String toCredentialToken(String token) {
        User user = findByUserVodToken(token);
        return user == null ? token : token + "-" + vodSecretOf(user);
    }

    private void loadUsernames() {
        usernames.clear();
        userRepository.findAll().forEach(user -> usernames.add(user.getUsername()));
    }

    private void initializeAdminUser() throws IOException {
        Optional<User> existingAdmin = userRepository.findById(1);
        if (existingAdmin.isPresent()) {
            updateExistingAdmin(existingAdmin.get());
        } else {
            createNewAdmin();
        }
    }

    private void updateExistingAdmin(User adminUser) throws IOException {
        Path credentialsPath = Utils.getDataPath("atv", "credentials.txt");

        if (Files.exists(credentialsPath)) {
            log.debug("Updating existing admin user credentials from {}", credentialsPath);
            List<String> lines = Files.readAllLines(credentialsPath, StandardCharsets.UTF_8);
            if (lines.size() >= 2) {
                String username = lines.get(0).trim();
                String password = lines.get(1).trim();

                if (!username.isEmpty() && !password.isEmpty()) {
                    adminUser.setUsername(username);
                    adminUser.setPassword(passwordEncoder.encode(password));
                    userRepository.save(adminUser);
                    log.info("管理员帐号重置成功！");
                    Files.deleteIfExists(credentialsPath);
                    return;
                }
            }
            log.warn("credentials.txt exists but doesn't contain valid username/password");
        }
    }

    private void createNewAdmin() {
        User adminUser = new User();
        adminUser.setRole(Role.ADMIN);
        adminUser.setUsername("admin");

        String password = Utils.generateSecurePassword();
        adminUser.setPassword(passwordEncoder.encode(password));

        userRepository.save(adminUser);

        String message = String.format(
                """
                        ============== 管理员帐号 ==============
                        用户名： admin
                        密码： %s
                        ======================================
                        警告： 登陆后立即更改密码！
                        """,
                password
        );

        log.warn(message);

        try {
            Path credentialsPath = Utils.getDataPath("initial_admin_credentials.txt");
            Files.write(credentialsPath, message.getBytes(), StandardOpenOption.CREATE);
            log.info("Initial admin credentials saved to {}", credentialsPath);
        } catch (IOException e) {
            log.error("Failed to save initial admin credentials", e);
        }
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public void logout() {
        String token = (String) SecurityContextHolder.getContext().getAuthentication().getCredentials();
        if (token != null) {
            sessionRepository.findByToken(token).ifPresent(sessionRepository::delete);
        }
    }

    public List<SessionDto> listSessions(String currentToken) {
        String username = SecurityContextHolder.getContext().getAuthentication().getPrincipal().toString();
        return sessionRepository.findAllByUsername(username).stream()
                .map(s -> toDto(s, currentToken))
                .toList();
    }

    public void revokeSession(int id) {
        String username = SecurityContextHolder.getContext().getAuthentication().getPrincipal().toString();
        Session session = sessionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("会话不存在"));
        if (!username.equals(session.getUsername())) {
            throw new UserUnauthorizedException("无权操作该会话", 40301);
        }
        sessionRepository.delete(session);
    }

    private SessionDto toDto(Session s, String currentToken) {
        SessionDto dto = new SessionDto();
        dto.setId(s.getId());
        dto.setUsername(s.getUsername());
        dto.setRole(s.getRole());
        dto.setLoginIp(s.getLoginIp());
        dto.setUserAgent(s.getUserAgent());
        dto.setBrowser(UserAgentParser.parseBrowser(s.getUserAgent()));
        dto.setOs(UserAgentParser.parseOs(s.getUserAgent()));
        dto.setLoginTime(s.getCreateTime());
        dto.setExpireTime(s.getExpireTime());
        dto.setCurrent(currentToken != null && currentToken.equals(s.getToken()));
        return dto;
    }

    public UserToken generateToken(User user, String loginIp, String userAgent) {
        var authorities = List.of(new SimpleGrantedAuthority(user.getRole().name()));
        String token = tokenService.encodeToken(user.getId(), user.getUsername(), user.getRole().name(), loginIp, userAgent);
        return new UserToken(user.getId(), user.getUsername(), authorities, token);
    }

    public String resetAdminPassword(String password) {
        User admin = userRepository.findById(1).orElseThrow(() -> new NotFoundException("管理员不存在"));
        String username = admin.getUsername();
        sessionRepository.deleteAll(sessionRepository.findAllByUsername(username));
        admin.setUsername("admin");
        admin.setRole(Role.ADMIN);
        admin.setPassword(passwordEncoder.encode(password));
        userRepository.save(admin);
        usernames.remove(username);
        usernames.add(admin.getUsername());
        return password;
    }

    public List<User> list() {
        return userRepository.findAll();
    }

    public User create(UserDto dto) {
        if (StringUtils.isEmpty(dto.getUsername())) {
            throw new BadRequestException("用户名不能为空");
        }
        validateUsername(dto.getUsername());
        if (StringUtils.isEmpty(dto.getPassword())) {
            throw new BadRequestException("密码不能为空");
        }
        if (userRepository.findByUsername(dto.getUsername()) != null) {
            throw new BadRequestException("用户名已经存在");
        }

        var user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setVodSecret(generateVodSecret());
        userRepository.save(user);
        usernames.add(user.getUsername());
        return user;
    }

    /** 用户名禁含 '-':凭证 token 形态 u-{username}-{secret} 靠 '-' 分段,用户名带 '-' 会造成归属解析歧义。 */
    private void validateUsername(String username) {
        if (username.contains("-")) {
            throw new BadRequestException("用户名不能包含 '-'");
        }
    }

    /** 用户凭证下载密钥:16 hex(SecureRandom)。用户名可猜测,u-{username} token 无熵,熵全靠此值。 */
    public static String generateVodSecret() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        StringBuilder secret = new StringBuilder(16);
        for (byte b : bytes) {
            secret.append(String.format("%02x", b));
        }
        return secret.toString();
    }

    /** 凭证下载密钥(V39 迁移兜底:读时补生成,防旧库漏回填)。 */
    public String vodSecretOf(User user) {
        if (StringUtils.isBlank(user.getVodSecret())) {
            user.setVodSecret(generateVodSecret());
            userRepository.save(user);
        }
        return user.getVodSecret();
    }

    public User update(int id, UserDto dto) {
        var user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("用户不存在"));
        String username = user.getUsername();
        var sessions = sessionRepository.findAllByUsername(username);
        sessionRepository.deleteAll(sessions);
        if (StringUtils.isNotEmpty(dto.getUsername())) {
            validateUsername(dto.getUsername());
            User other = userRepository.findByUsername(dto.getUsername());
            if (other != null && !other.getId().equals(user.getId())) {
                throw new BadRequestException("用户名已经存在");
            }
            user.setUsername(dto.getUsername());
        }
        if (StringUtils.isNotEmpty(dto.getPassword())) {
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
        }
        userRepository.save(user);
        usernames.remove(username);
        usernames.add(user.getUsername());
        return user;
    }

    public void delete(int id) {
        if (id == 1) {
            throw new BadRequestException("不能删除管理员");
        }
        // 个人账号凭证即用户数字身份:人走凭证销毁;追剧订阅引用该账号的,解析时自然落空(已有容错)。
        // 必须走各账号服务的 delete(连带移除内嵌 AList 的 storage/挂载),裸删库表会把活凭证遗留在 AList 里。
        // AList 侧清理失败(服务未就绪等)时中止删用户,账号行保留待管理员重试;
        // 唯一豁免:PikPak 主账号保护属业务拒绝而非清理失败,行照清
        for (DriverAccount account : driverAccountRepository.findByOwnerUid(id)) {
            try {
                driverAccountService.getObject().delete(account.getId());
            } catch (BadRequestException e) {
                throw new BadRequestException("网盘账号 [" + account.getName() + "] 清理失败,请稍后重试:" + e.getMessage());
            } catch (Exception e) {
                throw new BadRequestException("网盘账号 [" + account.getName() + "] 清理失败(AList 不可用?),请稍后重试");
            }
        }
        for (Account account : accountRepository.findByOwnerUid(id)) {
            try {
                accountService.getObject().delete(account.getId());
            } catch (BadRequestException e) {
                throw new BadRequestException("阿里账号 [user-" + account.getId() + "] 清理失败,请稍后重试:" + e.getMessage());
            } catch (Exception e) {
                throw new BadRequestException("阿里账号 [user-" + account.getId() + "] 清理失败(AList 不可用?),请稍后重试");
            }
        }
        for (PikPakAccount account : pikPakAccountRepository.findByOwnerUid(id)) {
            try {
                pikPakService.getObject().delete(account.getId());
            } catch (BadRequestException e) {
                if (e.getMessage() != null && e.getMessage().contains("主账号")) {
                    log.warn("pikpak account {} is master-protected, drop row for user {}", account.getId(), id);
                    continue;
                }
                throw new BadRequestException("PikPak 账号 [" + account.getUsername() + "] 清理失败,请稍后重试:" + e.getMessage());
            } catch (Exception e) {
                throw new BadRequestException("PikPak 账号 [" + account.getUsername() + "] 清理失败(AList 不可用?),请稍后重试");
            }
        }
        // 兜底清行:个别服务删除失败(如 PikPak 主账号保护)时也不留下孤儿账号行
        driverAccountRepository.deleteAll(driverAccountRepository.findByOwnerUid(id));
        accountRepository.deleteAll(accountRepository.findByOwnerUid(id));
        pikPakAccountRepository.deleteAll(pikPakAccountRepository.findByOwnerUid(id));
        userRepository.deleteById(id);
        loadUsernames();
    }

    public UserToken updateAccount(UserDto dto, String loginIp, String userAgent) {
        String username = SecurityContextHolder.getContext().getAuthentication().getPrincipal().toString();
        User user = userRepository.findByUsername(username);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }

        if (user.getRole() == Role.ADMIN && !username.equals(dto.getUsername())) {
            if (StringUtils.isBlank(dto.getUsername())) {
                throw new BadRequestException("用户名不能为空");
            }
            validateUsername(dto.getUsername());
            User other = userRepository.findByUsername(dto.getUsername());
            if (other != null && !other.getId().equals(user.getId())) {
                throw new BadRequestException("用户名已经存在");
            }
            user.setUsername(dto.getUsername());
        }

        if (StringUtils.isNotBlank(dto.getPassword())) {
            if (StringUtils.isBlank(dto.getOldPassword()) || !passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
                throw new BadRequestException("旧密码不正确");
            }
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
        }

        var sessions = sessionRepository.findAllByUsername(username);
        sessionRepository.deleteAll(sessions);

        userRepository.save(user);
        usernames.remove(username);
        usernames.add(user.getUsername());
        return generateToken(user, loginIp, userAgent);
    }
}
