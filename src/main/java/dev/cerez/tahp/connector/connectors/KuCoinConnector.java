package dev.cerez.tahp.connector.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cerez.tahp.connector.BaseConnector;
import dev.cerez.tahp.connector.model.BookTicker;
import dev.cerez.tahp.connector.model.OrderResult;
import dev.cerez.tahp.connector.model.Symbol;
import dev.cerez.tahp.connector.model.Volume24H;
import dev.cerez.tahp.triangular.engine.model.Action;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Consumer;

public final class KuCoinConnector extends BaseConnector implements AutoCloseable {

    private static final String BASE_HTTPS = "https://api.kucoin.com";
    private static final String BASE_WWS = "wss://ws-api-spot.kucoin.com";

    @Override
    protected @NotNull String getHTTPS() {
        return BASE_HTTPS;
    }

    @Override
    protected @NotNull String getWWS() {
        return BASE_WWS;
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
        }

        // Longitud del Pong
        if (11 == split.length && telemetry != null) {
            waitingForPong = false;
            telemetry.setCurrentDeltaDelayPingPongNanoTime(System.nanoTime() - delayPingPongNanoTime);
        }
    }

    @Override
    protected String getPingPayload() {
        return """
                {"id": "%d","type": "ping"}
                """.formatted(random.nextInt());
    }

    public void checkApikey() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Map<String, Symbol> getAllSymbols() {
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

            Double stepSize = Double.parseDouble(baseIncrement);
            symbols.put(symbol, new Symbol(
                    symbol,
                    pricePrecision,
                    quantityPrecision,
                    baseAsset,
                    quoteAsset,
                    enableTrading,
                    stepSize,
                    5d
            ));
        }
        cachedSymbols.clear();
        cachedSymbols.putAll(symbols);
        return symbols;
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
    public @NotNull Map<String, BookTicker> getAllBooks() {
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
    }

    @Override
    public @NotNull Map<String, Volume24H> getVolume24H() {
        JsonNode response = sendPublicRequest(Method.GET, "/api/v1/market/allTickers", new TreeMap<>());
        Map<String, Volume24H> result = new HashMap();
        for (JsonNode node : response.get("data").get("ticker")) {
            String symbol = node.get("symbol").asText();
            if (node.get("changePrice").asText().equals("null")) continue;
            result.put(symbol, new Volume24H(
                    symbol,
                    Double.parseDouble(node.get("volValue").asText()),
                    Double.parseDouble(node.get("vol").asText()))
            );
        }
        return result;
    }

    @Override
    public @NotNull Map<String, Double> getBalance() {
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
    public void unsubscribeBookTicker(@NotNull Consumer<BookTicker> listener) {

    }

    private final Random random = new Random();

    @Override
    protected void subscribeBookTickerBatch(@NotNull List<String> symbols) {
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