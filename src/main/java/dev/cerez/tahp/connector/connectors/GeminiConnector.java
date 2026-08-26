package dev.cerez.tahp.connector.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cerez.tahp.connector.BaseConnector;
import dev.cerez.tahp.connector.model.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
        if (split.length == 33) {
            BookTickDouble bookTickDouble = new BookTickDouble(
                    split[7].toUpperCase(Locale.ROOT),
                    fastParseDouble(split[11]),
                    fastParseDouble(split[15]),
                    fastParseDouble(split[19]),
                    fastParseDouble(split[23])
            );

            consumerBookTicker.accept(bookTickDouble);
        }

        // Longitud del Pong
        if (7 == split.length && telemetry != null) {
            waitingForPong = false;
            telemetry.setCurrentDeltaDelayPingPongNanoTime(System.nanoTime() - delayPingPongNanoTime);
        }
    }

    @Override
    protected String getPingPayload(@NotNull String wwsURL) {
        return """
                {"id":"%d","method":"ping","params":{}}
                """.formatted(id.incrementAndGet());
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
        List<String> symbolsNames = getAllSymbolsNames();
        HashMap<String, Symbol> symbols = new HashMap<>();

        int i = 0;
        for (String symbolName : symbolsNames) {
            JsonNode responseSymbol = sendPublicRequest(Method.GET, "/v1/symbols/details/%s".formatted(symbolName), new TreeMap<>());

            symbols.put(symbolName, new Symbol(
                    responseSymbol.get("symbol").asText(),
                    precision(Double.parseDouble(responseSymbol.get("quote_increment").asText())),
                    precision(Double.parseDouble(responseSymbol.get("tick_size").asText())),
                    responseSymbol.get("base_currency").asText(),
                    responseSymbol.get("quote_currency").asText(),
                    responseSymbol.get("status").asText().equals("open"),
                    new BigDecimal(responseSymbol.get("tick_size").asText()),
                    new BigDecimal("5")
            ));
            if ((i % 100) == 0) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
            i++;
        }
        cachedSymbols.clear();
        cachedSymbols.putAll(symbols);
        return symbols;
    }

    private static int precision(double value) {
        BigDecimal bd = BigDecimal.valueOf(value).stripTrailingZeros();
        return Math.max(0, bd.scale());
    }

    @Override
    public @NotNull Map<String, BookTickDouble> sGetAllBooks() {
        List<String> symbolsNames = getAllSymbolsNames();
        Map<String, BookTickDouble> result = new HashMap<>();
        for (String symbolName : symbolsNames) {
            Map<String, Object> parameters = new TreeMap<>();
            parameters.put("limit_bids", 1);
            parameters.put("limit_asks", 1);

            JsonNode node = sendPublicRequest(Method.GET, "/v1/book/%s".formatted(symbolName), parameters);

            try {
                JsonNode bid = node.get("bids").iterator().next();
                JsonNode ask = node.get("asks").iterator().next();

                result.put(symbolName, new BookTickDouble(
                        symbolName,
                        fastParseDouble(bid.get("price").asText()),
                        fastParseDouble(bid.get("amount").asText()),
                        fastParseDouble(ask.get("price").asText()),
                        fastParseDouble(ask.get("amount").asText()))
                );
            }catch (NoSuchElementException e){
                result.put(symbolName, new BookTickDouble(
                        symbolName,
                        0d,
                        0d,
                        0d,
                        0d
                ));
            }
        }
        return result;
    }

    @Override
    public @NotNull Map<String, Volume24H> sGetVolume24H() {
        List<String> symbolsNames = getAllSymbolsNames();
        Map<String, Volume24H> result = new HashMap<>();
        for (String symbolName : symbolsNames) {
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

    private final AtomicInteger id = new AtomicInteger();

    @Override
    protected void subscribeBookTickerBatch(@NotNull List<String> symbols) {
        String params = symbols.stream()
                .map(s -> "\"" + s + "@bookTicker\"")
                .collect(Collectors.joining(","));

        String json = """
        {"id":"%d", "method":"SUBSCRIBE","params":[%s]}
        """.formatted(id.incrementAndGet(), params);
        sendWebSocket(json);
    }

    @Override
    public void unsubscribeBookTicker(@NotNull Consumer<BookTickDouble> listener) {

    }

    @Override
    public void close() {

    }

    private List<String> allSymbolsCache = null;

    private List<String> getAllSymbolsNames(){
        @NotNull JsonNode response = sendPublicRequest(Method.GET, "/v1/symbols", new TreeMap<>());

        List<String> symbolsNames = new ArrayList<>();
        response.forEach(s -> symbolsNames.add(s.asText().toUpperCase(Locale.ROOT)));
        return List.copyOf(Objects.requireNonNullElseGet(allSymbolsCache, () -> allSymbolsCache = symbolsNames));
    }
}