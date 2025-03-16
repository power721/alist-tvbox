package cn.har01d.alist_tvbox.web;

import cn.har01d.alist_tvbox.entity.DriverAccount;
import cn.har01d.alist_tvbox.service.PanAccountService;
import cn.har01d.alist_tvbox.service.QuarkUCTV;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pan/accounts")
public class PanAccountController {
    private final PanAccountService panAccountService;

    public PanAccountController(PanAccountService panAccountService) {
        this.panAccountService = panAccountService;
    }

    @GetMapping
    public List<DriverAccount> list() {
        return panAccountService.list();
    }

    @PostMapping
    public DriverAccount create(@RequestBody DriverAccount account) {
        return panAccountService.create(account);
    }

    @PostMapping("/{id}")
    public DriverAccount update(@PathVariable Integer id, @RequestBody DriverAccount account) {
        return panAccountService.update(id, account);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Integer id) {
        panAccountService.delete(id);
    }

    @PostMapping("/-/qr")
    public QuarkUCTV.LoginResponse getQrCode(String type) {
        return panAccountService.getQrCode(type);
    }

    @PostMapping("/-/token")
    public String getRefreshToken(String type, String queryToken) {
        return panAccountService.getRefreshToken(type, queryToken);
    }
}
