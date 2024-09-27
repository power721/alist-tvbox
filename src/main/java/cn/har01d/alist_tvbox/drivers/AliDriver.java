package cn.har01d.alist_tvbox.drivers;

import cn.har01d.alist_tvbox.dto.AliFileList;
import cn.har01d.alist_tvbox.util.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
public class AliDriver implements Driver {
    public static final String API_BATCH = "https://api.alipan.com/adrive/v2/batch";
    public static final String API_LIST = "https://api.aliyundrive.com/adrive/v2/file/list_by_share";
    private final Share share;
    private final RestTemplate restTemplate;
    private final String driveId = UUID.randomUUID().toString();

    public AliDriver(Share share, RestTemplateBuilder builder) {
        this.share = share;
        String deviceID = UUID.randomUUID().toString().replace("-", "");
        this.restTemplate = builder
                .defaultHeader(HttpHeaders.ACCEPT, Constants.ACCEPT)
                .defaultHeader(HttpHeaders.USER_AGENT, Constants.USER_AGENT)
                .defaultHeader(HttpHeaders.REFERER, "https://www.alipan.com/")
                .defaultHeader("X-Device-Id", deviceID)
                .defaultHeader("X-Share-Token", getShareToken())
                .defaultHeader("X-Canary", "client=web,app=share,version=v2.3.1")
                .build();
    }

    private String getShareToken() {
        // get share token
        return "";
    }

    @Override
    public DriverType getType() {
        return DriverType.ALI;
    }

    @Override
    public DriverLink link(String id) {
        String body = """
                {
                	"requests": [{
                		"body": {
                			"file_id": "%s",
                			"share_id": "%s",
                			"auto_rename": true,
                			"to_parent_file_id": "root",
                			"to_drive_id": "%s"
                		},
                		"headers": {
                			"Content-Type": "application/json"
                		},
                		"id": "0",
                		"method": "POST",
                		"url": "/file/copy"
                	}],
                	"resource": "file"
                }
                """.formatted(id, share.id(), driveId);
        return new DriverLink("", Map.of());
    }

    @Override
    public void delete(String id) {
        String body = """
                {
                	"requests": [{
                		"body": {
                			"drive_id": "%s",
                			"file_id": "%s"
                		},
                		"headers": {
                			"Content-Type": "application/json"
                		},
                		"id": "%s",
                		"method": "POST",
                		"url": "/file/delete"
                	}],
                	"resource": "file"
                }
                """.formatted(driveId, id, id);
        HttpEntity<String> entity = new HttpEntity<>(body);
        ResponseEntity<String> response = restTemplate.exchange(API_BATCH, HttpMethod.POST, entity, String.class);
    }

    @Override
    public List<DriverFile> list(String parentId) {
        List<DriverFile> files = new ArrayList<>();
        String marker = "";
        do {
            var fsResponse = listFiles(parentId, marker);
            for (var item : fsResponse.getItems()) {
                files.add(new DriverFile(item.getFileId(), item.getName(), item.getType()));
            }
            marker = fsResponse.getNext();
        } while (StringUtils.isNotEmpty(marker));
        return files;
    }

    private AliFileList listFiles(String parentId, String marker) {
        Exception exception = null;
        for (int i = 0; i < 20; i++) {
            Map<String, Object> body = new HashMap<>();
            body.put("share_id", share.id());
            body.put("limit", 200);
            body.put("order_by", "name");
            body.put("order_direction", "ASC");
            body.put("parent_file_id", parentId);
            body.put("marker", marker);
            HttpEntity<Object> entity = new HttpEntity<>(body);

            try {
                ResponseEntity<AliFileList> response = restTemplate.exchange(API_LIST, HttpMethod.POST, entity, AliFileList.class);
                return response.getBody();
            } catch (HttpClientErrorException.TooManyRequests e) {
                exception = e;
                log.warn("Too many requests: {} {}", i + 1, parentId);
            } catch (HttpClientErrorException.Unauthorized e) {
                if (e.getMessage().contains("ShareLinkToken is invalid")) {
                    //share.setToken(aListService.getShareInfo(context.getSite(), path).getShareToken());
                } else {
                    throw e;
                }
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        log.error("list files failed {}", parentId, exception);
        return new AliFileList();
    }
}
