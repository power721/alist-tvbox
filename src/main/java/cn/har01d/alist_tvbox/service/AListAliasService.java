package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.dto.AListAliasDto;
import cn.har01d.alist_tvbox.entity.AListAlias;
import cn.har01d.alist_tvbox.entity.AListAliasRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service

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

    @PostConstruct
    public void init() {
        shareId += aliasRepository.count();
    }

    public AListAlias create(AListAliasDto dto) {
        aListLocalService.validateAListStatus();
        validate(dto);
        AListAlias alias = new AListAlias(dto);
        try {
            String token = accountService.login();
            alias.setId(shareId++);
            String sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Alias',0,'work','{\"paths\":\"%s\"}','','2023-06-20 12:00:00+00:00',1,'name','asc','front',0,'302_redirect','',0);";
            int count = Utils.executeUpdate(String.format(sql, alias.getId(), alias.getPath(), Utils.getAliasPaths(alias.getContent())));
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
        try {
            shareService.deleteStorage(id, token);

            String sql = "DELETE FROM x_storages WHERE id = " + id;
            Utils.executeUpdate(sql);

            sql = "INSERT INTO x_storages VALUES(%d,'%s',0,'Alias',0,'work','{\"paths\":\"%s\"}','','2023-06-20 12:00:00+00:00',1,'name','asc','front',0,'302_redirect','',0);";
            int count = Utils.executeUpdate(String.format(sql, alias.getId(), alias.getPath(), Utils.getAliasPaths(alias.getContent())));
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
