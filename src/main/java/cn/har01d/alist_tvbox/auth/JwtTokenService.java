package cn.har01d.alist_tvbox.auth;

import cn.har01d.alist_tvbox.config.AppProperties;
import cn.har01d.alist_tvbox.exception.UserUnauthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class JwtTokenService implements TokenService {
    public static final String ISSUER = "Har01d";
    public static final String SUBJECT = "AList";
    public static final String AUDIENCE = "TvBox";
    private final AppProperties appProperties;

    public JwtTokenService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @PostConstruct
    public void init() throws IOException {
        Path path = Path.of("/data/.jwt");
        if (Files.exists(path)) {
            String secret = Files.readString(path);
            appProperties.setSecretKey(secret);
        } else {
            String secret = UUID.randomUUID().toString();
            Files.writeString(path, secret);
            appProperties.setSecretKey(secret);
        }
    }

    @Override
    public UserToken extractToken(String rawToken) {
        try {
            Jws<Claims> jws = Jwts.parserBuilder()
                    .setSigningKey(appProperties.getSecretKey().getBytes())
                    .requireIssuer(ISSUER)
                    .requireSubject(SUBJECT)
                    .requireAudience(AUDIENCE)
                    .build()
                    .parseClaimsJws(rawToken);
            String username = jws.getBody().get("username", String.class);
            String authority = jws.getBody().get("authority", String.class);
            return new UserToken(username, Set.of(new SimpleGrantedAuthority(authority)), rawToken);
        } catch (MalformedJwtException e) {
            log.warn("JWT Token格式错误", e);
            throw new UserUnauthorizedException("JWT Token格式错误", 40101, e);
        } catch (ExpiredJwtException e) {
            log.warn("JWT Token过期", e);
            throw new UserUnauthorizedException("JWT Token过期", 40102, e);
        } catch (UnsupportedJwtException e) {
            log.warn("不支持的JWT Token", e);
            throw new UserUnauthorizedException("不支持的JWT Token", 40103, e);
        } catch (SignatureException e) {
            log.warn("JWT Token签名验证失败", e);
            throw new UserUnauthorizedException("JWT Token签名验证失败", 40104, e);
        } catch (Exception e) {
            log.warn("解析Token失败", e);
            throw new UserUnauthorizedException("解析Token失败", 40100, e);
        }
    }

    @Override
    public String encodeToken(String username, String authority, boolean rememberMe) {
        Instant now = Instant.now();
        Instant expire;
        if (rememberMe) {
            expire = now.plus(30, ChronoUnit.DAYS);
        } else {
            expire = now.plus(30, ChronoUnit.MINUTES);
        }
        return Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setIssuer(ISSUER)
                .setSubject(SUBJECT)
                .setAudience(AUDIENCE)
                .claim("username", username)
                .claim("authority", authority)
                .claim("rememberMe", rememberMe)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(expire))
                .signWith(SignatureAlgorithm.HS256, appProperties.getSecretKey().getBytes())
                .compact();
    }
}
