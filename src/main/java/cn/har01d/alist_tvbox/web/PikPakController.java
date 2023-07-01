package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.PikPakAccount;
import cn.har01d.alist_tvbox.entity.PikPakAccountRepository;
import cn.har01d.alist_tvbox.service.PikPakService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Profile("xiaoya")
@RestController
@RequestMapping("/pikpak")
public class PikPakController {
    private final PikPakAccountRepository accountRepository;
    private final PikPakService pikPakService;

    public PikPakController(PikPakAccountRepository accountRepository, PikPakService pikPakService) {
        this.accountRepository = accountRepository;
        this.pikPakService = pikPakService;
    }

    @GetMapping("/accounts")
    public List<PikPakAccount> list() {
        return accountRepository.findAll();
    }

    @PostMapping("/accounts")
    public PikPakAccount create(@RequestBody PikPakAccount account) {
        return pikPakService.create(account);
    }

    @PostMapping("/accounts/{id}")
    public PikPakAccount update(@PathVariable Integer id, @RequestBody PikPakAccount account) {
        return pikPakService.update(id, account);
    }

    @DeleteMapping("/accounts/{id}")
    public void delete(@PathVariable Integer id) {
        pikPakService.delete(id);
    }
}
