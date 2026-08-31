package cn.har01d.alist_tvbox.dto;

import cn.har01d.alist_tvbox.entity.Movie;

import java.util.List;

/**
 * 豆瓣 diff JSON 文件(json/{version}.json)的载荷契约,由 xiaoya-douban 导出端生成。
 * <p>
 * 键名与实体字段名对齐:MOVIE 行直接是 {@link Movie} 的字段;META 行因带 ManyToOne 关系
 * 用 {@link MetaPayload}(movieId/tmdbId 为裸 id,应用侧再换实体引用)。
 * deletes 为裸 id 列表(源库已移除或旧版本行,先删后插语义下由 upsert 覆盖)。
 */
public record MovieDiffPayload(List<Movie> movieUpserts, List<Integer> movieDeletes,
                               List<MetaPayload> metaUpserts, List<Integer> metaDeletes) {

    /** META 表行:字段名对齐 Meta 实体,关联列降为裸 id;time 为 epoch 毫秒。 */
    public record MetaPayload(Integer id, String path, String name, Integer year, Integer score,
                              Integer movieId, String type, Integer tid, Integer tmId,
                              Integer tmdbId, Integer siteId, Boolean disabled, Long time) {
    }
}
