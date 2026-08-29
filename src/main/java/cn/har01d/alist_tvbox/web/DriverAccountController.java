package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.dto.AccountInfo;
import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.service.AccountAccessGuard;
import cn.har01d.alist_tvbox.service.DriverAccountService;
import cn.har01d.alist_tvbox.service.QuarkUCTV;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/pan/accounts")
public class DriverAccountController {
    private final DriverAccountService driverAccountService;
    private final AccountAccessGuard guard;

    public DriverAccountController(DriverAccountService driverAccountService, AccountAccessGuard guard) {
        this.driverAccountService = driverAccountService;
        this.guard = guard;
    }

    @GetMapping
    public List<DriverAccount> list() {
        // 普通用户:本人账号(含凭证)∪ shared 全局账号(脱敏,凭证不下发)
        return driverAccountService.list().stream()
                .filter(account -> guard.canView(account.getOwnerUid(), account.isShared()))
                .map(guard::sanitize)
                .toList();
    }

    @PostMapping
    public DriverAccount create(@RequestBody DriverAccount account) {
        if (!guard.isElevated()) {
            account.setOwnerUid(guard.currentUid());
            // master 是全局敏感标记(spider 注入/取链选主账号),非管理身份不得自封
            account.setMaster(false);
        }
        return driverAccountService.create(account);
    }

    @PostMapping("/{id}")
    public DriverAccount update(@PathVariable Integer id, @RequestBody DriverAccount account) {
        DriverAccount existing = driverAccountService.get(id);
        guard.checkManage(existing.getOwnerUid());
        // master 是全局敏感标记(spider 注入/取链选主账号),非管理身份不得变更
        if (!guard.isElevated()) {
            account.setMaster(existing.isMaster());
        }
        return driverAccountService.update(id, account);
    }

    @PostMapping("/{id}/token")
    public void updateToken(@PathVariable Integer id, @RequestBody DriverAccount account) {
        guard.checkManage(driverAccountService.get(id - DriverAccountService.IDX).getOwnerUid());
        driverAccountService.updateToken(id, account);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        guard.checkManage(driverAccountService.get(id).getOwnerUid());
        driverAccountService.delete(id);
    }

    @PostMapping("/-/qr")
    public QuarkUCTV.LoginResponse getQrCode(String type) throws IOException {
        return driverAccountService.getQrCode(type);
    }

    @PostMapping("/-/token")
    public AccountInfo getRefreshToken(String type, String queryToken) {
        return driverAccountService.getRefreshToken(type, queryToken);
    }

    @PostMapping("/-/info")
    public AccountInfo getInfo(@RequestBody DriverAccount account) {
        return driverAccountService.getInfo(account);
    }
}
