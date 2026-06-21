package cn.har01d.alist_tvbox.web;

import java.util.List;

import cn.har01d.alist_tvbox.dto.UserDto;
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
import cn.har01d.alist_tvbox.service.UserService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

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
    public UserToken login(@RequestBody LoginDto account) {
        User user = userService.findByUsername(account.getUsername());
        if (user == null || !passwordEncoder.matches(account.getPassword(), user.getPassword())) {
            throw new UserUnauthorizedException("用户或密码错误", 40001);
        }
        return userService.generateToken(user);
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
    public UserToken updateAccount(@RequestBody UserDto user) {
        return userService.updateAccount(user);
    }
}
