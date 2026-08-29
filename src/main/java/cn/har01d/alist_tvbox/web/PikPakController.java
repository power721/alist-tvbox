package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.PikPakAccount;
import cn.har01d.alist_tvbox.entity.PikPakAccountRepository;
import cn.har01d.alist_tvbox.service.AccountAccessGuard;
import cn.har01d.alist_tvbox.service.PikPakService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/pikpak")
public class PikPakController {
    private final PikPakAccountRepository accountRepository;
    private final PikPakService pikPakService;
    private final AccountAccessGuard guard;

    public PikPakController(PikPakAccountRepository accountRepository, PikPakService pikPakService, AccountAccessGuard guard) {
        this.accountRepository = accountRepository;
        this.pikPakService = pikPakService;
        this.guard = guard;
    }

    @GetMapping("/accounts")
    public List<PikPakAccount> list() {
        return accountRepository.findAll().stream()
                .filter(account -> guard.canView(account.getOwnerUid(), account.isShared()))
                .map(guard::sanitize)
                .toList();
    }

    @PostMapping("/accounts")
    public PikPakAccount create(@RequestBody PikPakAccount account) {
        if (!guard.isElevated()) {
            account.setOwnerUid(guard.currentUid());
            // master 是全局敏感标记(共享 AList 凭证选主),非管理身份不得自封
            account.setMaster(false);
        }
        return pikPakService.create(account);
    }

    @PostMapping("/accounts/{id}")
    public PikPakAccount update(@PathVariable Integer id, @RequestBody PikPakAccount account) {
        PikPakAccount existing = accountRepository.findById(id).orElseThrow();
        guard.checkManage(existing.getOwnerUid());
        if (!guard.isElevated()) {
            // update 整体保存 DTO:master/ownerUid 必须保留存量,防止普通用户把自己的账号顶成全局主账号
            account.setMaster(existing.isMaster());
            account.setOwnerUid(existing.getOwnerUid());
        }
        return pikPakService.update(id, account);
    }

    @DeleteMapping("/accounts/{id}")
    public void delete(@PathVariable Integer id) {
        guard.checkManage(accountRepository.findById(id).orElseThrow().getOwnerUid());
        pikPakService.delete(id);
    }
}
