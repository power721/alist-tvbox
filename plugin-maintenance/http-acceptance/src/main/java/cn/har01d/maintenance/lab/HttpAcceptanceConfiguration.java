package cn.har01d.maintenance.lab;

import cn.har01d.alist_tvbox.auth.SessionTokenService;
import cn.har01d.alist_tvbox.auth.TokenFilter;
import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.*;
import cn.har01d.alist_tvbox.service.*;
import cn.har01d.alist_tvbox.service.backup.RestoreState;
import cn.har01d.alist_tvbox.util.Utils;
import cn.har01d.alist_tvbox.web.T4Controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.*;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.servlet.config.annotation.*;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** The isolated verifier uses current AT owners, not test classes or an AT startup profile. */
final class HttpAcceptanceConfiguration {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    record Database(String url) {}

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = UserRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                    UserRepository.class, SessionRepository.class, SettingRepository.class,
                    AccountRepository.class, DriverAccountRepository.class, PikPakAccountRepository.class,
                    PluginRepository.class, PluginFilterRepository.class, TenantRepository.class,
                    SubscriptionRepository.class}))
    static class Persistence {
        @Bean DataSource dataSource(Database database) {
            return new DriverManagerDataSource(database.url(), "sa", "");
        }

        @Bean Flyway flyway(DataSource dataSource) {
            var flyway = Flyway.configure().dataSource(dataSource)
                    .locations("classpath:db/migration/h2", "classpath:db/migration/common",
                            "classpath:db/migration/current").load();
            flyway.migrate();
            return flyway;
        }

        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource, Flyway flyway) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setManagedTypes(PersistenceManagedTypes.of(User.class.getName(), Session.class.getName(),
                    Setting.class.getName(), Account.class.getName(), DriverAccount.class.getName(),
                    PikPakAccount.class.getName(), Plugin.class.getName(), PluginFilter.class.getName(),
                    Tenant.class.getName(), Subscription.class.getName()));
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate",
                    "hibernate.physical_naming_strategy",
                    "org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl"));
            return factory;
        }

        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc implements WebMvcConfigurer {
        @Bean AppProperties properties() {
            var properties = new AppProperties();
            properties.setEnabledToken(true);
            properties.setT4Enabled(true);
            properties.setT4StandaloneOnly(true);
            properties.setT4DetailEnhance(false);
            return properties;
        }

        @Bean SessionTokenService tokens(SessionRepository sessions) { return new SessionTokenService(sessions); }

        @Bean TokenFilter tokenFilter(SessionTokenService tokens, SettingRepository settings) {
            return new TokenFilter(tokens, settings);
        }

        @Bean UserService users(
                UserRepository users, SessionRepository sessions, PasswordEncoder encoder, SessionTokenService tokens,
                DriverAccountRepository drivers, AccountRepository accounts, PikPakAccountRepository pikpak,
                JdbcTemplate jdbc, ObjectProvider<DriverAccountService> driverService,
                ObjectProvider<AccountService> accountService, ObjectProvider<PikPakService> pikpakService) {
            return new UserService(users, sessions, encoder, tokens,
                    new RestoreState(Utils.getDataPath("unused-restore.zip").toString()),
                    drivers, accounts, pikpak, jdbc, driverService, accountService, pikpakService);
        }

        @Bean SubscriptionService subscriptions(
                JdbcTemplate jdbc, SettingRepository settings, SubscriptionRepository subscriptions,
                AccountRepository accounts, DriverAccountRepository drivers, PluginRepository plugins,
                PluginFilterRepository filters, TenantRepository tenants, UserService users,
                AppProperties properties) {
            // Only the fixed plugin route is exercised. Built-in services are not started or exposed.
            return new SubscriptionService(new StandardEnvironment(), properties, new RestTemplateBuilder(), JSON,
                    jdbc, settings, subscriptions, accounts, null, null, drivers, null, null, null,
                    plugins, filters, null, null, new TenantService(tenants), users, null, null, null, null, null);
        }

        @Bean ManagedT4WorkerService worker(
                SubscriptionService subscriptions, AppProperties properties, PluginRepository plugins,
                ManagedT4BrokerClient broker, Integer pluginId,
                @Qualifier("managedApprovedPublicData") JsonNode approved) {
            return new ManagedT4WorkerService(JSON, plugins, subscriptions, properties, pluginId, broker, approved);
        }

        @Bean T4Controller t4(
                SubscriptionService subscriptions, AppProperties properties,
                PluginRepository plugins, ManagedT4WorkerService worker) {
            return new T4Controller(subscriptions, properties, null, null, null, null, null, null, null,
                    null, null, null, null, worker, plugins, JSON, null);
        }

        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.add(new StringHttpMessageConverter(StandardCharsets.UTF_8));
            converters.add(new MappingJackson2HttpMessageConverter(JSON));
        }
    }
}
