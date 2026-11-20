package cn.har01d.alist_tvbox.service;

/** Optional ownership policy for plugins currently managed by another workflow. */
public interface PluginMutationAdmission {
    boolean managed(int pluginId);
}
