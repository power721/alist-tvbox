package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.AListLogin;
import cn.har01d.alist_tvbox.dto.AccountDto;
import cn.har01d.alist_tvbox.dto.CheckinLog;
import cn.har01d.alist_tvbox.dto.CheckinResult;
import cn.har01d.alist_tvbox.entity.Account;
import cn.har01d.alist_tvbox.entity.AccountRepository;
import cn.har01d.alist_tvbox.service.AccountService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
public class AccountController {
    private final AccountRepository accountRepository;
    private final AccountService accountService;

    public AccountController(AccountRepository accountRepository, AccountService accountService) {
        this.accountRepository = accountRepository;
        this.accountService = accountService;
    }

    @GetMapping("/api/ali/accounts")
    public List<Account> list() {
        return accountRepository.findAll();
    }

    @PostMapping("/api/ali/accounts")
    public Account create(@RequestBody AccountDto account) {
        return accountService.create(account);
    }

    @PostMapping("/api/ali/accounts/{id}/checkin")
    public CheckinResult checkin(@PathVariable Integer id, @RequestParam(required = false) boolean force) {
        return accountService.checkin(id, force);
    }

    @GetMapping("/api/ali/accounts/{id}/checkin")
    public List<CheckinLog> getCheckinLogs(@PathVariable Integer id) {
        return accountService.getCheckinLogs(id);
    }

    @PostMapping("/api/ali/accounts/{id}")
    public Account update(@PathVariable Integer id, @RequestBody AccountDto account) {
        return accountService.update(id, account);
    }

    @DeleteMapping("/api/ali/accounts/{id}")
    public void delete(@PathVariable Integer id) {
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

    @PostMapping("/api/schedule")
    public Instant updateScheduleTime(@RequestBody Instant time) {
        return accountService.updateScheduleTime(time);
    }
}
