package dev.cerez.tahp.connector.connectors.exception;

import org.jetbrains.annotations.NotNull;

public class PostOnlyRejectException extends BinanceApiException {
    public PostOnlyRejectException(int code, String message) {
        super(code, message);
    }
    public PostOnlyRejectException(@NotNull BinanceApiException e) {
        super(e.getCode(), e.getMessage());
    }
}
