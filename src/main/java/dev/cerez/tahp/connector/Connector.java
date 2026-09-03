package dev.cerez.tahp.connector;

import dev.cerez.tahp.connector.model.*;
import dev.cerez.tahp.triangular.utils.Switch;
import dev.cerez.tahp.triangular.utils.Telemetryable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;

public interface Connector extends Switch, AutoCloseable, Telemetryable {

    @NotNull Map<String, Symbol> sGetAllSymbols();

    @NotNull Map<String, BookTickDouble> sGetAllBooks();

    @NotNull Map<String, Volume24H> sGetVolume24H();

    @NotNull Map<String, BigDecimal> sGetBalance();

    void sSendOrderToMkt(@NotNull String symbol,
                                         @NotNull ActionOrden actionOrden,
                                         @NotNull BigDecimal amount,
                                         @Nullable String nameOrder,
                                         boolean amountInBaseAsset
    );

    @NotNull Long getTimeSever();

    void setConsumerBookTicker(@NotNull Consumer<BookTickDouble> symbol);

    void subscribeBookTicker(@NotNull Collection<String> symbols);

    void unsubscribeBookTicker(@NotNull Consumer<BookTickDouble> listener);

    default void close(){
        stop();
    }


}
