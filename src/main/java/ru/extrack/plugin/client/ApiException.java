package ru.extrack.plugin.client;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/**
 * An error answer or a network failure. {@link #getMessage()} is Russian and safe to show to admins.
 */
public final class ApiException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int    status;
    private final String code;
    private final int    retryAfter;

    public ApiException(int status, String code, String message, int retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    private ApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.code = "network";
        this.retryAfter = 0;
    }

    static ApiException network(IOException exception) {
        String message;
        if (exception instanceof UnknownHostException) message = "адрес " + exception.getMessage() + " не найден";
        else if (exception instanceof SocketTimeoutException) message = "сервер не ответил вовремя";
        else if (exception instanceof ConnectException) message = "соединение отклонено";
        else if (exception instanceof SSLException) message = "ошибка SSL: " + exception.getMessage();
        else message = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return new ApiException(message, exception);
    }

    /** HTTP status, 0 for network errors. */
    public int status() {
        return this.status;
    }

    public String code() {
        return this.code;
    }

    /** Seconds from the Retry-After header, 0 if there was none. */
    public int retryAfter() {
        return this.retryAfter;
    }

    public boolean isAuth() {
        return this.status == 401 || this.status == 403;
    }

    public boolean isLocked() {
        return this.status == 423;
    }

    public boolean isRateLimited() {
        return this.status == 429;
    }

    /** The server will never accept this request, retrying is pointless. */
    public boolean isRejected() {
        return this.status == 400 || this.status == 413 || this.status == 422;
    }
}
