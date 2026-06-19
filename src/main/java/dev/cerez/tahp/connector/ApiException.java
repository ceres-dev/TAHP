package dev.cerez.tahp.connector;

public class ApiException extends RuntimeException {
    public ApiException(String message) {
        super(message);
    }
}
