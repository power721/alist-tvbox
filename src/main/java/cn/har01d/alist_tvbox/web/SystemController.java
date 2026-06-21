package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.domain.SystemInfo;
import cn.har01d.alist_tvbox.service.AListLocalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Properties;

@RestController
public class SystemController {
    private final AListLocalService aListLocalService;
    private final DataSource dataSource;
    private final String dialect;

    public SystemController(AListLocalService aListLocalService, DataSource dataSource,
                            @Value("${spring.jpa.database-platform:}") String dialect) {
        this.aListLocalService = aListLocalService;
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    @GetMapping("/api/system")
    public SystemInfo getSystemInfo() {
        Runtime runtime = Runtime.getRuntime();
        Properties props = System.getProperties();
        String ip = "127.0.0.1";
        String hostname = "localhost";
        try {
            InetAddress addr = InetAddress.getLocalHost();
            ip = addr.getHostAddress();
            hostname = addr.getHostName();
        } catch (UnknownHostException e) {
            // ignore
        }

        String dbType = "";
        String dbProduct = "";
        String dbVersion = "";
        String dbUrl = "";
        String dbDriverName = "";
        String dbDriverVersion = "";
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData md = conn.getMetaData();
            dbProduct = nn(md.getDatabaseProductName());
            dbVersion = nn(md.getDatabaseProductVersion());
            dbUrl = nn(md.getURL()).replaceAll("(?i)(password=)[^&;]*", "$1***");
            dbDriverName = nn(md.getDriverName());
            dbDriverVersion = nn(md.getDriverVersion());
            switch (dbProduct.toLowerCase()) {
                case "h2": dbType = "H2"; break;
                case "mysql": dbType = "MySQL"; break;
                case "postgresql": dbType = "PostgreSQL"; break;
                default: dbType = dbProduct;
            }
        } catch (Exception e) {
            // 数据库不可达时不影响系统信息接口；DB 字段留空
        }

        return new SystemInfo(
                ip,
                hostname,
                runtime.totalMemory(),
                runtime.totalMemory() - runtime.freeMemory(),
                runtime.availableProcessors(),
                props.getProperty("java.version"),
                props.getProperty("java.vendor"),
                props.getProperty("java.home"),
                props.getProperty("java.vm.name"),
                props.getProperty("os.name"),
                props.getProperty("os.version"),
                props.getProperty("os.arch"),
                props.getProperty("user.name"),
                props.getProperty("user.home"),
                props.getProperty("user.timezone"),
                props.getProperty("user.dir"),
                props.getProperty("PID"),
                String.valueOf(aListLocalService.getExternalPort()),
                dbType,
                dbProduct,
                dbVersion,
                dbUrl,
                dbDriverName,
                dbDriverVersion,
                dialect
        );
    }

    private static String nn(String s) {
        return s == null ? "" : s;
    }

}
