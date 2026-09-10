package dev.cerez.tahp.connector.connectors.exception.binance;

import dev.cerez.tahp.connector.connectors.exception.BinanceApiException;

import java.net.http.HttpRequest;

public class ReduceOnlyRejectException extends BinanceApiException {
    public ReduceOnlyRejectException(int code, String message, HttpRequest request) {
        super(code, message, request);
    }
}
