package cn.har01d.alist_tvbox.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MediaSubscriptionEpisodeSourceRepository extends JpaRepository<MediaSubscriptionEpisodeSource, Integer> {
    List<MediaSubscriptionEpisodeSource> findByResourceId(int resourceId);

    Optional<MediaSubscriptionEpisodeSource> findByEpisodeIdAndResourceId(int episodeId, int resourceId);

    /** 派生删除 = 先 select 再逐个 em.remove,必须在事务里执行(巡检后台线程无外围事务会抛 TransactionRequiredException)。 */
    @Transactional
    void deleteByResourceId(int resourceId);

    /** 同 {@link #deleteByResourceId}:声明方法级事务,无外围事务时自开、有则加入。 */
    @Transactional
    void deleteByResourceIdIn(Collection<Integer> resourceIds);

    long countByResourceId(int resourceId);

    /** 某订阅某集的全部集源行(各状态,调用方自行过滤/排序)。 */
    @Query("select s from MediaSubscriptionEpisodeSource s join MediaSubscriptionEpisode e on s.episodeId = e.id"
            + " where e.subscriptionId = ?1 and e.number = ?2")
    List<MediaSubscriptionEpisodeSource> findBySubscriptionAndNumber(int subscriptionId, int number);

    /** 某订阅全部 (集号, 集源行) 投影 —— 逐集资源矩阵(集数页签)用。 */
    @Query("select e.number, s from MediaSubscriptionEpisode e join MediaSubscriptionEpisodeSource s on s.episodeId = e.id"
            + " where e.subscriptionId = ?1")
    List<Object[]> findNumberAndSource(int subscriptionId);

    /** 某订阅处于指定状态的全部集源行 —— 可用性派生查询(LISTED/VERIFIED = 可播)。 */
    @Query("select s from MediaSubscriptionEpisodeSource s join MediaSubscriptionEpisode e on s.episodeId = e.id"
            + " where e.subscriptionId = ?1 and s.state in ?2")
    List<MediaSubscriptionEpisodeSource> findBySubscriptionAndStatesIn(int subscriptionId, Collection<String> states);

    /** 某订阅处于指定状态的集号集合(可用性派生:LISTED/VERIFIED ∪ = 本地已有集)。
     * <b>只统计 MOUNTED 资源</b> —— 候选探测后留下的行(资源还在池里没挂载)不得冒充本地已有集。 */
    @Query("select distinct e.number from MediaSubscriptionEpisode e"
            + " join MediaSubscriptionEpisodeSource s on s.episodeId = e.id"
            + " join MediaSubscriptionResource r on s.resourceId = r.id"
            + " where e.subscriptionId = ?1 and s.state in ?2 and r.state = 'MOUNTED'")
    List<Integer> findNumbersBySubscriptionAndStatesIn(int subscriptionId, Collection<String> states);

    /** 每个资源已记录的分集文件大小平均数(候选池"单集平均体积"列)。null fileSize 行不计入。 */
    @Query("select s.resourceId, avg(s.fileSize) from MediaSubscriptionEpisodeSource s"
            + " where s.resourceId in ?1 and s.fileSize is not null group by s.resourceId")
    List<Object[]> findAvgFileSizeGroupByResourceId(Collection<Integer> resourceIds);

    /** 某资源处于指定状态的分集集号(探测覆盖快照的替代品)。 */
    @Query("select e.number from MediaSubscriptionEpisode e join MediaSubscriptionEpisodeSource s on s.episodeId = e.id"
            + " where s.resourceId = ?1 and s.state in ?2")
    List<Integer> findNumbersByResourceIdAndStatesIn(int resourceId, Collection<String> states);
}
