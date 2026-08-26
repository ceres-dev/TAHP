package dev.cerez.tahp.connector.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cerez.tahp.connector.BaseConnector;
import dev.cerez.tahp.connector.model.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;

public final class KuCoinConnector extends BaseConnector implements AutoCloseable {

    private static final String BASE_HTTPS = "https://api.kucoin.com";
    private static final String BASE_WWS = "wss://ws-api-spot.kucoin.com";

    @Override
    @NotNull
    public String getHTTPS() {
        return BASE_HTTPS;
    }

    @Override
    @NotNull
    public String getWWS() {
        return BASE_WWS;
    }

    @Override
    protected void handleStream(@NotNull String wwsURL, @NotNull String contentToParse) {

        String[] split = contentToParse.split("\"");

        // Longitud del ticker book
        if (29 == split.length) {
            BookTickDouble bookTickDouble = new BookTickDouble(
                    split[3].substring(19),
                    fastParseDouble(split[23]),
                    fastParseDouble(split[25]),
                    fastParseDouble(split[17]),
                    fastParseDouble(split[19])
            );

            consumerBookTicker.accept(bookTickDouble);
        }

        // Longitud del Pong
        if (11 == split.length && telemetry != null) {
            waitingForPong = false;
            telemetry.setCurrentDeltaDelayPingPongNanoTime(System.nanoTime() - delayPingPongNanoTime);
        }
    }

    @Override
    protected String getPingPayload(@NotNull String wwsURL) {
        return """
                {"id": "%d","type": "ping"}
                """.formatted(random.nextInt());
    }

    @Override
    protected @NotNull Set<String> getBlackListEndpointLog() {
        return Set.of();
    }

    public void checkApikey() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Map<String, Symbol> sGetAllSymbols() {
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

            BigDecimal stepSize = new BigDecimal(baseIncrement);
            symbols.put(symbol, new Symbol(
                    symbol,
                    pricePrecision,
                    quantityPrecision,
                    baseAsset,
                    quoteAsset,
                    enableTrading,
                    stepSize,
                    new BigDecimal("5d")
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
    public @NotNull Map<String, BookTickDouble> sGetAllBooks() {
        JsonNode response = sendPublicRequest(Method.GET, "/api/v1/market/allTickers", new TreeMap<>());
        Map<String, BookTickDouble> result = new HashMap<>();
        for (JsonNode node : response.get("data").get("ticker")) {
            String symbol = node.get("symbol").asText();
            if (node.get("buy").asText().equals("null")) continue;
            result.put(symbol, new BookTickDouble(
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
    public @NotNull Map<String, Volume24H> sGetVolume24H() {
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
    public @NotNull Map<String, BigDecimal> sGetBalance() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public @NotNull OrderResult sSendOrderToMkt(@NotNull String symbol,
                                                @NotNull ActionOrden actionOrden,
                                                @NotNull BigDecimal amount,
                                                @Nullable String nameOrder,
                                                boolean amountInBaseAsset
    ) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public @NotNull Long getTimeSever() {
        return System.currentTimeMillis();
    }

    @Override
    public void unsubscribeBookTicker(@NotNull Consumer<BookTickDouble> listener) {

    }

    private final Random random = new Random();

    @Override
    protected void subscribeBookTickerBatch(@NotNull List<String> symbols) {
        String json = """
            {
              "id":"%s",
              "type":"subscribe",
              "topic":"/spotMarket/level1:%s",
              "response":true}
            """.formatted(random.nextInt(), String.join(",", symbols));
        sendWebSocket(json);
    }

    @Override
    public void close() {

    }
}