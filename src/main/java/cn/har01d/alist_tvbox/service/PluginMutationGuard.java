package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.Comparator;

@Service
public class PluginMutationGuard {
    private final PluginRepository plugins;
    private final EntityManager entityManager;
    private final ObjectProvider<PluginMutationAdmission> admissions;
    private final TransactionTemplate transaction;

    public PluginMutationGuard(PluginRepository plugins, EntityManager entityManager,
            ObjectProvider<PluginMutationAdmission> admissions, TransactionTemplate transaction) {
        this.plugins = plugins;
        this.entityManager = entityManager;
        this.admissions = admissions;
        this.transaction = transaction;
    }

    /** Caller owns the transaction; refresh also discards any stale first-level-cache values. */
    public Plugin lock(int id) {
        Plugin plugin = plugins.findById(id).orElseThrow(NotFoundException::new);
        entityManager.refresh(plugin, LockModeType.PESSIMISTIC_WRITE);
        return plugin;
    }

    public boolean managed(int id) {
        return admissions.orderedStream().anyMatch(admission -> admission.managed(id));
    }

    public void requireWritable(int id) {
        if (managed(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AT_PLUGIN_MAINTENANCE_ACTIVE");
        }
    }

    public Plugin writable(int id) {
        Plugin plugin = lock(id);
        requireWritable(id);
        return plugin;
    }

    public void requireAllWritable() {
        for (Plugin plugin : plugins.findAll().stream().sorted(Comparator.comparing(Plugin::getId)).toList()) {
            writable(plugin.getId());
        }
    }

    public void reorder(int id, int order) {
        transaction.executeWithoutResult(status -> {
            Plugin plugin = lock(id);
            plugin.setSortOrder(order);
            plugins.save(plugin);
        });
    }

    public boolean updateExtend(int id, String expected, String next) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            Plugin plugin = lock(id);
            if (managed(id) || !Objects.equals(expected, plugin.getExtend())) return false;
            plugin.setExtend(next);
            plugins.save(plugin);
            return true;
        }));
    }
}
