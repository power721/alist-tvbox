package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.auth.TokenFilter;
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
                .authorizeRequests(requests -> requests
                        .requestMatchers(
                                new AntPathRequestMatcher("/bilibili/-/status"),
                                new AntPathRequestMatcher("/bilibili/-/check")
                        ).authenticated()
                        .requestMatchers(
                                new AntPathRequestMatcher("/bilibili/**", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/subtitles/**", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/vod/**", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/vod1/**", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/play/**", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/parse/**", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/sub/**", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/repo/**", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/open/**", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/images", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/ali/token/*", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/accounts/principal", HttpMethod.GET.name()),
                                new AntPathRequestMatcher("/accounts/login", HttpMethod.POST.name()),
                                new AntPathRequestMatcher("/accounts/logout", HttpMethod.POST.name())
                        ).permitAll()
                        .anyRequest().authenticated())
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
