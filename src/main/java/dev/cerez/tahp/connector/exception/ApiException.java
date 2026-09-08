package dev.cerez.tahp.connector.exception;

import java.net.http.HttpRequest;

public class ApiException extends RuntimeException {

    public ApiException(String message, HttpRequest request) {
        super(message);
    }

    public ApiException(Exception cause) {
        super(cause);
    }
}
