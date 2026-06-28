package cn.har01d.alist_tvbox.config;

import db.migration.current.V2__Normalize_reserved_columns;
import db.migration.current.V3__Fix_null_sort_order;
import db.migration.current.V8__Normalize_enum_columns;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NativeDetector;

/**
 * Flyway discovers SQL migrations fine in a GraalVM native image (Spring Boot's
 * NativeImageResourceProvider serves them as resources), but Java migrations are found
 * by classpath package scanning (Flyway's ClassPathScanner), which native-image cannot
 * perform: directory listing of {@code resource:} paths is unsupported, so the scanner
 * logs "Unable to scan location: /db/migration/current (unsupported protocol: resource)"
 * and returns nothing. As a result the Java migrations under db.migration.current
 * are never discovered and never run natively (leaving the schema half-migrated).
 *
 * Spring Boot collects {@code JavaMigration} beans for Flyway, but these cannot be
 * registered as unconditional beans: in a regular JVM build Flyway still scans the
 * classpath and would then see two migrations with the same version ("Found more than
 * one migration with version ..."). So instead we add them through a
 * FlywayConfigurationCustomizer, and only when actually running in a native image
 * (detected at runtime via {@link NativeDetector}).
 */
@Configuration
public class NativeFlywayMigrationConfig {

    @Bean
    public FlywayConfigurationCustomizer nativeJavaMigrationCustomizer() {
        return configuration -> {
            if (!NativeDetector.inNativeImage()) {
                return;
            }
            configuration.javaMigrations(
                    new V2__Normalize_reserved_columns(),
                    new V3__Fix_null_sort_order(),
                    new V8__Normalize_enum_columns());
        };
    }
}
