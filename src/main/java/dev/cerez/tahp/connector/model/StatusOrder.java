package dev.cerez.tahp.connector.model;

import org.jetbrains.annotations.NotNull;

public enum StatusOrder {
    NEW,
    FILLED,
    CANCELED,
    OTHER;

    public static StatusOrder parse(@NotNull String value) {
        return switch (value){
            case "NEW" -> StatusOrder.NEW;
            case "FILLED" -> StatusOrder.FILLED;
            case "CANCELED" -> StatusOrder.CANCELED;
            default -> StatusOrder.OTHER;
        };
    }

}
