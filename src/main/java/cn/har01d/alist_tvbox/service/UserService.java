package cn.har01d.alist_tvbox.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.entity.UserRepository;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void init() {
        try {
            initializeAdminUser();
        } catch (Exception e) {
            log.error("Failed to initialize admin user", e);
            throw new RuntimeException("Critical failure - admin user initialization failed", e);
        }
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
            List<String> lines = Files.readAllLines(credentialsPath, StandardCharsets.UTF_8);
            if (lines.size() >= 2) {
                String username = lines.get(0).trim();
                String password = lines.get(1).trim();

                if (!username.isEmpty() && !password.isEmpty()) {
                    adminUser.setUsername(username);
                    adminUser.setPassword(passwordEncoder.encode(password));
                    userRepository.save(adminUser);
                    log.info("帐号重置成功！");
                    Files.deleteIfExists(credentialsPath);

                    try {
                        log.debug("reset .jwt");
                        Path path = Utils.getDataPath(".jwt");
                        Files.deleteIfExists(path);
                    } catch (Exception e) {
                        // ignore
                    }
                    return;
                }
            }
            log.warn("credentials.txt exists but doesn't contain valid username/password");
        }
    }

    private void createNewAdmin() {
        User adminUser = new User();
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

    public void update(User dto) {
        String id = SecurityContextHolder.getContext().getAuthentication().getPrincipal().toString();
        User user = userRepository.findById(Integer.parseInt(id)).orElseThrow(() -> new NotFoundException("用户不存在"));
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        userRepository.save(user);
    }
}
