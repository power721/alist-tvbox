package cn.har01d.alist_tvbox.storage;

import cn.har01d.alist_tvbox.entity.Share;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class StrmStorage extends Storage {
    private static final ObjectMapper mapper = new ObjectMapper();
    
    public StrmStorage(Share share) {
        super(share, "Strm");
        
        try {
            parseStrmConfig(share.getFolderId());
        } catch (Exception e) {
            log.error("Failed to parse STRM config", e);
            throw new RuntimeException("STRM配置解析失败: " + e.getMessage());
        }
    }
    
    private void parseStrmConfig(String configJson) throws Exception {
        if (StringUtils.isBlank(configJson)) {
            throw new IllegalArgumentException("STRM配置不能为空");
        }
        
        // 解析 JSON
        JsonNode config = mapper.readTree(configJson);
        
        // 构建 addition 字段，注意字段名转换：camelCase -> PascalCase
        addAddition("paths", config.get("paths").asText());
        addAddition("siteUrl", config.get("siteUrl").asText());
        addAddition("PathPrefix", config.get("pathPrefix").asText("/d"));
        addAddition("downloadFileTypes", config.get("downloadFileTypes").asText("ass,srt,vtt,sub,strm"));
        addAddition("filterFileTypes", config.get("filterFileTypes").asText("mp4,mkv,flv,avi,wmv,ts,rmvb,webm,mp3,flac,aac,wav,ogg,m4a,wma,alac"));
        addAddition("encodePath", config.get("encodePath").asBoolean(false));
        addAddition("withoutUrl", config.get("withoutUrl").asBoolean(false));
        addAddition("withSign", config.get("withSign").asBoolean(false));
        addAddition("SaveStrmToLocal", config.get("saveStrmToLocal").asBoolean(false));
        addAddition("SaveStrmLocalPath", config.get("saveStrmLocalPath").asText(""));
        addAddition("SaveLocalMode", config.get("saveLocalMode").asText("update"));
        
        buildAddition();
    }
}
