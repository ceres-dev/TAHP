package dev.cerez.tahp.connector.connectors;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.cerez.tahp.connector.ApiException;
import dev.cerez.tahp.connector.BaseConnector;
import dev.cerez.tahp.connector.model.*;
import dev.cerez.tahp.model.Action;
import dev.cerez.tahp.model.MarketStatus;
import lombok.Setter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class GeminiConnector extends BaseConnector implements AutoCloseable {

    private static final String BASE_HTTPS = "https://api.gemini.com";
    private static final String BASE_WWS = "wss://ws.gemini.com";

    @Setter
    private Consumer<BookTicker> consumerBookTicker;

    @Override
    protected @NotNull String getApiRestURL(@NotNull String baseURL) {
        return BASE_HTTPS;
    }

    @Override
    protected void handleStreamMessage(@NotNull String contentToParse) {
        String[] split = contentToParse.split("\"");
        if (split.length == 33) {
            BookTicker bookTicker = new BookTicker(
                    split[7].toUpperCase(Locale.ROOT),
                    fastParseDouble(split[11]),
                    fastParseDouble(split[15]),
                    fastParseDouble(split[19]),
                    fastParseDouble(split[23])
            );

            consumerBookTicker.accept(bookTicker);
        }

        // Longitud del Pong
        if (7 == split.length && telemetry != null) {
            waitingForPong = false;
            telemetry.setCurrentDeltaDelayPingPongNanoTime(System.nanoTime() - delayPingPongNanoTime);
        }
    }

    @Override
    @Contract(" -> new")
    protected @NotNull BaseConnector.URL getURL() {
        return new URL(BASE_HTTPS, BASE_WWS);
    }

    @Override
    protected String getPingPayload() {
        return """
                {"id":"%d","method":"ping","params":{}}
                """.formatted(id.incrementAndGet());
    }

    public void checkApikey() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Map<String, Symbol> getAllSymbols() {
        try {
            List<String> symbolsNames = getAllSymbolsNames();
            HashMap<String, Symbol> symbols = new HashMap<>();

            int i = 0;
            for (String symbolName : symbolsNames) {
                JsonNode responseSymbol = sendPublicRequest(Method.GET, "/v1/symbols/details/%s".formatted(symbolName), new TreeMap<>());

                symbols.put(symbolName, new Symbol(
                        responseSymbol.get("symbol").asText(),
                        precision(Double.parseDouble(responseSymbol.get("quote_increment").asText())),
                        precision(Double.parseDouble(responseSymbol.get("tick_size").asText())),
                        responseSymbol.get("status").asText().equals("open") ? MarketStatus.TRADING : MarketStatus.CLOSE,
                        responseSymbol.get("base_currency").asText(),
                        responseSymbol.get("quote_currency").asText(),
                        responseSymbol.get("product_type").asText().equals("spot"),
                        Double.parseDouble(responseSymbol.get("tick_size").asText()),
                        Set.of())
                );
                if ((i % 100) == 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
                i++;
            }
            cachedSymbols.clear();
            cachedSymbols.putAll(symbols);
            return symbols;
        } catch (IOException e) {
            e.printStackTrace();
            throw new ApiException(e);
        }
    }

    private static int precision(double value) {
        BigDecimal bd = BigDecimal.valueOf(value).stripTrailingZeros();
        return Math.max(0, bd.scale());
    }

    @Override
    public @NotNull Map<String, BookTicker> getAllBooks() {
        try {
            List<String> symbolsNames = getAllSymbolsNames();
            Map<String, BookTicker> result = new HashMap<>();
            for (String symbolName : symbolsNames) {
                try {
                    Map<String, Object> parameters = new TreeMap<>();
                    parameters.put("limit_bids", 1);
                    parameters.put("limit_asks", 1);

                    JsonNode node = sendPublicRequest(Method.GET, "/v1/book/%s".formatted(symbolName), parameters);

                    try {
                        JsonNode bid = node.get("bids").iterator().next();
                        JsonNode ask = node.get("asks").iterator().next();

                        result.put(symbolName, new BookTicker(
                                symbolName,
                                fastParseDouble(bid.get("price").asText()),
                                fastParseDouble(bid.get("amount").asText()),
                                fastParseDouble(ask.get("price").asText()),
                                fastParseDouble(ask.get("amount").asText()))
                        );
                    }catch (NoSuchElementException e){
                        result.put(symbolName, new BookTicker(
                                symbolName,
                                0d,
                                0d,
                                0d,
                                0d
                        ));
                    }
                }catch (JsonParseException ignored){}
            }
            return result;
        } catch (IOException e) {
            e.printStackTrace();
            throw new ApiException(e);
        }
    }

    @Override
    public @NotNull Map<String, Volume24H> getVolume24H() {
        try {
            List<String> symbolsNames = getAllSymbolsNames();
            Map<String, Volume24H> result = new HashMap<>();
            for (String symbolName : symbolsNames) {
                try {
                    JsonNode node = sendPublicRequest(Method.GET, "/v1/pubticker/%s".formatted(symbolName), new TreeMap<>());
                    JsonNode volume = node.get("volume");
                    List<String> fieldNames = new LinkedList<>();
                    Iterator<String> fieldNamesIterator = volume.fieldNames();
                    while (fieldNamesIterator.hasNext()) fieldNames.add(fieldNamesIterator.next());
                    result.put(symbolName, new Volume24H(
                            symbolName,
                            fastParseDouble(volume.get(fieldNames.get(0)).asText()),
                            fastParseDouble(volume.get(fieldNames.get(1)).asText())
                    ));
                    // En caso que el Json: 'xxxyyyy' does not have available data yet
                }catch (JsonParseException ignored){}
            }
            return result;
        } catch (IOException e) {
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

    private final AtomicInteger id = new AtomicInteger();

    @Override
    protected void subscribeBookTickerBatch(@NotNull List<String> symbols) {
        String params = symbols.stream()
                .map(s -> "\"" + s + "@bookTicker\"")
                .collect(Collectors.joining(","));

        String json = """
        {"id":"%d", "method":"SUBSCRIBE","params":[%s]}
        """.formatted(id.incrementAndGet(), params);
        if (savePendingRequest(json)) return;
        webSocket.sendText(json, true).join();
    }

    @Override
    public void unsubscribeBookTicker(@NotNull Consumer<BookTicker> listener) {

    }

    @Override
    public void close() {

    }

    private List<String> allSymbolsCache = null;

    private List<String> getAllSymbolsNames(){
        try {
            @NotNull JsonNode response = sendPublicRequest(Method.GET, "/v1/symbols", new TreeMap<>());

            List<String> symbolsNames = new ArrayList<>();
            response.forEach(s -> symbolsNames.add(s.asText().toUpperCase(Locale.ROOT)));
            return List.copyOf(Objects.requireNonNullElseGet(allSymbolsCache, () -> allSymbolsCache = symbolsNames));
        }catch (IOException e) {
            e.printStackTrace();
            return List.of();
        }
    }
}