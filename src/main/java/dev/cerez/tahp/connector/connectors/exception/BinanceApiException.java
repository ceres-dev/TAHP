package dev.cerez.tahp.connector.connectors.exception;

import dev.cerez.tahp.connector.ApiException;

public class BinanceApiException extends ApiException {
    public BinanceApiException(String message) {
        super(message);
    }
}
