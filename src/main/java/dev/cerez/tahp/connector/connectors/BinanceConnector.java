package dev.cerez.tahp.connector.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cerez.tahp.connector.ActionOrden;
import dev.cerez.tahp.connector.BaseConnector;
import dev.cerez.tahp.connector.model.*;
import dev.cerez.tahp.triangular.engine.model.Action;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class BinanceConnector extends BaseConnector {

    private static final String BASE_HTTPS = "https://api.binance.com";
    private static final String BASE_TESTNET_HTTPS = "https://testnet.binance.vision";

    private static final String BASE_WWS = "wss://stream.binance.com:9443/stream";
    private static final String BASE_TESTNET_WWS = "wss://demo-stream.binance.com:9443/stream";

    private static final String BASE_HTTPS_FUTURE = "https://fapi.binance.com";
    private static final String BASE_TESTNET_HTTPS_FUTURE = "https://testnet.binancefuture.com";

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

    @Override
    protected @Nullable String getPingPayload() {
        return null;
    }

    @SuppressWarnings("DuplicatedCode")
    @Override
    @SneakyThrows
    public @NotNull Map<String, Symbol> getAllSymbols() {
        JsonNode raw = sendPublicRequest(Method.GET, "/api/v1/exchangeInfo", new HashMap<>());
        HashMap<String, Symbol> symbols = new HashMap<>();

        for (JsonNode node : raw.get("symbols")) {
            double stepsize = Double.NaN;
            double minNotional = Double.NaN;
            for (JsonNode filters : node.get("filters")) {
                JsonNode type = filters.get("filterType");
                if ("LOT_SIZE".equals(type.asText())) {
                    stepsize = filters.get("stepSize").asDouble();
                }
                if ("NOTIONAL".equals(type.asText())) {
                    minNotional = filters.get("minNotional").asDouble();
                }
            }
            if (Double.isNaN(stepsize) || Double.isNaN(minNotional)) {
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
                    minNotional

            ));
        }
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
            result.put(s, new Volume24H(
                    s,
                    Double.parseDouble(node.get("quoteVolume").asText()),
                    Double.parseDouble(node.get("volume").asText())
            ));
        }

        return result;
    }

    @Override
    @SneakyThrows // Spot
    public @NotNull Map<String, Double> getBalance() {
        JsonNode raw = sendSignedRequest(Method.GET, "/api/v3/account", new TreeMap<>());
        Map<String, Double> result = new HashMap<>();
        for (JsonNode node : raw.get("balances")) {
            result.put(node.get("asset").asText(), Double.parseDouble(node.get("free").asText()));
        }
        return result;
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
    protected @NotNull String getHTTPS() {
        return isTestNet ? BASE_TESTNET_HTTPS : BASE_HTTPS;
    }

    @Override
    protected @NotNull String getWWS() {
        return isTestNet ? BASE_TESTNET_WWS : BASE_WWS;
    }

    /////////////////////////////////////////////////////////
    /////////////////////////////////////////////////////////

    private @NotNull String fGetHttps(){
        return isTestNet ? BASE_TESTNET_HTTPS_FUTURE : BASE_HTTPS_FUTURE;
    }

    public void wTransfer(@Nullable String symbol, @NotNull Transfer transfer, @NotNull String asset, double amount) {
        Map<String, Object> params = new HashMap<>();
        params.put("asset", asset.toLowerCase(Locale.US));
        params.put("amount", amount);
        params.put("type", transfer.getMethod());
        if (transfer == Transfer.MARGIN_TO_ISOLATED){
            Objects.requireNonNull(symbol);
            params.put("toSymbol", symbol);
        }
        if (transfer == Transfer.ISOLATED_TO_MARGIN){
            Objects.requireNonNull(symbol);
            params.put("fromSymbol", symbol);
        }
        sendSignedRequest(Method.POST, "/sapi/v1/asset/transfer", params);
    }

    @Getter
    @RequiredArgsConstructor
    public enum Transfer {
        SPOT_TO_FUTURE("MAIN_UMFUTURE"),
        FUTURE_TO_SPOT("UMFUTURE_MAIN"),
        SPOT_TO_MARGIN("MAIN_MARGIN"),
        MARGIN_TO_SPOT("MARGIN_MAIN"),
        MARGIN_TO_ISOLATED("MARGIN_ISOLATEDMARGIN"),
        ISOLATED_TO_MARGIN("ISOLATEDMARGIN_MARGIN");
        private final String method;
    }

    public double sGetPrice(@NotNull String symbol) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        return Double.parseDouble(sendPublicRequest(Method.GET, "/api/v3/ticker/price", params).get("price").textValue());
    }

    public void fSendOrderToMkt(@NotNull String symbol, @NotNull ActionOrden actionOrden, double amountBase, @NotNull String nameOrder) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        params.put("side", actionOrden);
        params.put("type", "MARKET");
        params.put("quantity", cachedSymbols.get(symbol).roundTickSize(amountBase));
        params.put("newClientOrderId", nameOrder);
        params.put("reduceOnly", true);
        sendSignedRequest(fGetHttps(), Method.POST, "/fapi/v1/order", params);
    }

    public @Nullable Position fGetPosition(@NotNull String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        JsonNode raw = sendSignedRequest(fGetHttps(), Method.GET, "/fapi/v3/positionRisk", params);
        for (JsonNode node : raw) {
            if (node.get("symbol").asText().equals(symbol.toUpperCase(Locale.US))) {
                return new Position(node.get("positionAmt").asDouble());
            }
        }
        return null;
    }

    public record Position(double quantity) {}

    private final HashMap<String, Symbol> fCachedSymbols = new HashMap<>();

    @SuppressWarnings("DuplicatedCode")
    public Map<String, Symbol> fGetAllSymbol() {
        Map<String, Symbol> symbols = new HashMap<>();
        JsonNode raw = sendPublicRequest(fGetHttps(), Method.GET, "/fapi/v1/exchangeInfo");
        for (JsonNode node : raw.get("symbols")) {
            double stepsize = Double.NaN;
            double minNotional = Double.NaN;
            for (JsonNode filters : node.get("filters")) {
                JsonNode type = filters.get("filterType");
                if ("LOT_SIZE".equals(type.asText())) {
                    stepsize = filters.get("stepSize").asDouble();
                }
                if ("MIN_NOTIONAL".equals(type.asText())) {
                    minNotional = filters.get("notional").asDouble();
                }
            }
            if (Double.isNaN(stepsize)) {
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
                    minNotional

            ));
        }
        fCachedSymbols.clear();
        fCachedSymbols.putAll(symbols);
        return symbols;
    }

    public void fSetLeverage(@NotNull String symbol, int leverage) {
        Map<String, Object> params = new HashMap<>();
        params.put("leverage", leverage);
        params.put("symbol", symbol.toUpperCase(Locale.US));
        sendSignedRequest(Method.POST, "/fapi/v1/leverage", params);
    }

    public double fGetPrice(@NotNull String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        return sendPublicRequest(fGetHttps(), Method.GET, "/fapi/v1/premiumIndex", params).get("markPrice").asDouble();
    }

    public Map<String, FundingRate> fGetFundingRate(){
        JsonNode raw0 = sendPublicRequest(fGetHttps(), Method.GET, "/fapi/v1/fundingInfo");
        Map<String, FundingConfig> result0 = new HashMap<>();
        for (JsonNode node : raw0) {
            String symbol = node.get("symbol").asText();
            result0.put(symbol,
                    new FundingConfig(node.get("adjustedFundingRateFloor").asDouble(),
                            node.get("adjustedFundingRateCap").asDouble(),
                            node.get("fundingIntervalHours").asInt()
                    )
            );
        }
        JsonNode raw1 = sendPublicRequest(fGetHttps(), Method.GET, "/fapi/v1/premiumIndex");
        Map<String, FundingRate> result1 = new HashMap<>();
        for (JsonNode node : raw1) {
            String symbol = node.get("symbol").asText();
            FundingConfig fundingConfig = result0.get(symbol);
            if (fundingConfig == null) {
                continue;
            }
            result1.put(symbol, new FundingRate(
                    symbol,
                    fundingConfig.min,
                    fundingConfig.max,
                    fundingConfig.interval,
                    node.get("lastFundingRate").asDouble(),
                    node.get("nextFundingTime").asLong())
            );
        }
        return result1;
    }

    private record FundingConfig(double min, double max, int interval) {}

    public record FundingRate(String symbol, double min, double max, int interval, double nextFundingRate, long nextFundingTime) {
        public double rate24h(){
            return  (24d / interval) * nextFundingRate;
        }

        public double reate24hAbs(){
            return Math.abs(rate24h());
        }
    }

    public boolean cPossibleConvert(@NotNull String fromAsset, @NotNull String toAsset) {
        Map<String, Object> params = new HashMap<>();
        params.put("fromAsset", fromAsset);
        params.put("toAsset", toAsset);
        JsonNode node = sendPublicRequest(Method.GET, "/sapi/v1/convert/exchangeInfo", params);
        return !node.isEmpty();
    }

    public Convert cGetMinMaxConvert(@NotNull String fromAsset, @NotNull String toAsset) {
        Map<String, Object> params = new HashMap<>();
        params.put("fromAsset", fromAsset);
        params.put("toAsset", toAsset);
        JsonNode raw = sendPublicRequest(Method.GET, "/sapi/v1/convert/exchangeInfo", params);
        JsonNode node = raw.iterator().next();
        return new Convert(node.get("fromAssetMinAmount").asDouble(),
                node.get("fromAssetMaxAmount").asDouble(),
                node.get("toAssetMinAmount").asDouble(),
                node.get("toAssetMaxAmount").asDouble()
        );
    }

    public String cConvert(@NotNull String fromAsset, @NotNull String toAsset, double amount, boolean fromAmount) {
        Map<String, Object> params = new HashMap<>();
        params.put("fromAsset", fromAsset);
        params.put("toAsset", toAsset);
        params.put("walletType", "SPOT");
        if (fromAmount) {
            params.put("fromAmount", amount);
        }else {
            params.put("toAmount", amount);
        }
        return sendSignedRequest(Method.GET, "/sapi/v1/convert/getQuote", params).get("quoteId").asText();
    }

    public ConvertStatus cAccept(@NotNull String id) {
        Map<String, Object> params = new HashMap<>();
        params.put("quoteId", id);
        return ConvertStatus.valueOf(sendSignedRequest(Method.PUT, "/sapi/v1/convert/accept", params).get("orderStatus").asText());
    }

    public enum ConvertStatus {
        PROCESS,
        ACCEPT_SUCCESS,
        SUCCESS,
        FAIL
    }

    public record Convert(double fromMin, double fromMax, double toMin, double toMax) {}

    public double mGetInterest(@NotNull String asset) {
        Map<String, Object> params = new HashMap<>();
        params.put("asset", asset.toUpperCase(Locale.US));
        params.put("isIsolated", true);
        JsonNode node = sendSignedRequest(Method.GET, "/sapi/v1/margin/next-hourly-interest-rate", params);
        return node.get("nextHourlyInterestRate").asDouble();
    }

    public void mSetEnableInsolated(@NotNull String symbol, boolean enable) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        if (enable) {
            sendSignedRequest(Method.PUT, "/sapi/v1/margin/isolated/account", params);
        } else {
            sendSignedRequest(Method.DELETE, "/sapi/v1/margin/isolated/account", params);
        }
    }

    public boolean mIsEnableInsolated(@NotNull String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        JsonNode raw = sendSignedRequest(Method.DELETE, "/sapi/v1/margin/isolated/account", params);
        for (JsonNode node : raw) {
            if (node.get("symbol").asText().equals(symbol.toUpperCase(Locale.US))) {
                return node.get("enabled").asBoolean();
            }
        }
        return false;
    }

    public boolean mIsAllowInsolated(@NotNull String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        JsonNode raw = sendSignedRequest(Method.DELETE, "/sapi/v1/margin/isolated/account", params);
        for (JsonNode node : raw) {
            if (node.get("symbol").asText().equals(symbol.toUpperCase(Locale.US))) {
                return node.get("tradeEnabled").asBoolean() && node.get("isolatedCreated").asBoolean();
            }
        }
        return false;
    }

    public double mGetMaxBorrowable(@NotNull String symbol, @NotNull String asset) {
        Map<String, Object> params = new HashMap<>();
        params.put("isolatedSymbol", symbol.toUpperCase(Locale.US));
        params.put("asset", asset.toUpperCase(Locale.US));
        JsonNode node = sendSignedRequest(Method.GET, "/sapi/v1/margin/maxBorrowable", params);
        return node.get("amount").asDouble();
    }

    public double mGetBorrowed(@NotNull String symbol) {
        JsonNode raw = sendSignedRequest(Method.GET, "/sapi/v1/margin/isolated/account");
        for (JsonNode node : raw) {
            if (node.get("symbol").asText().equals(symbol.toUpperCase(Locale.US))) {
                JsonNode base = node.get("baseAsset");
                return base.get("borrowed").asDouble() + base.get("interest").asDouble();
            }
        }
        return 0d;
    }

    public void mBorrow(@NotNull String symbol, @NotNull String asset, double amount) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        params.put("asset", asset.toUpperCase(Locale.US));
        params.put("amount", amount);
        params.put("isIsolated", true);
        params.put("type", "BORROW");
        sendSignedRequest(Method.POST, "/sapi/v1/margin/borrow-repay", params);
    }

    public Map<String, BalanceInsolated> mGetBalance(@NotNull List<String> symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", String.join(",", symbol).toUpperCase(Locale.US));
        JsonNode raw = sendSignedRequest(Method.GET, "/sapi/v1/margin/balance", params);
        Map<String, BalanceInsolated> map = new HashMap<>();
        for (JsonNode node : raw) {
            JsonNode baseNode = node.get("baseAsset");

            AssetMargin base = new AssetMargin(
                    baseNode.get("asset").asText(),
                    baseNode.get("borrowEnabled").asBoolean(),
                    baseNode.get("repayEnabled").asBoolean(),
                    baseNode.get("borrowed").asDouble()
            );
            JsonNode quoteNode = node.get("quoteAsset");
            AssetMargin quote = new AssetMargin(
                    quoteNode.get("asset").asText(),
                    quoteNode.get("borrowEnabled").asBoolean(),
                    quoteNode.get("repayEnabled").asBoolean(),
                    quoteNode.get("borrowed").asDouble()
            );
            var balance = new BalanceInsolated(base, quote);
            map.put(balance.symbol(), balance);
        }
        return map;
    }

    public record BalanceInsolated(AssetMargin base, AssetMargin quote) {
        @Contract(pure = true)
        public @NotNull String symbol() {
            return base.asset + quote.asset;
        }
    }

    public record AssetMargin(String asset, boolean borrowEnable, boolean repayEnable, double borrowed) {}

    public void mSendOrderToMkt(@NotNull String symbol, @NotNull ActionOrden actionOrden, double amountBase, @NotNull String nameOrder) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        params.put("side", actionOrden);
        params.put("type", "MARKET");
        params.put("quantity", cachedSymbols.get(symbol).roundTickSize(amountBase));
        params.put("isIsolated", true);
        params.put("newClientOrderId", nameOrder);
        sendSignedRequest(Method.POST, "/sapi/v1/margin/order", params);
    }

    public void mRepay(@NotNull String symbol, @NotNull String asset, double amount) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        params.put("asset", asset.toUpperCase(Locale.US));
        params.put("amount", amount);
        params.put("isIsolated", true);
        params.put("type", "REPAY");
        sendSignedRequest(Method.GET, "/sapi/v1/margin/repay", params);
    }
}
