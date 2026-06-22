package dev.cerez.tahp.connector.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cerez.tahp.connector.ApiException;
import dev.cerez.tahp.connector.BaseConnector;
import dev.cerez.tahp.connector.model.*;
import dev.cerez.tahp.model.Action;
import dev.cerez.tahp.model.MarketStatus;
import lombok.Setter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

public final class KuCoinConnector extends BaseConnector implements AutoCloseable {

    private static final String BASE_HTTPS = "https://api.kucoin.com";
    private static final String BASE_WWS = "wss://ws-api-spot.kucoin.com";

    @Setter
    private Consumer<BookTicker> consumerBookTicker;
    private volatile boolean ready = false;

    @Override
    protected @NotNull String getBaseURL(@NotNull String baseURL) {
        return BASE_HTTPS;
    }

    @Override
    protected void handleStreamMessage(@NotNull String contentToParse) {

        String[] split = contentToParse.split("\"");

        // Longitud del ticker book
        if (29 == split.length) {
            BookTicker bookTicker = new BookTicker(
                    split[3].substring(19),
                    fastParseDouble(split[23]),
                    fastParseDouble(split[25]),
                    fastParseDouble(split[17]),
                    fastParseDouble(split[19])
            );

            consumerBookTicker.accept(bookTicker);
            return;
        }
        switch (split[7]) {
            case "welcome" -> ready = true;
        }

    }

    public record ApiKeysKuCoin(String key, String secret, String passphrase) {}

    public KuCoinConnector(@NotNull KuCoinConnector.ApiKeysKuCoin apiKeys) {

    }

    /** Carga las credenciales desde KUCOIN_API_KEY / KUCOIN_API_SECRET / KUCOIN_API_PASSPHRASE. */
    public KuCoinConnector() {
        this(loadApiKeysFromEnv());
    }

    @Override
    @Contract(" -> new")
    protected @NotNull BaseConnector.URL getURL() {
        @NotNull JsonNode response = sendPublicRequest(Method.POST, "/api/v1/bullet-public", new TreeMap<>());
        String endpoint = BASE_WWS + "?token=" + response.get("data").get("token").asText();
        return new URL(BASE_HTTPS, endpoint);
    }

    @Override
    protected void sendPing() {
        String request = """
                {
                  "id": "%d",
                  "type": "ping"
                }
                """.formatted(random.nextInt());
        if (webSocket != null){
            webSocket.sendText(request, true);
        }else {
            throw new IllegalStateException();
        }
    }

    private static @NotNull KuCoinConnector.ApiKeysKuCoin loadApiKeysFromEnv() {
        String key = System.getenv("KUCOIN_API_KEY");
        String secret = System.getenv("KUCOIN_API_SECRET");
        String passphrase = System.getenv("KUCOIN_API_PASSPHRASE");
        return new ApiKeysKuCoin(key, secret, passphrase);
    }

    public void checkApikey() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull ExchangeInfo getExchangeInfo() {

        try {
            @NotNull JsonNode response = sendPublicRequest(Method.GET, "/api/v2/symbols", new TreeMap<>());
            HashMap<String, Symbol> symbols = new HashMap<>();
            for (JsonNode data : response.get("data")) {
                String symbol = data.get("symbol").asText();
                String baseAsset = data.get("baseCurrency").asText();
                String quoteAsset = data.get("quoteCurrency").asText();
                boolean enableTrading = data.get("enableTrading").asBoolean();
                String baseIncrement = data.get("baseIncrement").asText();
                String priceIncrement = data.get("priceIncrement").asText();

                Integer quantityPrecision = decimalPlaces(baseIncrement);
                Integer pricePrecision = decimalPlaces(priceIncrement);

                MarketStatus marketStatus = enableTrading
                        ? MarketStatus.TRADING
                        : MarketStatus.HALT; // ajusta estos nombres a tu enum

                Double stepSize = Double.parseDouble(baseIncrement);
                symbols.put(symbol, new Symbol(
                        symbol,
                        pricePrecision,
                        quantityPrecision,
                        marketStatus,
                        baseAsset,
                        quoteAsset,
                        enableTrading,
                        stepSize,
                        Set.of()
                ));
            }
            ExchangeInfo info = new ExchangeInfo(List.of(), symbols);
            cachedSymbols.clear();
            cachedSymbols.putAll(symbols);
            return info;
        } catch (RuntimeException e) {
            e.printStackTrace();
            throw new ApiException(e.getMessage());
        }
    }

