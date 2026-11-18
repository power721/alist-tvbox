package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.auth.TokenFilter;
import cn.har01d.atmaintenance.MaintenanceApplication;
import cn.har01d.atmaintenance.config.SecurityConfiguration;
import cn.har01d.atmaintenance.service.EmbeddedMaintenanceMigrations;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.annotation.Order;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:maintenance-embedded.properties")
@ComponentScan(basePackages = "cn.har01d.atmaintenance",
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = {MaintenanceApplication.class, SecurityConfiguration.class}))
@EntityScan(basePackages = {"cn.har01d.alist_tvbox.entity", "cn.har01d.atmaintenance.entity"})
@EnableJpaRepositories(basePackages = {"cn.har01d.alist_tvbox.entity", "cn.har01d.atmaintenance.repository"})
public class EmbeddedMaintenanceConfiguration implements WebMvcConfigurer {
    @Bean
    FlywayMigrationStrategy maintenanceMigrations() {
        return new EmbeddedMaintenanceMigrations();
    }

    @Bean
    @Order(1)
    SecurityFilterChain maintenanceSecurityFilterChain(HttpSecurity http, TokenFilter tokenFilter) throws Exception {
        return http.securityMatcher("/api/plugin-maintenance/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().hasAuthority("ADMIN"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(tokenFilter, BasicAuthenticationFilter.class)
                .build();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/plugin-maintenance/**")
                .addResourceLocations("classpath:/maintenance-ui/");
    }

    @Bean
    @Order(2)
    SecurityFilterChain maintenancePageSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher("/plugin-maintenance/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .build();
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/plugin-maintenance", "/plugin-maintenance/");
        registry.addViewController("/plugin-maintenance/").setViewName("forward:/plugin-maintenance/index.html");
    }
}
