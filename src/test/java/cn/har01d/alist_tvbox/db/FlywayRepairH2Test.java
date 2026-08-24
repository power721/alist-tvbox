package cn.har01d.alist_tvbox.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.RepairResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 迁移类就地修正的工具(环境变量门控,平时跳过):V20/V30 这类 Java 迁移在**未发布分支上修 bug** 后
 * class 字节变化 → flyway_schema_history 里旧 checksum 与新类不匹配,新 jar 启动 validate 直接失败。
 * <p>
 * 用法(须先停应用释放 H2 文件锁,并备份 .mv.db):
 * <pre>
 * MSUB_REPAIR_URL='jdbc:h2:file:/opt/alist-tvbox/atv' \
 * MSUB_REPAIR_USER=sa MSUB_REPAIR_PASSWORD=password \
 * mvn test -Dtest=FlywayRepairH2Test
 * </pre>
 * repair 只改 flyway_schema_history 的 checksum/描述对齐当前类(不重跑迁移,不动业务数据),
 * 结束附一次 validate 证明新 jar 可正常启动。先在库副本上跑过验证:
 * <pre>cp /opt/alist-tvbox/atv.mv.db /tmp/atv-repair-check/  # URL 指向副本即可演练</pre>
 */
class FlywayRepairH2Test {

    @Test
    void repairChecksumsAgainstCurrentMigrationClasses() {
        String url = System.getenv("MSUB_REPAIR_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "MSUB_REPAIR_URL 未配置,跳过(工具测试)");
        String user = System.getenv("MSUB_REPAIR_USER");
        String password = System.getenv("MSUB_REPAIR_PASSWORD");

        Flyway flyway = Flyway.configure()
                .dataSource(url, user == null ? "sa" : user, password)
                // 与 application.yaml 的 spring.flyway.locations 同集({vendor} 按 H2 解析为 h2)
                .locations("classpath:db/migration/h2", "classpath:db/migration/common", "classpath:db/migration/current")
                .load();
        RepairResult result = flyway.repair();
        System.out.println("flyway repair: checksum repaired=" + result.repairActions.stream()
                .filter(a -> a.equalsIgnoreCase("Checksum repaired")).count()
                + " actions=" + result.repairActions);
        // repair 后 validate 必须通过:这正是新 jar 启动时 Flyway 要做的事
        flyway.validate();
        System.out.println("flyway validate: OK (新 jar 可正常启动)");
    }
}
