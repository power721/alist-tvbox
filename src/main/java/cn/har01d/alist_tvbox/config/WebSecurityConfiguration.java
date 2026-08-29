package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.auth.TokenFilter;
import cn.har01d.alist_tvbox.domain.Role;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class WebSecurityConfiguration {
    private final TokenFilter tokenFilter;

    public WebSecurityConfiguration(TokenFilter tokenFilter) {
        this.tokenFilter = tokenFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                        .authorizeHttpRequests(requests -> requests.requestMatchers(
                                "/tg/**",
                                "/tgs/**",
                                "/tv/**",
                                "/dav/**",
                                "/parse/**",
                                "/check-links/**",
                                "/offline_download/**",
                                "/ali/access_token",
                                "/api/local/admin/password",
                                "/api/local/backup",
                                "/api/local/db-test",
                                "/api/alist/status",
                                "/api/profiles",
                                "/api/accounts/login",
                                "/api/sync/validate",
                                "/api/playback/event",
                                "/api/playback/events",
                                "/api/playback/changes",
                                "/api/playback/sync",
                                "/live/follow",
                                "/live/unfollow",
                                "/live/*/follow",
                                "/live/*/unfollow"
                        ).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS).permitAll()
                        // 全局配置写入只允许 ADMIN/CLIENT:USER 仅可读(脱敏后),不得改全局设置/重置全局 vod token
                        .requestMatchers(HttpMethod.POST, "/api/token").hasAnyAuthority(Role.ADMIN.name(), Role.CLIENT.name())
                        .requestMatchers(HttpMethod.POST, "/api/settings/**").hasAnyAuthority(Role.ADMIN.name(), Role.CLIENT.name())
                        .requestMatchers(HttpMethod.PUT, "/api/settings/**").hasAnyAuthority(Role.ADMIN.name(), Role.CLIENT.name())
                        .requestMatchers(HttpMethod.PATCH, "/api/settings/**").hasAnyAuthority(Role.ADMIN.name(), Role.CLIENT.name())
                        .requestMatchers(HttpMethod.DELETE, "/api/settings/**").hasAnyAuthority(Role.ADMIN.name(), Role.CLIENT.name())
                        .requestMatchers(
                                "/api/token",
                                "/api/settings",
                                "/api/telegram/search",
                                "/api/settings/install_mode",
                                "/api/alist/start/status",
                                "/api/share-link",
                                "/api/drive/**",
                                "/api/accounts/logout",
                                "/api/accounts/principal"
                        ).authenticated()
                        .requestMatchers("/api/playback/tokens/**", "/api/playback/records", "/api/playback/records/**",
                                "/api/playback/tombstones/**")
                        .hasAnyAuthority(Role.ADMIN.name(), Role.USER.name())
                        // 账号管理按归属隔离(AccountAccessGuard):USER 管本人账号,全局账号只读脱敏
                        .requestMatchers("/api/pan/accounts/**", "/api/ali/accounts/**", "/api/pikpak/accounts/**")
                        .hasAnyAuthority(Role.ADMIN.name(), Role.USER.name(), Role.CLIENT.name())
                        // VOD 订阅按归属隔离:USER 可用全局默认订阅(只读)+添加个人订阅
                        .requestMatchers("/api/subscriptions/**")
                        .hasAnyAuthority(Role.ADMIN.name(), Role.USER.name(), Role.CLIENT.name())
                        // 猫影视客户端要求链接内嵌 basic auth(客户端限制):USER 也需读取凭证来拼装链接;
                        // 该凭证只解锁 /cat /node /open 内容前缀,内容仍由 vod token 校验,泄漏面可控。重新生成仍限管理级
                        .requestMatchers(HttpMethod.GET, "/api/basic-auth-credentials")
                        .hasAnyAuthority(Role.ADMIN.name(), Role.USER.name(), Role.CLIENT.name())
                        .requestMatchers("/api/live/follows", "/api/live/follows/**")
                        .hasAnyAuthority(Role.ADMIN.name(), Role.USER.name())
                        // 弹幕渲染配置是个人观看偏好,按登录用户独立存储
                        .requestMatchers("/api/live/danmaku-config")
                        .hasAnyAuthority(Role.ADMIN.name(), Role.USER.name())
                        // 用户级设置(白名单键):按登录用户存 {key}:u{uid},读取回退全局值
                        .requestMatchers("/api/user-settings/**")
                        .hasAnyAuthority(Role.ADMIN.name(), Role.USER.name())
                        .requestMatchers("/api/media-subscriptions", "/api/media-subscriptions/**")
                        .hasAnyAuthority(Role.ADMIN.name(), Role.USER.name())
                        // 代理行管理(/p 盘线路 pid 按用户吊销):须登录,USER 只见/只删自己的归属行
                        .requestMatchers(HttpMethod.GET, "/play-urls").authenticated()
                        .requestMatchers("/api/users/**", "/api/tenants/**", "/api/files/**", "/api/alist/alias/**")
                        .hasAuthority(Role.ADMIN.name())
                        .requestMatchers("/api/**").hasAnyAuthority(Role.ADMIN.name(), Role.CLIENT.name())
                        .requestMatchers(HttpMethod.POST).authenticated()
                        .requestMatchers(HttpMethod.PUT).authenticated()
                        .requestMatchers(HttpMethod.PATCH).authenticated()
                        .requestMatchers(HttpMethod.DELETE).authenticated()
                        .anyRequest().permitAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(tokenFilter, BasicAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
