package cn.har01d.alist_tvbox.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SiteDtoJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsLegacyOrderAndVersionJsonNames() throws Exception {
        SiteDto dto = objectMapper.readValue("""
                {
                  "name": "本地",
                  "url": "http://localhost",
                  "order": 7,
                  "version": 4
                }
                """, SiteDto.class);

        assertThat(dto.getSortOrder()).isEqualTo(7);
        assertThat(dto.getStorageVersion()).isEqualTo(4);
    }

    @Test
    void writesLegacyOrderAndVersionJsonNames() throws Exception {
        SiteDto dto = new SiteDto();
        dto.setName("本地");
        dto.setUrl("http://localhost");
        dto.setSortOrder(7);
        dto.setStorageVersion(4);

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"order\":7");
        assertThat(json).contains("\"version\":4");
        assertThat(json).doesNotContain("sortOrder");
        assertThat(json).doesNotContain("storageVersion");
    }
}
