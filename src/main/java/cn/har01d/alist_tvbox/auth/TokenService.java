package cn.har01d.alist_tvbox.auth;

public interface TokenService {
    UserToken extractToken(String rawToken);

    String encodeToken(int userId, String username, String authority);
}
