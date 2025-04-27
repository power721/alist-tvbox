package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.domain.SystemInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;

@RestController
public class SystemController {

    @GetMapping("/api/system")
    public SystemInfo getSystemInfo() throws UnknownHostException {
        Runtime runtime = Runtime.getRuntime();
        Properties props = System.getProperties();
        InetAddress addr = InetAddress.getLocalHost();
        return new SystemInfo(
                addr.getHostAddress(),
                addr.getHostName(),
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
                System.getenv("ALIST_PORT")
        );
    }

}
