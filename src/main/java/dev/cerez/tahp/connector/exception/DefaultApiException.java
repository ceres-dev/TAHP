package dev.cerez.tahp.connector.exception;

import java.net.http.HttpRequest;

public class DefaultApiException extends ApiException {
    public DefaultApiException(String message, HttpRequest request) {
        super(message, request);
    }
}
