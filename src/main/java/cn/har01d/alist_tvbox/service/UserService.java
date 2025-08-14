package cn.har01d.alist_tvbox.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import cn.har01d.alist_tvbox.auth.TokenService;
import cn.har01d.alist_tvbox.auth.UserToken;
import cn.har01d.alist_tvbox.domain.Role;
import cn.har01d.alist_tvbox.entity.SessionRepository;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.entity.UserRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    @PostConstruct
    public void init() {
        try {
            initializeAdminUser();
        } catch (Exception e) {
            log.error("Failed to initialize admin user", e);
            throw new IllegalStateException("Critical failure - admin user initialization failed", e);
        }

        fixUserRole();
    }

    private void fixUserRole() {
        userRepository.findById(1).ifPresent(user -> {
            if (user.getRole() == null) {
                user.setRole(Role.ADMIN);
                userRepository.save(user);
            }
        });
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

    public List<User> list() {
        return userRepository.findAll();
    }

    public User create(User dto) {
        var user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        return userRepository.save(user);
    }

    public User update(int id, User dto) {
        var user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("用户不存在"));
        var sessions = sessionRepository.findAllByUsername(user.getUsername());
        sessionRepository.deleteAll(sessions);
        if (StringUtils.isNotEmpty(dto.getUsername())) {
            user.setUsername(dto.getUsername());
        }
        if (StringUtils.isNotEmpty(dto.getPassword())) {
            user.setPassword(passwordEncoder.encode(dto.getPassword()));
        }
        return userRepository.save(user);
    }

    public void delete(int id) {
        userRepository.deleteById(id);
    }

    public UserToken updateAccount(User dto) {
        String username = SecurityContextHolder.getContext().getAuthentication().getPrincipal().toString();
        User user = userRepository.findByUsername(username);
        if (user == null) {
            throw new NotFoundException("用户不存在");
        }
        var sessions = sessionRepository.findAllByUsername(username);
        sessionRepository.deleteAll(sessions);

        if (user.getRole() == Role.ADMIN && !username.equals(dto.getUsername())) {
            User other = userRepository.findByUsername(dto.getUsername());
            if (other != null && !other.getId().equals(user.getId())) {
                throw new BadRequestException("用户名已经存在");
            }
            user.setUsername(dto.getUsername());
        }

        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        userRepository.save(user);
        return generateToken(user);
    }
}