    private @NotNull Integer decimalPlaces(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }

        int dot = value.indexOf('.');
        if (dot < 0) {
            return 0;
        }

        int decimals = value.length() - dot - 1;

        while (decimals > 0 && value.charAt(value.length() - 1) == '0') {
            value = value.substring(0, value.length() - 1);
            decimals--;
        }

        return Math.max(decimals, 0);
    }

    @Override
    public @NotNull Map<String, BookTicker> getBookTickers() {
        try {
            JsonNode response = sendPublicRequest(Method.GET, "/api/v1/market/allTickers", new TreeMap<>());
            Map<String, BookTicker> result = new HashMap<>();
            for (JsonNode node : response.get("data").get("ticker")) {
                String symbol = node.get("symbol").asText();
                if (node.get("buy").asText().equals("null")) continue;
                result.put(symbol, new BookTicker(
                        symbol,
                        Double.parseDouble(node.get("buy").asText()),
                        Double.parseDouble(node.get("bestBidSize").asText()),
                        Double.parseDouble(node.get("sell").asText()),
                        Double.parseDouble(node.get("bestAskSize").asText())
                ));
            }
            return result;
        } catch (RuntimeException e) {
            e.printStackTrace();
            throw new ApiException(e.getMessage());
        }
    }

    @Override
    public @NotNull Set<Ticker24H> getTicker24H() {
        try {
            JsonNode response = sendPublicRequest(Method.GET, "/api/v1/market/allTickers", new TreeMap<>());
            Set<Ticker24H> result = new HashSet<>();
            for (JsonNode node : response.get("data").get("ticker")) {
                String symbol = node.get("symbol").asText();
                if (node.get("changePrice").asText().equals("null")) continue;
                result.add(new Ticker24H(
                        symbol,
                        Double.parseDouble(node.get("changePrice").asText()),
                        Double.parseDouble(node.get("changeRate").asText()) * 100.0, // KuCoin da una fracción (0.025 = 2.5%)
                        Double.parseDouble(node.get("volValue").asText()),
                        Double.parseDouble(node.get("vol").asText()))
                );
            }
            return result;
        } catch (RuntimeException e) {
            e.printStackTrace();
            throw new ApiException(e.getMessage());
        }
    }

    @Override
    public @NotNull HashMap<String, Double> getBalance() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public @NotNull OrderResult placeMarketOrder(@NotNull Symbol symbol,
                                                 @NotNull Action side,
                                                 @NotNull Double amount,
                                                 @NotNull Boolean useQuantity
    ) {
        throw new UnsupportedOperationException("Not supported yet.");
    }


    @Override
    public void subscribeBookTicker(@NotNull Collection<String> symbols) {
        List<String> streams = new ArrayList<>(symbols);
        for (int i = 0; i < streams.size(); i += MAX_STREAMS_PER_SUBSCRIBE) {
            int end = Math.min(i + MAX_STREAMS_PER_SUBSCRIBE, streams.size());
            subscribeBookTickerBatch(streams.subList(i, end));
            if (webSocket != null) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(COOLDOWN_MS));
        }
    }

    @Override
    public void unsubscribeBookTicker(@NotNull Consumer<BookTicker> listener) {

    }

    private final Random random = new Random();


    private void subscribeBookTickerBatch(@NotNull List<String> symbols) {
        String json = """
            {
              "id":"%s",
              "type":"subscribe",
              "topic":"/spotMarket/level1:%s",
              "response":true
            }
            """.formatted(random.nextInt(), String.join(",", symbols));
        if (savePendingRequest(json)) return;
        webSocket.sendText(json, true).join();
    }

    @Override
    public void close() {

    }
}