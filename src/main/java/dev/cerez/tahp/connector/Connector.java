package dev.cerez.tahp.connector;

import dev.cerez.tahp.connector.model.*;
import dev.cerez.tahp.model.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Consumer;

public interface Connector extends Switch {

    @NotNull ExchangeInfo getExchangeInfo();

    @NotNull Map<String, BookTicker> getBookTickers();

    @NotNull Set<Ticker24H> getTicker24H();

    @NotNull HashMap<String, Double> getBalance();

    @NotNull OrderResult placeMarketOrder(@NotNull Symbol symbol,
                                          @NotNull Action side,
                                          @NotNull Double amount,
                                          @NotNull Boolean useQuantity
    );

    void setConsumerBookTicker(@NotNull Consumer<BookTicker> symbol);

    void subscribeBookTicker(@NotNull Collection<String> symbols);

    void unsubscribeBookTicker(@NotNull Consumer<BookTicker> listener);
}
