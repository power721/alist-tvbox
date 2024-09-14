package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.entity.PikPakAccount;
import cn.har01d.alist_tvbox.entity.PikPakAccountRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.exception.NotFoundException;
import cn.har01d.alist_tvbox.util.Utils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Slf4j
@Service

public class PikPakService {
    private final PikPakAccountRepository pikPakAccountRepository;
    private final AccountService accountService;
    private final AListLocalService aListLocalService;

    public PikPakService(PikPakAccountRepository pikPakAccountRepository, AccountService accountService, AListLocalService aListLocalService) {
        this.pikPakAccountRepository = pikPakAccountRepository;
        this.accountService = accountService;
        this.aListLocalService = aListLocalService;
    }

    @PostConstruct
    public void setup() {
        pikPakAccountRepository.getFirstByMasterTrue().ifPresent(this::updateAList);
    }

    public void readPikPak() {
        if (pikPakAccountRepository.count() > 0) {
            return;
        }

        Path file = Paths.get("/data/pikpak.txt");
        if (Files.exists(file)) {
            log.info("read PikPak account from file");
            try {
                String line = Files.readString(file);
                String[] parts = line.split("\\s+");
                if (parts.length == 2) {
                    PikPakAccount account = new PikPakAccount();
                    account.setNickname("PikPak");
                    account.setUsername(fix(parts[0]));
                    account.setPassword(fix(parts[1]));
                    account.setMaster(true);
                    pikPakAccountRepository.save(account);
                    log.info("add PikPak account {} {}: {}", account.getId(), account.getNickname(), account.getUsername());
                }
            } catch (Exception e) {
                log.warn("", e);
            }
        }

        readPikPakAccounts();
    }

    private void readPikPakAccounts() {
        Path file = Paths.get("/data/pikpak_list.txt");
        if (Files.exists(file)) {
            log.info("read PikPak accounts from file");
            try {
                List<String> lines = Files.readAllLines(file);
                for (String line : lines) {
                    String[] parts = line.split("\\s+");
                    if (parts.length == 3) {
                        String path = parts[0];
                        String username = fix(parts[1]);
                        String password = fix(parts[2]);

                        PikPakAccount account = new PikPakAccount();
                        account.setNickname(path);
                        account.setUsername(username);
                        account.setPassword(password);
                        pikPakAccountRepository.save(account);
                        log.info("add PikPak account {} {}: {}", account.getId(), account.getNickname(), account.getUsername());
                    }
                }
            } catch (Exception e) {
                log.warn("", e);
            }
        }
    }

