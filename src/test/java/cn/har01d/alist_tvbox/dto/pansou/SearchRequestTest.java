package cn.har01d.alist_tvbox.dto.pansou;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRequestTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void omitsConcAndRefreshWhenNull() throws Exception {
        SearchRequest request = new SearchRequest("kw", List.of("c"), "all");
        String json = objectMapper.writeValueAsString(request);
        assertThat(json).doesNotContain("\"conc\"").doesNotContain("\"refresh\"");
        assertThat(json).contains("\"res\":\"merge\""); // res is non-null default, still present
    }

    @Test
    void includesConcAndRefreshWhenSet() throws Exception {
        SearchRequest request = new SearchRequest("kw", List.of("c"), "all");
        request.setConc(20);
        request.setRefresh(true);
        String json = objectMapper.writeValueAsString(request);
        assertThat(json).contains("\"conc\":20").contains("\"refresh\":true");
    }
}
