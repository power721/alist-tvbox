package cn.har01d.alist_tvbox.entity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:schema-validation;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/current",
        "logging.file.name=target/schema-validation.log"
})
class SchemaValidationTest {

    @Test
    void flywayBaselineSatisfiesJpaValidation() {
    }

    @Test
    void currentSchemaUsesMysqlCompatibleIndexCreation() throws IOException {
        try (var input = getClass().getResourceAsStream("/db/migration/current/V1__Create_current_schema.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(migration).doesNotContain("CREATE INDEX IF NOT EXISTS");
        }
    }
}
