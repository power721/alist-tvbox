package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.AListLogin;
import cn.har01d.alist_tvbox.dto.AccountDto;
import cn.har01d.alist_tvbox.dto.AccountInfo;
import cn.har01d.alist_tvbox.dto.CheckinLog;
import cn.har01d.alist_tvbox.dto.CheckinResult;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.exception.BadRequestException;
import cn.har01d.alist_tvbox.service.AccountAccessGuard;
import cn.har01d.alist_tvbox.service.AccountService;
import cn.har01d.alist_tvbox.service.AliyunTvTokenService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
public class AccountController {
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final AliyunTvTokenService tvTokenService;
    private final AccountAccessGuard guard;

    public AccountController(AccountRepository accountRepository, AccountService accountService, AliyunTvTokenService tvTokenService,
                             AccountAccessGuard guard) {
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.tvTokenService = tvTokenService;
        this.guard = guard;
    }

    @GetMapping("/api/ali/accounts")
    public List<Account> list() {
        // 普通用户:本人账号(含凭证)∪ shared 全局账号(脱敏,凭证不下发)
        return accountRepository.findAll().stream()
                .filter(account -> guard.canView(account.getOwnerUid(), account.isShared()))
                .map(guard::sanitize)
                .toList();
    }

    @PostMapping("/api/ali/accounts")
    public Account create(@RequestBody AccountDto account) {
        if (!guard.isElevated()) {
            account.setOwnerUid(guard.currentUid());
            // master 是全局敏感标记(tokenm 注入/AList 同步选主账号),非管理身份不得自封
            account.setMaster(false);
        }
        return accountService.create(account);
    }

    @PostMapping("/api/ali/accounts/-/info")
    public AccountInfo getInfo(@RequestBody Account account) {
        if (account.getId() != null) {
            // 带 id 走库存账号(会刷新并回写 token):必须归属人本人/管理级,否则可窥探他人账号身份与配额
            guard.checkManage(accountRepository.findById(account.getId()).orElseThrow().getOwnerUid());
        }
        return accountService.getInfo(account);
    }

    @PostMapping("/api/ali/accounts/{id}/checkin")
    public CheckinResult checkin(@PathVariable Integer id, @RequestParam(required = false) boolean force) {
        guard.checkManage(accountRepository.findById(id).orElseThrow().getOwnerUid());
        return accountService.checkin(id, force);
    }

    @GetMapping("/api/ali/accounts/{id}/checkin")
    public List<CheckinLog> getCheckinLogs(@PathVariable Integer id) {
        Account account = accountRepository.findById(id).orElseThrow();
        if (!guard.canView(account.getOwnerUid(), account.isShared())) {
            throw new BadRequestException("无权查看该账号");
        }
        return accountService.getCheckinLogs(id);
    }

    @PostMapping("/api/ali/accounts/{id}/token")
    public void updateTokens(@PathVariable Integer id, @RequestBody AccountDto account) {
        guard.checkManage(accountRepository.findById(id).orElseThrow().getOwnerUid());
        accountService.updateTokens(id, account);
    }

    @PostMapping("/api/ali/accounts/{id}")
    public Account update(@PathVariable Integer id, @RequestBody AccountDto account) {
        Account existing = accountRepository.findById(id).orElseThrow();
        guard.checkManage(existing.getOwnerUid());
        if (!guard.isElevated()) {
            account.setMaster(existing.isMaster());
        }
        return accountService.update(id, account);
    }

    @DeleteMapping("/api/ali/accounts/{id}")
    public void delete(@PathVariable Integer id) {
        guard.checkManage(accountRepository.findById(id).orElseThrow().getOwnerUid());
        accountService.delete(id);
    }

    @GetMapping("/ali/token/{id}")
    public String getAliToken(@PathVariable String id) {
        return accountService.getAliRefreshToken(id);
    }

    @GetMapping("/ali/open/{id}")
    public String getAliOpenRefreshToken(@PathVariable String id) {
        return accountService.getAliOpenRefreshToken(id);
    }

    @PostMapping("/api/alist/login")
    public AListLogin updateAListLogin(@RequestBody AListLogin login) {
        return accountService.updateAListLogin(login);
    }

    @GetMapping("/api/alist/login")
    public AListLogin getAListLoginInfo() {
        return accountService.getAListLoginInfo();
    }

    @PostMapping("/api/alist/password")
    public String resetPassword() {
        return accountService.resetPassword();
    }

    @PostMapping("/api/schedule")
    public Instant updateScheduleTime(@RequestBody Instant time) {
        return accountService.updateScheduleTime(time);
    }

    @PostMapping("/ali/auth/qr")
    public Map<String, String> getQrcodeUrl() {
        return tvTokenService.getQrcodeUrl();
    }

    @GetMapping("/ali/auth/qr")
    public Map checkQrcodeStatus(String sid) {
        return tvTokenService.checkQrcodeStatus(sid);
    }

    @PostMapping("/ali/auth/token")
    public Map getToken(String code) {
        return tvTokenService.getToken(code);
    }

    @PostMapping("/ali/access_token")
    public Map refreshToken(@RequestBody Map<String, Object> data) {
        return tvTokenService.refreshToken(data);
    }
}
