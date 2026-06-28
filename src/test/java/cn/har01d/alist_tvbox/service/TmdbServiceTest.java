package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.TaskResult;
import cn.har01d.alist_tvbox.domain.TaskStatus;
import cn.har01d.alist_tvbox.entity.Meta;
import cn.har01d.alist_tvbox.entity.MetaRepository;
import cn.har01d.alist_tvbox.entity.SettingRepository;
import cn.har01d.alist_tvbox.entity.Task;
import cn.har01d.alist_tvbox.entity.Tmdb;
import cn.har01d.alist_tvbox.entity.TmdbMeta;
import cn.har01d.alist_tvbox.entity.TmdbMetaRepository;
import cn.har01d.alist_tvbox.entity.TmdbRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.restclient.RestTemplateBuilder;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TmdbServiceTest {
    @Mock
    private TmdbRepository tmdbRepository;
    @Mock
    private TmdbMetaRepository tmdbMetaRepository;
    @Mock
    private MetaRepository metaRepository;
    @Mock
    private SettingRepository settingRepository;
    @Mock
    private SiteService siteService;
    @Mock
    private TaskService taskService;

    @TempDir
    Path tempDir;

    @Test
    void scrapeIndexFileShouldNotLeakSiteIdAcrossConcurrentRuns() throws Exception {
        System.setProperty("atv.data.dir", tempDir.toString());
        try {
            TmdbService service = new TmdbService(
                    tmdbRepository,
                    tmdbMetaRepository,
                    metaRepository,
                    settingRepository,
                    siteService,
                    taskService,
                    new RestTemplateBuilder(),
                    new ObjectMapper()
            );

            Task task1 = runningTask(1);
            Task task2 = runningTask(2);

            when(taskService.getById(1)).thenReturn(task1);
            when(taskService.getById(2)).thenReturn(task2);
            when(metaRepository.existsByPath(anyString())).thenReturn(false);
            when(tmdbMetaRepository.findByPath(anyString())).thenReturn(null);
            when(metaRepository.findByPath(anyString())).thenReturn(null);
            when(metaRepository.save(any(Meta.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(tmdbRepository.getByName(anyString())).thenAnswer(invocation -> List.of(movie(invocation.getArgument(0))));

            List<TmdbMeta> savedMetas = new CopyOnWriteArrayList<>();
            doAnswer(invocation -> {
                savedMetas.add(copy(invocation.getArgument(0)));
                return invocation.getArgument(0);
            }).when(tmdbMetaRepository).save(any(TmdbMeta.class));

            CountDownLatch firstTaskSavedFirstLine = new CountDownLatch(1);
            CountDownLatch secondTaskFinished = new CountDownLatch(1);
            doAnswer(invocation -> {
                Integer taskId = invocation.getArgument(0);
                if (taskId == 1 && firstTaskSavedFirstLine.getCount() == 1) {
                    firstTaskSavedFirstLine.countDown();
                    assertTrue(secondTaskFinished.await(5, TimeUnit.SECONDS));
                }
                return null;
            }).when(taskService).updateTaskData(anyInt(), anyString());

            List<String> firstLines = List.of("/site-1/alpha#Alpha", "/site-1/beta#Beta");
            List<String> secondLines = List.of("/site-2/gamma#Gamma");

            Thread first = new Thread(() -> {
                try {
                    setSiteId(service, 1);
                    service.scrapeIndexFile(task1, firstLines, false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "tmdb-scrape-1");

            Thread second = new Thread(() -> {
                try {
                    assertTrue(firstTaskSavedFirstLine.await(5, TimeUnit.SECONDS));
                    setSiteId(service, 2);
                    service.scrapeIndexFile(task2, secondLines, false);
                    secondTaskFinished.countDown();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "tmdb-scrape-2");

            first.start();
            second.start();
            first.join();
            second.join();

            List<Integer> firstTaskSiteIds = new ArrayList<>();
            for (TmdbMeta meta : savedMetas) {
                if (meta.getPath().startsWith("/site-1/")) {
                    firstTaskSiteIds.add(meta.getSiteId());
                }
            }

            assertEquals(List.of(1, 1), firstTaskSiteIds);
            assertTrue(tempDir.resolve("atv/tmdb_paths.txt").toFile().exists());
            assertTrue(tempDir.resolve("atv/tmdb_failed.txt").toFile().exists());
        } finally {
            System.clearProperty("atv.data.dir");
        }
    }

    private static Task runningTask(int id) {
        Task task = new Task();
        task.setId(id);
        task.setStatus(TaskStatus.RUNNING);
        task.setResult(TaskResult.OK);
        return task;
    }

    private static Tmdb movie(String name) {
        Tmdb tmdb = new Tmdb();
        tmdb.setTmdbId(name.hashCode());
        tmdb.setName(name);
        tmdb.setType("movie");
        tmdb.setYear(2024);
        tmdb.setScore("8.0");
        return tmdb;
    }

    private static TmdbMeta copy(TmdbMeta source) {
        TmdbMeta meta = new TmdbMeta();
        meta.setPath(source.getPath());
        meta.setSiteId(source.getSiteId());
        meta.setName(source.getName());
        meta.setTmId(source.getTmId());
        meta.setType(source.getType());
        meta.setYear(source.getYear());
        meta.setScore(source.getScore());
        meta.setTmdb(source.getTmdb());
        return meta;
    }

    @SuppressWarnings("unchecked")
    private static void setSiteId(TmdbService service, int value) throws Exception {
        Field field = TmdbService.class.getDeclaredField("siteId");
        field.setAccessible(true);
        Object current = field.get(service);
        if (current instanceof ThreadLocal<?> threadLocal) {
            ((ThreadLocal<Integer>) threadLocal).set(value);
        } else {
            field.set(service, value);
        }
    }
}
