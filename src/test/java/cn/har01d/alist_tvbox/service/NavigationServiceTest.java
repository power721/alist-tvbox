package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.Navigation;
import cn.har01d.alist_tvbox.entity.NavigationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NavigationServiceTest {

    @Test
    void resolvesNameByValue() {
        NavigationRepository repository = Mockito.mock(NavigationRepository.class);
        when(repository.findFirstByValue("130")).thenReturn(Optional.of(new Navigation("音乐综合", "130", 2, true, true, 1)));
        NavigationService service = new NavigationService(repository);

        assertEquals("音乐综合", service.getNameByValue("130"));
        assertNull(service.getNameByValue("999"));
    }

    @Test
    void resolvesParentValueForSubRegion() {
        NavigationRepository repository = Mockito.mock(NavigationRepository.class);
        when(repository.findFirstByValueAndType("130", 2))
                .thenReturn(Optional.of(new Navigation("音乐综合", "130", 2, true, true, 1, 42)));
        when(repository.findById(42)).thenReturn(Optional.of(new Navigation(42, "音乐", "3", 1, true, true, 8)));
        NavigationService service = new NavigationService(repository);

        assertEquals("3", service.getParentValue("130"));
        assertNull(service.getParentValue("167")); // 主分区无父级
    }

    @Test
    void renamesOnlySubCategoriesStillUsingOldNames() {
        NavigationRepository repository = Mockito.mock(NavigationRepository.class);
        NavigationService service = new NavigationService(repository);
        Navigation oldName = new Navigation("大熊猫", "220", 2, true, true, 1, 50); // B 站改版前的旧名 → 更新
        Navigation customized = new Navigation("我的手书区", "47", 2, true, true, 1, 51); // 用户自定义名 → 不动
        Navigation alreadyRenamed = new Navigation("同人·手书", "47", 2, true, true, 1, 52); // 已是新名 → 跳过
        Navigation mainRegion = new Navigation("动物圈", "217", 1, true, true, 15); // 主分区不在校正范围
        oldName.setId(9);
        customized.setId(10);
        alreadyRenamed.setId(11);
        mainRegion.setId(12);

        service.renameRenamedCategories(List.of(oldName, customized, alreadyRenamed, mainRegion));

        assertEquals("动物二创", oldName.getName());
        assertEquals("我的手书区", customized.getName());
        assertEquals("同人·手书", alreadyRenamed.getName());
        assertEquals("动物圈", mainRegion.getName());
        verify(repository).save(oldName);
        verify(repository, never()).save(customized);
        verify(repository, never()).save(alreadyRenamed);
    }

    @Test
    void setupInsertsNewSubCategoriesForExistingInstances() {
        NavigationRepository repository = Mockito.mock(NavigationRepository.class);
        List<Navigation> rows = new ArrayList<>();
        int id = 1;
        for (String[] p : new String[][]{{"国创", "167"}, {"知识", "36"}, {"动画", "1"}, {"音乐", "3"},
                {"汽车", "223"}, {"娱乐", "5"}, {"影视", "181"}, {"舞蹈", "129"}, {"科技", "188"}, {"生活", "160"}}) {
            Navigation row = new Navigation(p[0], p[1], 1, true, true, id);
            row.setId(id++);
            rows.add(row);
        }
        when(repository.count()).thenReturn(1L);
        when(repository.findAll()).thenReturn(rows);
        NavigationService service = new NavigationService(repository);

        service.setup();

        org.mockito.ArgumentCaptor<Navigation> captor = org.mockito.ArgumentCaptor.forClass(Navigation.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        int techId = rows.stream().filter(e -> "188".equals(e.getValue())).findFirst().orElseThrow().getId();
        int lifeId = rows.stream().filter(e -> "160".equals(e.getValue())).findFirst().orElseThrow().getId();
        assertTrue(captor.getAllValues().stream().anyMatch(e ->
                "232".equals(e.getValue()) && "科工机械".equals(e.getName()) && e.getParentId() == techId));
        assertTrue(captor.getAllValues().stream().anyMatch(e ->
                "254".equals(e.getValue()) && "亲子".equals(e.getName()) && e.getParentId() == lifeId));
    }
}
