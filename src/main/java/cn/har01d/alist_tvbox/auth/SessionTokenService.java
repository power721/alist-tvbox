package cn.har01d.alist_tvbox.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import cn.har01d.alist_tvbox.entity.Session;
import cn.har01d.alist_tvbox.entity.SessionRepository;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionTokenService implements TokenService {
    private final SessionRepository sessionRepository;

    @Override
    public UserToken extractToken(String token) {
        var session = sessionRepository.findByToken(token)
                .orElseThrow(() -> new UserUnauthorizedException("Token失效", 40100));
        if (session.getExpireTime().isBefore(Instant.now())) {
            throw new UserUnauthorizedException("Token过期", 40102);
        }
        return new UserToken(session.getUsername(), Set.of(new SimpleGrantedAuthority("ADMIN")), token);
    }

    @Override
    public String encodeToken(String username, String authority, boolean rememberMe) {
        if (sessionRepository.countByUsername(username) >= 5) {
            var session = sessionRepository.findFirstByUsername(username);
            sessionRepository.delete(session);
        }
        var session = new Session();
        session.setToken(UUID.randomUUID().toString().replace("-", ""));
        session.setUsername(username);
        session.setExpireTime(Instant.now().plus(30, ChronoUnit.DAYS));
        sessionRepository.save(session);
        return session.getToken();
    }
}
