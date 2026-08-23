package dev.cerez.tahp.connector.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cerez.tahp.connector.BaseConnector;
import dev.cerez.tahp.connector.model.BookTicker;
import dev.cerez.tahp.connector.model.OrderResult;
import dev.cerez.tahp.connector.model.Symbol;
import dev.cerez.tahp.connector.model.Volume24H;
import dev.cerez.tahp.engine.model.Action;
import lombok.SneakyThrows;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class BinanceConnector extends BaseConnector {

    private static final String BASE_HTTPS = "https://api.binance.com";
    private static final String BASE_TESTNET_WWS = "wss://demo-stream.binance.com:9443/stream";
    private static final String BASE_WWS = "wss://stream.binance.com:9443/stream";
    private static final String BASE_HTTPS_FUTURE = "https://fapi.binance.com";
    private static final String BASE_TESTNET_FUTURE = "https://testnet.binancefuture.com";

    public BinanceConnector(boolean isTestNet) {
        super(isTestNet);
    }

    @SneakyThrows
    public void checkApikey() {

    }

    @Override
    protected void handleStreamMessage(@NotNull String payload) {
        String[] split = payload.split("\"");
        if (split.length == 29) {
            BookTicker bookTicker = new BookTicker(
                    split[11],
                    fastParseDouble(split[15]),
                    fastParseDouble(split[19]),
                    fastParseDouble(split[23]),
                    fastParseDouble(split[27])
            );
            this.consumerBookTicker.accept(bookTicker);
        }
    }

    @Override
    public void invalidedCache() {
    }

    @Contract(" -> new")
    @Override
    protected @NotNull URL getURL() {
        return new URL(BASE_HTTPS, BASE_WWS);
    }

    @Override
    protected @Nullable String getPingPayload() {
        return null;
    }

    @Override
    @SneakyThrows
    public @NotNull Map<String, Symbol> getAllSymbols() {
//        var response = api.exchangeInfo(new ExchangeInfoRequest().showPermissionSets(true)).get(TIMEOUT, TimeUnit.SECONDS);
        JsonNode raw = sendPublicRequest(Method.GET, "/api/v1/exchangeInfo", new HashMap<>());
        HashMap<String, Symbol> symbols = new HashMap<>();

        for (JsonNode node : raw.get("symbols")) {
            double stepsize = Double.NaN;
            for (JsonNode filters : node.get("filters")) {
                JsonNode type = filters.get("filterType");
                if (type != null && "LOT_SIZE".equals(type.asText())) {
                    stepsize = filters.get("stepSize").doubleValue();
                }
            }
            if (Double.isNaN(stepsize)){
                continue;
            }
            symbols.put(node.get("symbol").asText(), new Symbol(
                    node.get("symbol").asText(),
                    node.get("baseAssetPrecision").asInt(),
                    node.get("quotePrecision").asInt(),
                    node.get("baseAsset").asText(),
                    node.get("quoteAsset").asText(),
                    "TRADING".equals(node.get("status").asText()),
                    stepsize,
                    Set.of()
            ));
//            if (!isTestNet) if (!s.getPermissions().contains("TRD_GRP_074")) continue;
//            symbols.put(symbol.getSymbol(), s);
        }
        cachedSymbols.clear();
        cachedSymbols.putAll(symbols);
        return symbols;
    }

    @Override
    @SneakyThrows
    public @NotNull Map<String, BookTicker> getAllBooks() {
        Map<String, BookTicker> result = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        params.put("symbolStatus", "TRADING");
        JsonNode raw = sendPublicRequest(Method.GET, "/api/v3/ticker/bookTicker", params);
        return result;
    }

    @Override
    @SneakyThrows
    public @NotNull Map<String, Volume24H> getVolume24H() {
        Map<String, Volume24H> result = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        params.put("type", "MINI");
        params.put("symbolStatus", "TRADING");
        JsonNode raw = sendPublicRequest(Method.GET, "/api/v3/ticker/24hr", params);
        for (JsonNode node : raw) {
            String s = node.get("symbol").asText();
            result.put(s, new  Volume24H(
                    s,
                    Double.parseDouble(node.get("quoteVolume").asText()),
                    Double.parseDouble(node.get("volume").asText())
            ));
        }

        return result;
    }

    @Override
    @SneakyThrows
    public @NotNull HashMap<String, Double> getBalance() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    @SneakyThrows
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

    @Override
    protected void subscribeBookTickerBatch(@NotNull List<String> streams) {
        if (streams.isEmpty()) {
            return;
        }
        UUID uuid = UUID.randomUUID();
        String params = streams.stream()
                .map(s -> "\"" + s.toLowerCase(Locale.US) + "@bookTicker\"")
                .collect(Collectors.joining(","));
        String json = """
                {"method": "SUBSCRIBE","params": [%s],"id": "%s"}
                """.formatted(params, uuid.toString().replace("-", ""));

        if (savePendingRequest(json)) return;
        webSocket.sendText(json, true).join();
    }

    @Override
    protected @NotNull String getApiRestURL(@NotNull String baseURL) {
        return BASE_HTTPS;
    }
}
