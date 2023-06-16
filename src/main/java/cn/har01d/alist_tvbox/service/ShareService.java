package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.ShareInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ShareService {
    private final Environment environment;
    private final ObjectMapper objectMapper;

    public ShareService(Environment environment, ObjectMapper objectMapper) {
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    public String[] getProfiles() {
        return environment.getActiveProfiles();
    }

    public Page<ShareInfo> list(Pageable pageable) {
        if (!Arrays.asList(environment.getActiveProfiles()).contains("xiaoya")) {
            return new PageImpl<>(new ArrayList<>());
        }

        int total = 0;
        List<ShareInfo> list = new ArrayList<>();
        Connection connection = null;
        int size = pageable.getPageSize();
        int offset = pageable.getPageNumber() * size;
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:/opt/alist/data/data.db");
            Statement statement = connection.createStatement();
            String sql = "select count(*) from x_storages where driver = 'AliyundriveShare2Open'";
            ResultSet rs = statement.executeQuery(sql);
            total = rs.getInt(1);
            sql = "select * from x_storages where driver = 'AliyundriveShare2Open' LIMIT " + size + " OFFSET " + offset;
            rs = statement.executeQuery(sql);
            while (rs.next()) {
                ShareInfo shareInfo = new ShareInfo();
                shareInfo.setId(rs.getInt("id"));
                shareInfo.setPath(rs.getString("mount_path"));
                String addition = rs.getString("addition");
                if (StringUtils.isNotBlank(addition)) {
                    Map<String, String> map = objectMapper.readValue(addition, Map.class);
                    shareInfo.setShareId(map.get("share_id"));
                    shareInfo.setPassword(map.get("share_pwd"));
                    shareInfo.setFolderId(map.get("root_folder_id"));
                }
                list.add(shareInfo);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // ignore
                }
            }
        }

        return new PageImpl<>(list, pageable, total);
    }
}
