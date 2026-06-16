package cn.har01d.alist_tvbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservedColumnMappingTest {

    @Test
    void siteUsesSafeDatabaseColumnNames() throws Exception {
        Field sortOrder = Site.class.getDeclaredField("sortOrder");
        assertThat(sortOrder.getAnnotation(Column.class).name()).isEqualTo("sort_order");

        Field storageVersion = Site.class.getDeclaredField("storageVersion");
        assertThat(storageVersion.getAnnotation(Column.class).name()).isEqualTo("storage_version");
        assertThat(storageVersion.getAnnotation(Version.class)).isNull();

        assertThatThrownBy(() -> Site.class.getDeclaredField("order"))
                .isInstanceOf(NoSuchFieldException.class);
        assertThatThrownBy(() -> Site.class.getDeclaredField("version"))
                .isInstanceOf(NoSuchFieldException.class);
    }

    @Test
    void orderedEntitiesUseSortOrderColumn() throws Exception {
        assertSortOrderColumn(Navigation.class);
        assertSortOrderColumn(Emby.class);
        assertSortOrderColumn(Jellyfin.class);
        assertSortOrderColumn(TelegramChannel.class);
        assertSortOrderColumn(Feiniu.class);
    }

    private void assertSortOrderColumn(Class<?> type) throws Exception {
        Field sortOrder = type.getDeclaredField("sortOrder");
        assertThat(sortOrder.getAnnotation(Column.class).name()).isEqualTo("sort_order");
        assertThatThrownBy(() -> type.getDeclaredField("order"))
                .isInstanceOf(NoSuchFieldException.class);
    }
}
