package cn.har01d.alist_tvbox.service;

import cn.har01d.alist_tvbox.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** currentUid fail-closed(§3.4):非管理级身份解析失败返回 -1 并拒绝,不再回落 uid=0 冒充管理归属。 */
class AccountAccessGuardTest {
    private final AccountAccessGuard guard = new AccountAccessGuard();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String principalName, Object details, String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .toList();
        User principal = new User(principalName, "password", authorities);
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(principal, "creds", authorities);
        if (details != null) {
            token.setDetails(details);
        }
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    void sessionTokenDetailsWins() {
        authenticate("whatever", 7, Role.USER.name());
        assertEquals(7, guard.currentUid());
    }

    @Test
    void basicAuthPrincipalParsedAsUid() {
        authenticate("7", null, Role.USER.name());
        assertEquals(7, guard.currentUid());
    }

    @Test
    void unresolvableUserFailsClosedInsteadOfAdmin() {
        authenticate("not-a-number", null, Role.USER.name());
        assertEquals(AccountAccessGuard.UNRESOLVED_UID, guard.currentUid());
        assertFalse(guard.canManage(0), "解析失败不得冒充 uid=0 管理归属");
        assertFalse(guard.canView(0, true), "解析失败不得看到共享账号");
        assertFalse(guard.canUseCredentials(0));
        assertEquals(AccountAccessGuard.UNRESOLVED_UID, guard.effectiveUid());
    }

    @Test
    void noAuthenticationFailsClosed() {
        assertEquals(AccountAccessGuard.UNRESOLVED_UID, guard.currentUid());
    }

    @Test
    void unresolvableElevatedIdentityStaysGlobal() {
        // CLIENT(X-API-KEY 服务端互访):principal 不可解析,但管理级身份保持 0(全局管理者)
        authenticate("api-key-value", null, Role.CLIENT.name());
        assertEquals(0, guard.currentUid());
        assertEquals(0, guard.effectiveUid());
        assertTrue(guard.canManage(0));
    }
}
