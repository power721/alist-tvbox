package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.auth.TokenService;
import cn.har01d.alist_tvbox.auth.UserToken;
import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.dto.UserDto;
import cn.har01d.alist_tvbox.entity.SessionRepository;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.entity.UserRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.service.backup.RestoreState;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RestoreState restoreState;
    private final JdbcTemplate jdbcTemplate;

    private final Set<String> usernames = new HashSet<>();

    @PostConstruct
    public void init() {
        if (restoreState.shouldSkipInitializationWrites()) {
            log.info("Skip user initialization during startup YAML restore");
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
     * YAML restore (the handler falls back to DB auto-increment), so a restored admin may land at id≠1.
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

    public UserToken generateToken(User user) {
        var authorities = List.of(new SimpleGrantedAuthority(user.getRole().name()));
        String token = tokenService.encodeToken(user.getId(), user.getUsername(), user.getRole().name());
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
        if (StringUtils.isEmpty(dto.getPassword())) {
            throw new BadRequestException("密码不能为空");
        }
        if (userRepository.findByUsername(dto.getUsername()) != null) {
            throw new BadRequestException("用户名已经存在");
        }

        var user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        userRepository.save(user);
        usernames.add(user.getUsername());
        return user;
    }

    public User update(int id, UserDto dto) {
        var user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("用户不存在"));
        String username = user.getUsername();
        var sessions = sessionRepository.findAllByUsername(username);
        sessionRepository.deleteAll(sessions);
        if (StringUtils.isNotEmpty(dto.getUsername())) {
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
        userRepository.deleteById(id);
        loadUsernames();
    }

    public UserToken updateAccount(UserDto dto) {
        String username = SecurityContextHolder.getContext().getAuthentication().getPrincipal().toString();
        User user = userRepository.findByUsername(username);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }

        if (user.getRole() == Role.ADMIN && !username.equals(dto.getUsername())) {
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
        return generateToken(user);
    }
}
