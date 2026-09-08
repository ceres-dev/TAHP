package dev.cerez.tahp.connector.connectors.exception;

import dev.cerez.tahp.connector.exception.ApiException;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.net.http.HttpRequest;

@EqualsAndHashCode(callSuper = true)
@Data
public class BinanceApiException extends ApiException {

    private final int code;

    public BinanceApiException(int code, String message, HttpRequest request) {
        super(message, request);
        this.code = code;
    }
}
