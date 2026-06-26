package cn.har01d.alist_tvbox.web;

import java.util.List;

import cn.har01d.alist_tvbox.dto.UserDto;
import cn.har01d.alist_tvbox.dto.SessionDto;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import cn.har01d.alist_tvbox.auth.LoginDto;
import cn.har01d.alist_tvbox.auth.UserToken;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import cn.har01d.alist_tvbox.service.RateLimiter;
import cn.har01d.alist_tvbox.service.UserService;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;

    @GetMapping("/api/users")
    public List<User> list() {
        return userService.list();
    }

    @PostMapping("/api/users")
    public User create(@RequestBody UserDto user) {
        return userService.create(user);
    }

    @PostMapping("/api/users/{id}")
    public User update(@PathVariable int id, @RequestBody UserDto user) {
        return userService.update(id, user);
    }

    @DeleteMapping("/api/users/{id}")
    public void delete(@PathVariable int id) {
        userService.delete(id);
    }

    @PostMapping("/api/accounts/login")
    public UserToken login(@RequestBody LoginDto account, HttpServletRequest request) {
        String rateKey = account.getUsername() + ":" + Utils.getClientIp(request);
        rateLimiter.checkLocked(rateKey);
        User user = userService.findByUsername(account.getUsername());
        if (user == null || !passwordEncoder.matches(account.getPassword(), user.getPassword())) {
            rateLimiter.recordFailure(rateKey);
            throw new UserUnauthorizedException("用户或密码错误", 40001);
        }
        rateLimiter.reset(rateKey);
        return userService.generateToken(user, Utils.getClientIp(request), request.getHeader("User-Agent"));
    }

    @PostMapping("/api/accounts/logout")
    public void logout() {
        userService.logout();
    }

    @GetMapping("/api/accounts/principal")
    public Authentication principal() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @PostMapping("/api/accounts/update")
    public UserToken updateAccount(@RequestBody UserDto user, HttpServletRequest request) {
        return userService.updateAccount(user, Utils.getClientIp(request), request.getHeader("User-Agent"));
    }

    @GetMapping("/api/accounts/sessions")
    public List<SessionDto> sessions() {
        String token = (String) SecurityContextHolder.getContext().getAuthentication().getCredentials();
        return userService.listSessions(token);
    }

    @DeleteMapping("/api/accounts/sessions/{id}")
    public void revokeSession(@PathVariable int id) {
        userService.revokeSession(id);
    }
}
