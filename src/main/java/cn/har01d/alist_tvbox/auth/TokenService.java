package cn.har01d.alist_tvbox.auth;

public interface TokenService {
    UserToken extractToken(String rawToken);

    String encodeToken(String username, String authority, boolean rememberMe);
}
