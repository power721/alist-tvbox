package cn.har01d.alist_tvbox.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.har01d.alist_tvbox.auth.LoginDto;
import cn.har01d.alist_tvbox.auth.UserToken;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import cn.har01d.alist_tvbox.service.UserService;

@RestController
@RequestMapping("/api/accounts")
public class UserController {
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserService userService, PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public UserToken login(@RequestBody LoginDto account) {
        User user = userService.findByUsername(account.getUsername());
        if (user == null || !passwordEncoder.matches(account.getPassword(), user.getPassword())) {
            throw new UserUnauthorizedException("用户或密码错误", 40001);
        }
        return userService.generateToken(user);
    }

    @PostMapping("/logout")
    public void logout(@RequestHeader("Authorization") String token) {
        userService.logout(token);
    }

    @GetMapping("/principal")
    public Authentication principal() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @PostMapping("/update")
    public UserToken update(@RequestBody User user) {
        return userService.update(user);
    }
}
