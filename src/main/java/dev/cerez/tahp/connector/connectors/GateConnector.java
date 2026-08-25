package dev.cerez.tahp.connector.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cerez.tahp.connector.BaseConnector;
import dev.cerez.tahp.connector.model.BookTickDouble;
import dev.cerez.tahp.connector.model.OrderResult;
import dev.cerez.tahp.connector.model.Symbol;
import dev.cerez.tahp.connector.model.Volume24H;
import dev.cerez.tahp.triangular.engine.model.Action;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Consumer;

public final class GateConnector extends BaseConnector implements AutoCloseable {

    private static final String BASE_HTTPS = "https://api.gateio.ws/api/v4";
    private static final String BASE_TESTNET_HTTPS = "https://api-testnet.gateapi.io/api/v4";
    private static final String BASE_WWS =  "wss://api.gateio.ws/ws/v4/";
    private static final String BASE_TESTNET_WWS = "wss://ws-testnet.gate.com/v4/ws/spot";

    public GateConnector(boolean isTestNet) {
        super(isTestNet);
    }

    @Override
    @NotNull
    public String getHTTPS() {
        return isTestNet ? BASE_TESTNET_HTTPS : BASE_HTTPS;
    }

    @Override
    @NotNull
    public String getWWS() {
        return isTestNet ? BASE_TESTNET_WWS : BASE_WWS;
    }

    @Override
    protected void handleStream(@NotNull String wwsURL, @NotNull String contentToParse) {
        String[] split = contentToParse.split("\"");

        // Longitud del ticker book
        if (39 == split.length) {
            BookTickDouble bookTickDouble = new BookTickDouble(
                    split[21],
                    fastParseDouble(split[25]),
                    fastParseDouble(split[29]),
                    fastParseDouble(split[33]),
                    fastParseDouble(split[37])
            );

            consumerBookTicker.accept(bookTickDouble);
        }
        // Longitud del Pong
        if (23 == split.length && telemetry != null) {
            waitingForPong = false;
            telemetry.setCurrentDeltaDelayPingPongNanoTime(System.nanoTime() - delayPingPongNanoTime);
        }
    }

    @Override
    protected String getPingPayload() {
        return """
                {"time":%d,"channel":"spot.ping"}
                """.formatted(System.currentTimeMillis());
    }

    public void checkApikey() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Map<String, Symbol> getAllSymbols() {
        @NotNull JsonNode response = sendPublicRequest(Method.GET, "/spot/currency_pairs", new TreeMap<>());
        HashMap<String, Symbol> symbols = new HashMap<>();
        for (JsonNode node : response) {

            symbols.put(node.get("id").asText(), new Symbol(
                    node.get("id").asText(),
                    node.get("precision").asInt(),
                    node.get("amount_precision").asInt(),
                    node.get("base").asText(),
                    node.get("quote").asText(),
                    "tradable".equals(node.get("trade_status").asText()),
                    new BigDecimal(node.get("min_base_amount").asText()),
                    new BigDecimal("5")
            ));
        }
        synchronized (cachedSymbols) {
            cachedSymbols.clear();
            cachedSymbols.putAll(symbols);
        }
        return symbols;
    }

    @Override
    public @NotNull Map<String, BookTickDouble> getAllBooks() {
        Map<String, BookTickDouble> result = new HashMap<>();
        if (cachedSymbols.isEmpty()) {
            getAllSymbols();
        }
        synchronized (cachedSymbols) {
            for (Symbol symbol : cachedSymbols.values()) {
                TreeMap<String, Object> params = new TreeMap<>();
                params.put("currency_pair", symbol.name());
                params.put("limit", 1);
                params.put("with_id", false);
                JsonNode response = sendPublicRequest(Method.GET, "/spot/order_book", params);

                Iterator<JsonNode> iteratorAsks = response.get("asks").iterator();
                double askPrice = 0, askAmount = 0;
                boolean isPrice = true;
                if (iteratorAsks.hasNext()) {
                    JsonNode nodeAsks = iteratorAsks.next();
                    for (JsonNode node : nodeAsks) {
                        if (isPrice) {
                            askPrice = Double.parseDouble(node.asText());
                            isPrice = false;
                        }else {
                            askAmount = Double.parseDouble(node.asText());
                        }
                    }
                }

                Iterator<JsonNode> iteratorBids = response.get("bids").iterator();
                double bidPrice = 0, bidAmount = 0;
                isPrice = true;
                if (iteratorBids.hasNext()) {
                    JsonNode nodeBids = iteratorBids.next();
                    for (JsonNode node : nodeBids) {
                        if (isPrice) {
                            bidPrice = Double.parseDouble(node.asText());
                            isPrice = false;
                        }else {
                            bidAmount = Double.parseDouble(node.asText());
                        }
                    }
                }

                result.put(symbol.name(), new BookTickDouble(
                        symbol.name(),
                        bidPrice,
                        bidAmount,
                        askPrice,
                        askAmount
                ));
            }
            return result;
        }
    }

    @Override
    public @NotNull Map<String, Volume24H> getVolume24H() {
        JsonNode response = sendPublicRequest(Method.GET, "/spot/tickers", new TreeMap<>());
        Map<String, Volume24H> result = new HashMap<>();
        for (JsonNode node : response) {
            String symbol = node.get("currency_pair").asText();
            result.put(symbol, new Volume24H(
                    symbol,
                    Double.parseDouble(node.get("quote_volume").asText()),
                    Double.parseDouble(node.get("base_volume").asText()))
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
    public void unsubscribeBookTicker(@NotNull Consumer<BookTickDouble> listener) {

    }

    @Override
    protected void subscribeBookTickerBatch(@NotNull List<String> symbols) {
        if (symbols.isEmpty()) {
            return;
        }
        String json = """
            {"time":%d,"channel":"spot.book_ticker","event":"subscribe","payload": [%s]}
            """.formatted(System.currentTimeMillis(), String.join(",", symbols.stream().map(s -> "\"" + s + "\"").toList()));
        sendWebSocket(json);
    }

    @Override
    public void close() {

    }
}
