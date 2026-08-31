package dev.cerez.tahp.connector.exception;

public class ApiException extends RuntimeException {
    public ApiException(String message, Exception cause) {
        super(message, cause);
    }

    public ApiException(String message) {
        super(message);
    }

    public ApiException(Exception cause) {
        super(cause);
    }
}
