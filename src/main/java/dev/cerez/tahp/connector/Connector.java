package dev.cerez.tahp.connector;

import dev.cerez.tahp.connector.model.*;
import dev.cerez.tahp.model.Action;
import dev.cerez.tahp.model.Switch;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public interface Connector extends Switch {

    int MAX_SYMBOLS_PER_SUBSCRIBE = 100; // límite de KuCoin por topic de orderbookLevel1
    long SUBSCRIBE_BATCH_DELAY_MS = 300;

    @NotNull ExchangeInfo getExchangeInfo();

    @NotNull Map<String, BookTicker> getBookTickers();

    @NotNull Map<String, Volume24H> getVolume24H();

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
