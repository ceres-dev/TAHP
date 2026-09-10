package dev.cerez.tahp.connector.connectors.exception.binance;

import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.connector.connectors.exception.BinanceApiException;

import java.net.http.HttpRequest;

public class SystemNotEnoughAssetException extends BinanceApiException {
    public SystemNotEnoughAssetException(int code, String message, HttpRequest request) {
        super(code, message, request);
    }
}
