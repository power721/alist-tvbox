package cn.har01d.alist_tvbox.dto.pansou;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Data
public class SearchResult {
    @JsonProperty("message_id")
    private String messageId;
    @JsonProperty("unique_id")
    private String uniqueId;
    private String channel;
    private Instant datetime;
    private String title;
    private String content;
    private List<Link> links;
    private List<String> tags;

    public String toPgString() {
        return datetime.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "\t" + channel + "\t" + content.replace('\n', ' ') + "\t" + messageId;
    }
}
