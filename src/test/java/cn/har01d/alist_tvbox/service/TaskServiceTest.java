package cn.har01d.alist_tvbox.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskServiceTest {

    @Test
    void usesMysqlDdlWhenDatabaseProductIsMysqlWithoutProfile() {
        assertThat(TaskService.summaryColumnTextSql("MySQL"))
                .isEqualTo("ALTER TABLE task MODIFY COLUMN summary TEXT");
    }

    @Test
    void usesPostgresqlDdlWhenDatabaseProductIsPostgresqlWithoutProfile() {
        assertThat(TaskService.summaryColumnTextSql("PostgreSQL"))
                .isEqualTo("ALTER TABLE task ALTER COLUMN summary TYPE TEXT");
    }
}
