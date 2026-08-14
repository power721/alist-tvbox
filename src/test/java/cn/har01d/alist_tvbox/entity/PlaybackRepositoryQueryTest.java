package cn.har01d.alist_tvbox.entity;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 派生查询方法名必须与参数个数、实体属性对得上。
 * <p>
 * 这类错误(例如谓词含 uid 却没声明 uid 参数)在单元测试里发现不了 —— mock 仓库不解析方法名 ——
 * 而 Spring Data 是在**启动时**急切创建查询的:一旦对不上,整个应用起不来,该端点每次调用都 500。
 * 这里直接用 Spring Data 自己的 {@link PartTree} 解析器做同样的校验,不必启动 Spring 上下文。
 */
class PlaybackRepositoryQueryTest {

    @Test
    void derivedQueriesDeclareEveryBindableParameter() {
        assertDerivedQueries(PlaybackTombstoneRepository.class, PlaybackTombstone.class);
        assertDerivedQueries(PlaybackTokenRepository.class, PlaybackToken.class);
        assertDerivedQueries(HistoryRepository.class, History.class);
    }

    private void assertDerivedQueries(Class<?> repository, Class<?> domain) {
        for (Method method : repository.getDeclaredMethods()) {
            // @Query 方法是手写 JPQL,不经过派生查询解析,跳过
            if (method.isAnnotationPresent(Query.class)) {
                continue;
            }
            // PartTree 解析失败(属性名不存在等)会抛异常,等价于启动期报错
            PartTree tree = new PartTree(method.getName(), domain);
            int required = 0;
            for (Part part : tree.getParts()) {
                required += part.getNumberOfArguments();
            }
            assertThat(bindableParameters(method))
                    .as("%s.%s 的谓词需要 %d 个绑定参数", repository.getSimpleName(), method.getName(), required)
                    .isEqualTo(required);
        }
    }

    /** 可绑定参数 = 方法参数中排除 Sort/Pageable/Limit 这类分页排序参数。 */
    private int bindableParameters(Method method) {
        int count = 0;
        for (Class<?> type : method.getParameterTypes()) {
            if (Sort.class.isAssignableFrom(type) || Pageable.class.isAssignableFrom(type)
                    || Limit.class.isAssignableFrom(type)) {
                continue;
            }
            count++;
        }
        return count;
    }

    /** 兜底:确保上面的断言真的覆盖到了方法,而不是因为反射拿到空列表而空跑。 */
    @Test
    void repositoriesExposeDerivedQueries() {
        List<String> names = new ArrayList<>();
        for (Method method : PlaybackTombstoneRepository.class.getDeclaredMethods()) {
            names.add(method.getName());
        }
        assertThat(names).contains("findByUidAndChangeSeqGreaterThan", "deleteByExpireAtBefore");
        assertThat(domainType(PlaybackTombstoneRepository.class)).isEqualTo(PlaybackTombstone.class);
    }

    /**
     * 管理端/网页删除的墓碑落在 uid 全局分区(sync_scope IS NULL)。scoped 客户端若看不见这些墓碑,
     * 删除对其不可见、记录被下次 PUSH 复活——故每个 scoped 墓碑 @Query 都必须含 {@code t.syncScope IS NULL}。
     * mock 仓库不解析 JPQL,这类谓词回归只能在查询字符串层面守住。
     */
    @Test
    void scopedTombstoneQueriesIncludeUidGlobalRows() {
        List<String> violations = new ArrayList<>();
        int scopedCount = 0;
        for (Method method : PlaybackTombstoneRepository.class.getDeclaredMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query == null || !method.getName().startsWith("findSync")) {
                continue;
            }
            scopedCount++;
            if (!query.value().contains("t.syncScope IS NULL")) {
                violations.add(method.getName());
            }
        }
        assertThat(scopedCount)
                .as("必须扫到 scoped 墓碑查询,否则本断言空跑")
                .isGreaterThanOrEqualTo(7);
        assertThat(violations)
                .as("scoped 墓碑查询必须含 `t.syncScope IS NULL`,uid 全局删除才能下达 scoped 客户端")
                .isEmpty();
    }

    private Class<?> domainType(Class<?> repository) {
        for (Type type : repository.getGenericInterfaces()) {
            if (type instanceof ParameterizedType parameterized) {
                return (Class<?>) parameterized.getActualTypeArguments()[0];
            }
        }
        throw new IllegalStateException("no domain type on " + repository);
    }
}
