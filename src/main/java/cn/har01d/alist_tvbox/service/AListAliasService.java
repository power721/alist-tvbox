package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.AListAliasDto;
import cn.har01d.alist_tvbox.entity.AListAlias;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.Constants;
import cn.har01d.alist_tvbox.util.Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

@Slf4j
@Service
@Profile("xiaoya")
public class AListAliasService {
    private final AListAliasRepository aliasRepository;
    private final AccountService accountService;
    private final ShareService shareService;
    private final AListLocalService aListLocalService;

    private volatile int shareId = 6000;

    public AListAliasService(AListAliasRepository aliasRepository,
                             AccountService accountService,
                             ShareService shareService,
                             AListLocalService aListLocalService) {
        this.aliasRepository = aliasRepository;
        this.accountService = accountService;
        this.shareService = shareService;
        this.aListLocalService = aListLocalService;
    }

    public AListAlias create(AListAliasDto dto) {
        aListLocalService.validateAListStatus();
        validate(dto);
        AListAlias alias = new AListAlias(dto);
        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            String token = accountService.login();
            alias.setId(shareId++);
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Alias',0,'work','{\"paths\":\"%s\"}','','2023-06-20 12:00:00+00:00',1,'name','asc','front',0,'302_redirect','');";
            int count = statement.executeUpdate(String.format(sql, alias.getId(), alias.getPath(), Utils.getAliasPaths(alias.getContent())));
            log.info("insert alias {}: {}, result: {}", alias.getId(), alias.getPath(), count);
            aliasRepository.save(alias);
            shareService.enableStorage(alias.getId(), token);
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
        return alias;
    }

    public AListAlias update(Integer id, AListAliasDto dto) {
        aListLocalService.validateAListStatus();
        validate(dto);

        AListAlias alias = new AListAlias(dto);
        alias.setId(id);
        aliasRepository.save(alias);

        String token = accountService.login();
        try (Connection connection = DriverManager.getConnection(Constants.DB_URL);
             Statement statement = connection.createStatement()) {
            shareService.deleteStorage(id, token);

            String sql = "DELETE FROM x_storages WHERE id = " + id;
            statement.executeUpdate(sql);

            sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Alias',0,'work','{\"paths\":\"%s\"}','','2023-06-20 12:00:00+00:00',1,'name','asc','front',0,'302_redirect','');";
            int count = statement.executeUpdate(String.format(sql, alias.getId(), alias.getPath(), Utils.getAliasPaths(alias.getContent())));
            log.info("update alias {}: {}, result: {}", alias.getId(), alias.getPath(), count);

            shareService.enableStorage(id, token);
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
        return alias;
    }

    private static void validate(AListAliasDto dto) {
        if (StringUtils.isBlank(dto.getPath())) {
            throw new BadRequestException("挂载路径不能为空");
        }
        if (!dto.getPath().startsWith("/")) {
            throw new BadRequestException("挂载路径必须/以开头");
        }
        if (StringUtils.isBlank(dto.getContent())) {
            throw new BadRequestException("内容不能为空");
        }
        for (String path : dto.getContent().split("\n")) {
            if (!path.startsWith("/")) {
                throw new BadRequestException("别名路径必须/以开头");
            }
        }
    }

    public void delete(Integer id) {
        aListLocalService.validateAListStatus();
        aliasRepository.deleteById(id);
        String token = accountService.login();
        shareService.deleteStorage(id, token);
    }

}
