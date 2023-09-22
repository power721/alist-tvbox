package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.auth.TokenFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
public class WebSecurityConfiguration {
    private final TokenFilter tokenFilter;

    public WebSecurityConfiguration(TokenFilter tokenFilter) {
        this.tokenFilter = tokenFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeRequests(requests -> requests.requestMatchers(
                                new AntPathRequestMatcher("/accounts/login"),
                                new AntPathRequestMatcher("/accounts/logout")
                        ).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS).permitAll()
                        .requestMatchers(
                                new AntPathRequestMatcher("/ali/accounts/**"),
                                new AntPathRequestMatcher("/pikpak/accounts/**"),
                                new AntPathRequestMatcher("/bilibili/-/**"),
                                new AntPathRequestMatcher("/alist/**"),
                                new AntPathRequestMatcher("/files/**"),
                                new AntPathRequestMatcher("/sites/**"),
                                new AntPathRequestMatcher("/shares/**"),
                                new AntPathRequestMatcher("/storage/**"),
                                new AntPathRequestMatcher("/resources/**"),
                                new AntPathRequestMatcher("/subscriptions/**"),
                                new AntPathRequestMatcher("/settings/**"),
                                new AntPathRequestMatcher("/tasks/**"),
                                new AntPathRequestMatcher("/nav/**"),
                                new AntPathRequestMatcher("/logs/**"),
                                new AntPathRequestMatcher("/meta/**"),
                                new AntPathRequestMatcher("/index/**"),
                                new AntPathRequestMatcher("/index-templates/**"),
                                new AntPathRequestMatcher("/login"),
                                new AntPathRequestMatcher("/system"),
                                new AntPathRequestMatcher("/token"),
                                new AntPathRequestMatcher("/export-shares")
                        ).authenticated()
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