    public void loadPikPak() {
        try {
            List<PikPakAccount> list = pikPakAccountRepository.findAll();
            for (PikPakAccount account : list) {
                String sql = "INSERT INTO x_storages VALUES(%d,\"/\uD83C\uDD7F️我的PikPak/%s\",0,'PikPak',30,'work','{\"root_folder_id\":\"\",\"username\":\"%s\",\"password\":\"%s\"}','','2023-06-20 12:00:00+00:00',0,'','','',0,'302_redirect','');";
                Utils.executeUpdate(String.format(sql, 8000 + account.getId(), account.getNickname(), account.getUsername(), account.getPassword()));
            }
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    private String fix(String text) {
        if (text.startsWith("\"")) {
            text = text.substring(1);
        }
        if (text.endsWith("\"")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private void validate(PikPakAccount dto) {
        if (StringUtils.isBlank(dto.getNickname())) {
            throw new BadRequestException("账号昵称不能为空");
        }
        if (dto.getNickname().contains("/")) {
            throw new BadRequestException("账号昵称不能包含/");
        }
        if (StringUtils.isBlank(dto.getUsername())) {
            throw new BadRequestException("用户名不能为空");
        }
        if (StringUtils.isBlank(dto.getPassword())) {
            throw new BadRequestException("账号密码不能为空");
        }
    }

    public void updateIndexFile() {
        log.info("update PikPak index file");
        ProcessBuilder builder = new ProcessBuilder();
        builder.command("sh", "-c", "/index.sh");
        builder.inheritIO();
        try {
            builder.start();
        } catch (Exception e) {
            log.warn("", e);
        }
    }

    public PikPakAccount create(PikPakAccount dto) {
        validate(dto);
        if (pikPakAccountRepository.count() == 0) {
            dto.setMaster(true);
            updateIndexFile();
            aListLocalService.startAListServer();
        } else {
            if (pikPakAccountRepository.existsByNickname(dto.getNickname())) {
                throw new BadRequestException("账号昵称已经存在");
            }
            if (pikPakAccountRepository.existsByUsername(dto.getUsername())) {
                throw new BadRequestException("用户名已经存在");
            }
        }

        updateMaster(dto);
        dto = pikPakAccountRepository.save(dto);
        updatePikPak(dto);

        return dto;
    }

    public PikPakAccount update(Integer id, PikPakAccount dto) {
        validate(dto);
        PikPakAccount account = pikPakAccountRepository.findById(id).orElseThrow(NotFoundException::new);
        PikPakAccount other = pikPakAccountRepository.findByNickname(dto.getNickname());
        if (other != null && !id.equals(other.getId())) {
            throw new BadRequestException("账号昵称已经存在");
        }
        other = pikPakAccountRepository.findByUsername(dto.getUsername());
        if (other != null && !id.equals(other.getId())) {
            throw new BadRequestException("用户名已经存在");
        }

        boolean changed = account.isMaster() != dto.isMaster()
                || !account.getUsername().equals(dto.getUsername())
                || !account.getPassword().equals(dto.getPassword());
        dto.setId(id);

        if (changed && dto.isMaster()) {
            updateMaster(dto);
        }

        pikPakAccountRepository.save(dto);
        updatePikPak(dto);

        return dto;
    }

    public void updatePikPak(PikPakAccount account) {
        int status = aListLocalService.getAListStatus();
        try {
            int id = 8000 + account.getId();
            int disabled = status == 0 ? 0 : 1;
            String token = status == 2 ? accountService.login() : "";
            if (status == 2) {
                accountService.deleteStorage(id, token);
            }
            String sql = "INSERT INTO x_storages (id,mount_path,driver,cache_expiration,status,addition,modified,disabled,webdav_policy) VALUES(%d,\"/\uD83C\uDD7F️我的PikPak/%s\",'PikPak',30,'work','{\"root_folder_id\":\"\",\"username\":\"%s\",\"password\":\"%s\"}','2023-06-20 12:00:00+00:00',%d,'302_redirect');";
            Utils.executeUpdate(String.format(sql, id, account.getNickname(), account.getUsername(), account.getPassword(), disabled));
            log.info("add AList PikPak {} {}: {}", id, account.getNickname(), account.getUsername());
            if (status == 2) {
                accountService.enableStorage(id, token);
            }
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    private void updateMaster(PikPakAccount account) {
        if (account.isMaster()) {
            log.info("reset account master");
            List<PikPakAccount> list = pikPakAccountRepository.findAll();
            for (PikPakAccount a : list) {
                a.setMaster(false);
            }
            account.setMaster(true);
            pikPakAccountRepository.saveAll(list);
            updateAList(account);
        }
    }

    private void updateAList(PikPakAccount account) {
        try {
            log.info("update AList PikPak credentials by account: {}", account.getId());

            String sql = "update x_storages set addition = json_replace(addition, '$.username', '" + account.getUsername() + "') where driver = 'PikPakShare';";
            Utils.executeUpdate(String.format(sql));
            sql = "update x_storages set addition = json_replace(addition, '$.password', '" + account.getPassword() + "') where driver = 'PikPakShare';";
            Utils.executeUpdate(String.format(sql));
        } catch (Exception e) {
            throw new BadRequestException(e);
        }
    }

    public void delete(Integer id) {
        PikPakAccount account = pikPakAccountRepository.findById(id).orElse(null);
        if (account != null) {
            if (account.isMaster()) {
                throw new BadRequestException("不能删除主账号");
            }
            pikPakAccountRepository.deleteById(id);
            String token = accountService.login();
            accountService.deleteStorage(8000 + account.getId(), token);
        }
    }
}
