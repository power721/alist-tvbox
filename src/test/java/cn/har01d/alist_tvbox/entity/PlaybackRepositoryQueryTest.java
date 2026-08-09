package cn.har01d.alist_tvbox.entity;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    private Class<?> domainType(Class<?> repository) {
        for (Type type : repository.getGenericInterfaces()) {
            if (type instanceof ParameterizedType parameterized) {
                return (Class<?>) parameterized.getActualTypeArguments()[0];
            }
        }
        throw new IllegalStateException("no domain type on " + repository);
    }
}
