package dev.cerez.tahp.connector.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.net.http.HttpRequest;

@EqualsAndHashCode(callSuper = true)
@Data
public class ApiException extends RuntimeException {

    private final HttpRequest request;

    public ApiException(String message, HttpRequest request) {
        super(message);
        this.request = request;
    }
}
