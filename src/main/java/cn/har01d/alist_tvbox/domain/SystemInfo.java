package cn.har01d.alist_tvbox.domain;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SystemInfo {
    public String ip;
    public String hostName;
    public Long jvmTotalMemory;
    public Long jvmUsedMemory;
    public int jvmCpus;
    public String javaVersion;
    public String javaVendor;
    public String javaHome;
    public String jvmName;
    public String osName;
    public String osVersion;
    public String osArch;
    public String userName;
    public String userHome;
    public String timezone;
    public String workDir;
    public String pid;
    public String alistPort;
}
