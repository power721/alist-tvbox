package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.entity.EmbyRepository;
import cn.har01d.alist_tvbox.entity.FeiniuRepository;
import cn.har01d.alist_tvbox.entity.JellyfinRepository;
import cn.har01d.alist_tvbox.entity.Plugin;
import cn.har01d.alist_tvbox.entity.PluginRepository;
import cn.har01d.alist_tvbox.entity.Setting;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.SiteRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionSourceServiceTest {
    @Test
    void exposesPianDanAsBuiltinNavigationSource() {
        PluginRepository pluginRepository = mock(PluginRepository.class);
        SettingRepository settingRepository = mock(SettingRepository.class);
        SiteRepository siteRepository = mock(SiteRepository.class);
        EmbyRepository embyRepository = mock(EmbyRepository.class);
        FeiniuRepository feiniuRepository = mock(FeiniuRepository.class);
        JellyfinRepository jellyfinRepository = mock(JellyfinRepository.class);
        when(settingRepository.findById("builtin_subscription_sources")).thenReturn(Optional.empty());
        when(siteRepository.findById(1)).thenReturn(Optional.empty());
        when(pluginRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        when(embyRepository.count()).thenReturn(0L);
        when(feiniuRepository.count()).thenReturn(0L);
        when(jellyfinRepository.count()).thenReturn(0L);

        SubscriptionSourceService service = new SubscriptionSourceService(
                new AppProperties(),
                pluginRepository,
                settingRepository,
                siteRepository,
                embyRepository,
                feiniuRepository,
                jellyfinRepository,
                new ObjectMapper()
        );

        assertThat(service.findAll())
                .anySatisfy(source -> assertThat(source)
                        .returns("csp_PianDan", SubscriptionSourceService.ManagedSource::key)
                        .returns("片单导航", SubscriptionSourceService.ManagedSource::name)
                        .returns(true, SubscriptionSourceService.ManagedSource::builtin)
                        .returns(true, SubscriptionSourceService.ManagedSource::enabled));

        // WebHome 首页站与其它内置源同权管理:默认启用且居首位(站点选择器首位)
        List<SubscriptionSourceService.ManagedSource> all = service.findAll();
        assertThat(all.get(0))
                .returns("atv_home", SubscriptionSourceService.ManagedSource::key)
                .returns("影视首页", SubscriptionSourceService.ManagedSource::name)
                .returns(true, SubscriptionSourceService.ManagedSource::builtin)
                .returns(true, SubscriptionSourceService.ManagedSource::enabled)
                .returns(1, SubscriptionSourceService.ManagedSource::sortOrder);
    }

    @Test
    void migrateWebPagesToFrontMovesLegacyRowsOnce() {
        // 存量迁移:首版落底的网页行一次性挪到插件区最前(相对顺序保留),标志防重复
        PluginRepository pluginRepository = mock(PluginRepository.class);
        SettingRepository settingRepository = mock(SettingRepository.class);
        SiteRepository siteRepository = mock(SiteRepository.class);
        EmbyRepository embyRepository = mock(EmbyRepository.class);
        FeiniuRepository feiniuRepository = mock(FeiniuRepository.class);
        JellyfinRepository jellyfinRepository = mock(JellyfinRepository.class);
        when(settingRepository.findById("builtin_subscription_sources")).thenReturn(Optional.empty());
        when(siteRepository.findById(1)).thenReturn(Optional.empty());
        when(embyRepository.count()).thenReturn(0L);
        when(feiniuRepository.count()).thenReturn(0L);
        when(jellyfinRepository.count()).thenReturn(0L);
        when(settingRepository.existsByName("web_pages_front_migrated")).thenReturn(false);

        Plugin pyPlugin = new Plugin();
        pyPlugin.setId(1);
        pyPlugin.setSortOrder(15);
        pyPlugin.setName("爬虫");
        Plugin page1 = new Plugin();
        page1.setId(2);
        page1.setSortOrder(16);
        page1.setName("网页1");
        page1.setUrl("/static/webhome/pages/p1.html");
        Plugin page2 = new Plugin();
        page2.setId(3);
        page2.setSortOrder(17);
        page2.setName("网页2");
        page2.setUrl("/static/webhome/pages/p2.html");
        when(pluginRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(pyPlugin, page1, page2));
        when(pluginRepository.findById(1)).thenReturn(Optional.of(pyPlugin));
        when(pluginRepository.findById(2)).thenReturn(Optional.of(page1));
        when(pluginRepository.findById(3)).thenReturn(Optional.of(page2));

        SubscriptionSourceService service = new SubscriptionSourceService(
                new AppProperties(),
                pluginRepository,
                settingRepository,
                siteRepository,
                embyRepository,
                feiniuRepository,
                jellyfinRepository,
                new ObjectMapper()
        );

        service.migrateWebPagesToFrontOnce();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Plugin>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(pluginRepository).saveAll(captor.capture());
        List<Plugin> saved = captor.getValue();
        assertThat(saved).extracting(Plugin::getId).containsExactly(2, 3, 1);
        verify(settingRepository).save(org.mockito.ArgumentMatchers.argThat(
                arg -> arg != null && "web_pages_front_migrated".equals(arg.getName())));
    }

    @Test
    void moveToFrontOfPluginsInsertsBeforeOtherPlugins() {
        // 新网页源插入第一个非内置源之前(插件区最前),其余源相对顺序不变
        PluginRepository pluginRepository = mock(PluginRepository.class);
        SettingRepository settingRepository = mock(SettingRepository.class);
        SiteRepository siteRepository = mock(SiteRepository.class);
        EmbyRepository embyRepository = mock(EmbyRepository.class);
        FeiniuRepository feiniuRepository = mock(FeiniuRepository.class);
        JellyfinRepository jellyfinRepository = mock(JellyfinRepository.class);
        when(settingRepository.findById("builtin_subscription_sources")).thenReturn(Optional.empty());
        when(siteRepository.findById(1)).thenReturn(Optional.empty());
        when(embyRepository.count()).thenReturn(0L);
        when(feiniuRepository.count()).thenReturn(0L);
        when(jellyfinRepository.count()).thenReturn(0L);

        Plugin pyPlugin = new Plugin();
        pyPlugin.setId(1);
        pyPlugin.setSortOrder(15);
        pyPlugin.setName("爬虫");
        Plugin page = new Plugin();
        page.setId(2);
        page.setSortOrder(16);
        page.setName("网页");
        when(pluginRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(pyPlugin, page));
        when(pluginRepository.findById(1)).thenReturn(Optional.of(pyPlugin));
        when(pluginRepository.findById(2)).thenReturn(Optional.of(page));

        SubscriptionSourceService service = new SubscriptionSourceService(
                new AppProperties(),
                pluginRepository,
                settingRepository,
                siteRepository,
                embyRepository,
                feiniuRepository,
                jellyfinRepository,
                new ObjectMapper()
        );

        service.moveToFrontOfPlugins("plugin-2");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Plugin>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(pluginRepository).saveAll(captor.capture());
        List<Plugin> saved = captor.getValue();
        assertThat(saved).extracting(Plugin::getId).containsExactly(2, 1);
        assertThat(saved.get(0).getSortOrder()).isEqualTo(saved.get(1).getSortOrder() - 1);
    }

    @Test
    void webPageSourceIsNotRefreshable() {
        // 自定义网页源:文件即内容,「刷新」按 spider 插件逻辑无意义,隐藏刷新/配置入口
        PluginRepository pluginRepository = mock(PluginRepository.class);
        SettingRepository settingRepository = mock(SettingRepository.class);
        SiteRepository siteRepository = mock(SiteRepository.class);
        EmbyRepository embyRepository = mock(EmbyRepository.class);
        FeiniuRepository feiniuRepository = mock(FeiniuRepository.class);
        JellyfinRepository jellyfinRepository = mock(JellyfinRepository.class);
        when(settingRepository.findById("builtin_subscription_sources")).thenReturn(Optional.empty());
        when(siteRepository.findById(1)).thenReturn(Optional.empty());
        when(embyRepository.count()).thenReturn(0L);
        when(feiniuRepository.count()).thenReturn(0L);
        when(jellyfinRepository.count()).thenReturn(0L);

        Plugin page = new Plugin();
        page.setId(5);
        page.setSortOrder(10);
        page.setName("电影库");
        page.setUrl("/static/webhome/pages/电影库.html");
        when(pluginRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(page));

        SubscriptionSourceService service = new SubscriptionSourceService(
                new AppProperties(),
                pluginRepository,
                settingRepository,
                siteRepository,
                embyRepository,
                feiniuRepository,
                jellyfinRepository,
                new ObjectMapper()
        );

        assertThat(service.findAll())
                .anySatisfy(source -> assertThat(source)
                        .returns("/static/webhome/pages/电影库.html", SubscriptionSourceService.ManagedSource::url)
                        .returns(false, SubscriptionSourceService.ManagedSource::refreshable)
                        .returns(false, SubscriptionSourceService.ManagedSource::extendable));
    }

    @Test
    void t4VisibilityUpdateAndHiddenSiteKeys() {
        // 「T4」开关:插件侧存 Plugin.t4Visible,内置侧存 builtin settings;t4HiddenSiteKeys
        // 按 pluginSiteKey/内置 siteKey 汇总;update 不带 t4Visible(null)=保持不变(QQ 扫码对话框路径)
        PluginRepository pluginRepository = mock(PluginRepository.class);
        SettingRepository settingRepository = mock(SettingRepository.class);
        SiteRepository siteRepository = mock(SiteRepository.class);
        EmbyRepository embyRepository = mock(EmbyRepository.class);
        FeiniuRepository feiniuRepository = mock(FeiniuRepository.class);
        JellyfinRepository jellyfinRepository = mock(JellyfinRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        when(settingRepository.findById("builtin_subscription_sources")).thenReturn(Optional.empty());
        when(siteRepository.findById(1)).thenReturn(Optional.empty());
        when(embyRepository.count()).thenReturn(0L);
        when(feiniuRepository.count()).thenReturn(0L);
        when(jellyfinRepository.count()).thenReturn(0L);

        Plugin plugin = new Plugin();
        plugin.setId(9);
        plugin.setSortOrder(20);
        plugin.setName("爬虫");
        plugin.setExternalId("hexabc123");
        when(pluginRepository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(plugin));
        when(pluginRepository.findById(9)).thenReturn(Optional.of(plugin));
        // 内置源 state 存 Setting JSON:save 后 findById 须读回(save 打桩默认无效果)
        java.util.Map<String, Setting> store = new java.util.HashMap<>();
        when(settingRepository.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0))));
        when(settingRepository.save(org.mockito.ArgumentMatchers.any(Setting.class)))
                .thenAnswer(inv -> {
                    Setting setting = inv.getArgument(0);
                    store.put(setting.getName(), setting);
                    return setting;
                });

        SubscriptionSourceService service = new SubscriptionSourceService(
                new AppProperties(),
                pluginRepository,
                settingRepository,
                siteRepository,
                embyRepository,
                feiniuRepository,
                jellyfinRepository,
                objectMapper
        );

        assertThat(service.t4HiddenSiteKeys()).isEmpty();
        assertThat(service.findAll().stream()
                .filter(source -> "plugin-9".equals(source.id())).findFirst().orElseThrow()
                .t4Visible()).isTrue();

        // 插件源关闭 T4 可见性 + 内置源 csp_Media 关闭
        service.update("plugin-9", new SubscriptionSourceService.ManagedSourceUpdate("爬虫", true, false, null));
        service.update("builtin-csp_Media", new SubscriptionSourceService.ManagedSourceUpdate("我的追剧", true, false, null));

        assertThat(plugin.isT4Visible()).isFalse();
        assertThat(service.t4HiddenSiteKeys()).containsExactlyInAnyOrder("hexabc123", "csp_Media");

        // null = 保持不变:QQ 扫码对话框只传 name/enabled/extend,不得回滚用户设置
        service.update("plugin-9", new SubscriptionSourceService.ManagedSourceUpdate("爬虫", true, null, "{}"));
        assertThat(plugin.isT4Visible()).isFalse();
    }
}
