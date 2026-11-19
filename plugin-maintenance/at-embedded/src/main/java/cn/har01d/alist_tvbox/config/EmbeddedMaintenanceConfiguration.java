package cn.har01d.alist_tvbox.config;

import cn.har01d.alist_tvbox.auth.TokenFilter;
import cn.har01d.atmaintenance.service.EmbeddedMaintenanceMigrations;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:maintenance-embedded.properties")
@Import(FlywayRepairConfig.class)
@ComponentScan(basePackages = "cn.har01d.atmaintenance",
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
@EntityScan(basePackages = {"cn.har01d.alist_tvbox.entity", "cn.har01d.atmaintenance.entity"})
@EnableJpaRepositories(basePackages = {"cn.har01d.alist_tvbox.entity", "cn.har01d.atmaintenance.repository"})
public class EmbeddedMaintenanceConfiguration {
    @Bean
    @Primary
    FlywayMigrationStrategy maintenanceMigrations(
            @Qualifier("repairedMigrationStrategy") FlywayMigrationStrategy hostMigrations) {
        return new EmbeddedMaintenanceMigrations(hostMigrations);
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

}
