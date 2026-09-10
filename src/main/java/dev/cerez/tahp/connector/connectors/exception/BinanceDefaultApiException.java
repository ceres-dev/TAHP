package dev.cerez.tahp.connector.connectors.exception;

import dev.cerez.tahp.connector.exception.DefaultApiException;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.net.http.HttpRequest;

@EqualsAndHashCode(callSuper = true)
@Data
public class BinanceDefaultApiException extends DefaultApiException {

    private final int code;

    public BinanceDefaultApiException(int code, String message, HttpRequest request) {
        super(message, request);
        this.code = code;
    }
}
