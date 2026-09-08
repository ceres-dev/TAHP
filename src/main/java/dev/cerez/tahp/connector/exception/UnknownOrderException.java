package dev.cerez.tahp.connector.exception;

import dev.cerez.tahp.connector.connectors.exception.BinanceApiException;

import java.net.http.HttpRequest;

public class UnknownOrderException extends BinanceApiException {
    public UnknownOrderException(int code, String message, HttpRequest request) {
        super(code, message, request);
    }
}
