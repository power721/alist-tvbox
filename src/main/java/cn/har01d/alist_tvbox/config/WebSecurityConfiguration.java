package cn.har01d.alist_tvbox.config;

import cn.spark2fire.auth.token.TokenFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

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
                .authorizeRequests()
                .antMatchers(HttpMethod.POST, "/accounts/login", "/accounts/logout").permitAll()
                .antMatchers(HttpMethod.OPTIONS).permitAll()
                .antMatchers("/ali-accounts/**", "/files/**", "/sites/**", "/shares/**", "/subscriptions/**", "/settings/**").authenticated()
                .antMatchers("/login", "/storage", "/storages", "/token", "/resources", "/checkin").authenticated()
                .antMatchers(HttpMethod.POST).authenticated()
                .antMatchers(HttpMethod.PUT).authenticated()
                .antMatchers(HttpMethod.PATCH).authenticated()
                .antMatchers(HttpMethod.DELETE).authenticated()
                .anyRequest().permitAll()
                .and()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and()
                .csrf().disable()
                .formLogin().disable()
                .logout().disable()
                .addFilterBefore(tokenFilter, BasicAuthenticationFilter.class);
        return http.build();
    }
}
