package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.PluginRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public final class PluginMutationGuardTestSupport {
    private PluginMutationGuardTestSupport() {}

    public static TransactionTemplate transaction() {
        var manager = mock(PlatformTransactionManager.class);
        lenient().when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return new TransactionTemplate(manager);
    }

    public static PluginMutationGuard unmanaged(PluginRepository plugins) {
        return new PluginMutationGuard(plugins, mock(EntityManager.class),
                new StaticListableBeanFactory().getBeanProvider(PluginMutationAdmission.class), transaction());
    }
}
