package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.domain.SystemInfo;
import cn.har01d.alist_tvbox.service.AListLocalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;

@RestController
public class SystemController {
    private final AListLocalService aListLocalService;

    public SystemController(AListLocalService aListLocalService) {
        this.aListLocalService = aListLocalService;
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
                String.valueOf(aListLocalService.getExternalPort())
        );
    }

}
