package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.auth.LoginDto;
import cn.har01d.alist_tvbox.auth.TokenService;
import cn.har01d.alist_tvbox.auth.UserToken;
import cn.har01d.alist_tvbox.config.MyUserDetailsService;
import cn.har01d.alist_tvbox.entity.User;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import cn.har01d.alist_tvbox.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Set;

@RestController
@RequestMapping("/api/accounts")
public class UserController {
    private final UserService userService;
    private final TokenService tokenService;
    private final MyUserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserService userService,
                          TokenService tokenService,
                          MyUserDetailsService userDetailsService,
                          PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public UserToken login(@RequestBody LoginDto account) {
        UserDetails user = userDetailsService.loadUserByUsername(account.getUsername());
        if (user != null && passwordEncoder.matches(account.getPassword(), user.getPassword())) {
            Collection<? extends GrantedAuthority> authorities;
            if (user.getAuthorities().isEmpty()) {
                authorities = Set.of(new SimpleGrantedAuthority("ROLE_USER"));
            } else {
                authorities = user.getAuthorities();
            }
            String token = tokenService.encodeToken(user.getUsername(), authorities.iterator().next().getAuthority(), account.isRememberMe());
            return new UserToken(user.getUsername(), authorities, token);
        } else {
            throw new UserUnauthorizedException("用户或密码错误", 40001);
        }
    }

    @PostMapping("/logout")
    public void logout() {
    }

    @GetMapping("/principal")
    public Authentication principal() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @PostMapping("/update")
    public void update(@RequestBody User user) {
        userService.update(user);
    }
}
