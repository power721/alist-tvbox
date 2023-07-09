package cn.har01d.alist_tvbox.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class UserUnauthorizedException extends RuntimeException {
    private final int code;

    public UserUnauthorizedException() {
        super();
        this.code = 500;
    }

    public UserUnauthorizedException(String message, int code) {
        super(message);
        this.code = code;
    }

    public UserUnauthorizedException(String message, int code, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
