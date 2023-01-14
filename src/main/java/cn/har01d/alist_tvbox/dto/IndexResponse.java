package cn.har01d.alist_tvbox.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class IndexResponse extends IndexRequest {
    private String filePath;

    public IndexResponse() {
    }

    public IndexResponse(IndexRequest request) {
        setSite(request.getSite());
        setSiteId(request.getSiteId());
        setIndexName(request.getIndexName());
        setExcludeExternal(request.isExcludeExternal());
        setCompress(request.isCompress());
        setPaths(request.getPaths());
        setExcludes(request.getExcludes());
        setStopWords(request.getStopWords());
        setMaxDepth(request.getMaxDepth());
    }
}
