package cn.har01d.alist_tvbox.service;

/** Optional host policy; an unfinished durable maintenance intent must deny normal calls. */
public interface PluginWorkerAdmission {
    boolean blocked(String siteKey);
}
