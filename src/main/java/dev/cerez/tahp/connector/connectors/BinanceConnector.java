package dev.cerez.tahp.connector.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cerez.tahp.connector.model.ActionOrden;
import dev.cerez.tahp.connector.BaseConnector;
import dev.cerez.tahp.connector.model.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class BinanceConnector extends BaseConnector {

    private static final String BASE_HTTPS = "https://api.binance.com";
    private static final String BASE_TESTNET_HTTPS = "https://testnet.binance.vision";

    private static final String BASE_WWS_STREAM = "wss://stream.binance.com:9443/stream";
    private static final String BASE_TESTNET_WWS_STREAM = "wss://demo-stream.binance.com:9443/stream";

    private static final String BASE_HTTPS_FUTURE = "https://fapi.binance.com";
    private static final String BASE_TESTNET_HTTPS_FUTURE = "https://testnet.binancefuture.com";

    private static final String BASE_WWS_FUTURE_STREAM = "wss://fstream.binance.com/stream";
    private static final String BASE_WWS_FUTURE_STREAM_ALT = "wss://stream.binancefuture.com/stream";

    public BinanceConnector(boolean isTestNet) {
        super(isTestNet);
    }

    @Override
    protected void handleStream(@NotNull String wwsURL, @NotNull String contentToParse) {
        String[] split = contentToParse.split("\"");

        // Parsing express
        if (split.length == 29) {
            BookTickDouble bookTickDouble = new BookTickDouble(
                    split[11],
                    fastParseDouble(split[15]),
                    fastParseDouble(split[19]),
                    fastParseDouble(split[23]),
                    fastParseDouble(split[27])
            );
            if (this.consumerBookTicker != null) this.consumerBookTicker.accept(bookTickDouble);
        }

        // Paring de otras requests
        String stream = split[3];
        Consumer<String> consumer = consumerStreamsMap.get(wwsURL + "@" + stream);
        if (consumer == null) return;
        String payload = contentToParse.replaceAll("\\s", "").substring(20 + stream.length(), contentToParse.length()-1);
        consumer.accept(payload);
    }

    @Override
    protected @Nullable String getPingPayload(@NotNull String wwsURL) {
        return null;
    }

    @Override
    protected @NotNull Set<String> getBlackListEndpointLog() {
        return Set.of("/api/v3/time");
    }

    @SuppressWarnings("DuplicatedCode")
    @Override
    public @NotNull Map<String, Symbol> sGetAllSymbols() {
        JsonNode raw = sendPublicRequest(Method.GET, "/api/v1/exchangeInfo", new HashMap<>());
        HashMap<String, Symbol> symbols = new HashMap<>();

        for (JsonNode node : raw.get("symbols")) {
            BigDecimal stepsize = BigDecimal.ZERO;
            BigDecimal minNotional = BigDecimal.ZERO;
            for (JsonNode filters : node.get("filters")) {
                JsonNode type = filters.get("filterType");
                if ("LOT_SIZE".equals(type.asText())) {
                    stepsize = new BigDecimal(filters.get("stepSize").asText());
                }
                if ("NOTIONAL".equals(type.asText())) {
                    minNotional = new BigDecimal(filters.get("minNotional").asText());
                }
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
    public @NotNull Map<String, BookTickDouble> sGetAllBooks() {
        Map<String, BookTickDouble> result = new HashMap<>();
        Map<String, Object> params = new HashMap<>();
        params.put("symbolStatus", "TRADING");
        JsonNode raw = sendPublicRequest(Method.GET, "/api/v3/ticker/bookTicker", params);
        return result;
    }

    @Override
    public @NotNull Map<String, Volume24H> sGetVolume24H() {
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
    public @NotNull Map<String, BigDecimal> sGetBalance() {
        JsonNode raw = sendSignedRequest(Method.GET, "/api/v3/account", new TreeMap<>());
        Map<String, BigDecimal> result = new HashMap<>();
        for (JsonNode node : raw.get("balances")) {
            result.put(node.get("asset").asText(), new BigDecimal(node.get("free").asText()));
        }
        return result;
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
        return sendPublicRequest(Method.GET,"/api/v3/time").get("serverTime").asLong();
    }

    @Override
    public void unsubscribeBookTicker(@NotNull Consumer<BookTickDouble> listener) {

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

        sendWebSocket(json);
    }

    @Override
    public @NotNull String getHTTPS() {
        return isTestNet ? BASE_TESTNET_HTTPS : BASE_HTTPS;
    }

    @Override
    public @NotNull String getWWS() {
        return isTestNet ? BASE_TESTNET_WWS_STREAM : BASE_WWS_STREAM;
    }

    public static class BinanceKeys extends Keys{
        public BinanceKeys(String key, String secret) {
            super(key, secret);
        }
    }

    /////////////////////////////////////////////////////////
    /////////////////////////////////////////////////////////

    public void wTransfer(@Nullable String symbol, @NotNull Transfer transfer, @NotNull String asset, @NotNull BigDecimal amount) {
        Map<String, Object> params0 = new HashMap<>();
        params0.put("asset", asset.toUpperCase(Locale.US));
        params0.put("amount", amount);
        params0.put("type", transfer.getMethod());
        if (transfer == Transfer.MARGIN_TO_ISOLATED){
            Objects.requireNonNull(symbol);
            params0.put("toSymbol", symbol);
        }
        if (transfer == Transfer.ISOLATED_TO_MARGIN){
            Objects.requireNonNull(symbol);
            params0.put("fromSymbol", symbol);
        }
        BigDecimal prevBalance = switch (transfer){
            case FUTURE_TO_SPOT, MARGIN_TO_SPOT -> sGetBalance().get(asset);
            case ISOLATED_TO_MARGIN, SPOT_TO_MARGIN -> mcGetBalance(asset).free();
            case MARGIN_TO_ISOLATED -> miGetBalance(symbol).asset(asset).free();
            case SPOT_TO_FUTURE -> fGetBalance(asset);
        };
        sendSignedRequest(Method.POST, "/sapi/v1/asset/transfer", params0);
        int attempts = 0;
        while (attempts++ < 10){
            BigDecimal postBalance = switch (transfer){
                case FUTURE_TO_SPOT, MARGIN_TO_SPOT -> sGetBalance().get(asset);
                case MARGIN_TO_ISOLATED -> miGetBalance(symbol).asset(asset).free();
                case ISOLATED_TO_MARGIN, SPOT_TO_MARGIN -> mcGetBalance(asset).free();
                case SPOT_TO_FUTURE -> fGetBalance(asset);
            };
            if ((postBalance.subtract(prevBalance)).compareTo(amount) == 0){
                break;
            }
            // ¿Sirve tener esto aquí???
            prevBalance = postBalance;
        }
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

    public BigDecimal sGetPrice(@NotNull String symbol) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        return new BigDecimal(sendPublicRequest(Method.GET, "/api/v3/ticker/price", params).get("price").textValue());
    }

    public @Nullable Order sGetOrder(@NotNull String symbol, @NotNull String nameOrder) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        JsonNode raw =  sendSignedRequest(Method.GET, "/api/v3/allOrders", params);
        for (JsonNode node : raw){
            if (node.get("clientOrderId").asText().equals(nameOrder)) {
                return new Order(new BigDecimal(node.get("executedQty").asText()), new BigDecimal(node.get("cummulativeQuoteQty").asText()));
            }
        }
        return null;
    }

    @Contract("_ -> new")
    public @NotNull BinanceConnector.BookTick sGetFullPrice(@NotNull String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        JsonNode node = sendPublicRequest(Method.GET, "/api/v3/depth", params);
        JsonNode bid = node.get("bids").iterator().next();
        JsonNode ask = node.get("asks").iterator().next();
        BigDecimal bidPrice = new BigDecimal(bid.iterator().next().asText());
        BigDecimal askPrice = new BigDecimal(ask.iterator().next().asText());
        return new BookTick(bidPrice, BigDecimal.ZERO, askPrice, BigDecimal.ZERO);
    }

    public long sPing(){
        long start = System.nanoTime();
        sendPublicRequest(Method.GET, "/api/v3/ping");
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    public void wsCreateBookTicker(@NotNull Consumer<BookTick> consumer, @NotNull String symbol){
        UUID uuid = UUID.randomUUID();
        String stream = symbol.toLowerCase(Locale.US) + "@bookTicker";
        addConsumerStreams(getWWS() + "@" + stream, (payload) -> {
            String[] split = payload.split("\"");
            consumer.accept(new BookTick(new BigDecimal(split[9]), new BigDecimal(split[13]), new BigDecimal(split[17]), new BigDecimal(split[21])));
        });

        sendWebSocket(getWWS(), """
                {"method": "SUBSCRIBE","params": ["%s"],"id": "%s"}
                """.formatted(stream, uuid.toString().replace("-", "")));
    }

    public void wsRemoveBookTicker(@NotNull String symbol) {
        UUID uuid = UUID.randomUUID();
        String stream = symbol.toLowerCase(Locale.US) + "@bookTicker";
        removeConsumerStreams(getWWS() + "@" + stream);
        sendWebSocket(getWWS(), """
                {"method": "UNSUBSCRIBE","params": ["%s"],"id": "%s"}
                """.formatted(stream, uuid.toString().replace("-", "")));
    }

    private @NotNull String fGetHttps(){
        return isTestNet ? BASE_TESTNET_HTTPS_FUTURE : BASE_HTTPS_FUTURE;
    }

    public @NotNull String fGetWWS(){
        return BASE_WWS_FUTURE_STREAM;
    }

    public @NotNull BigDecimal fGetBalance(String asset) {
        JsonNode raw = sendSignedRequest(Method.GET, "/fapi/v3/balance");
        for (JsonNode node : raw){
            if (node.get("asset").asText().equals(asset)) {
                return new BigDecimal(node.get("maxWithdrawAmount").asText());
            }
        }
        return BigDecimal.ZERO;
    }

    public void fSendOrderToMkt(@NotNull String symbol, @NotNull ActionOrden actionOrden, BigDecimal amountBase, @NotNull String nameOrder) {
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
                return new Position(new BigDecimal(node.get("positionAmt").asText()));
            }
        }
        return null;
    }

    private final HashMap<String, Symbol> fCachedSymbols = new HashMap<>();

    @SuppressWarnings("DuplicatedCode")
    public Map<String, Symbol> fGetAllSymbol() {
        Map<String, Symbol> symbols = new HashMap<>();
        JsonNode raw = sendPublicRequest(fGetHttps(), Method.GET, "/fapi/v1/exchangeInfo");
        for (JsonNode node : raw.get("symbols")) {
            BigDecimal stepsize = BigDecimal.ZERO;
            BigDecimal minNotional = BigDecimal.ZERO;
            for (JsonNode filters : node.get("filters")) {
                JsonNode type = filters.get("filterType");
                if ("LOT_SIZE".equals(type.asText())) {
                    stepsize = new BigDecimal(filters.get("stepSize").asText());
                }
                if ("MIN_NOTIONAL".equals(type.asText())) {
                    minNotional = new BigDecimal(filters.get("notional").asText());
                }
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

    public long fPing(){
        long start = System.nanoTime();
        sendPublicRequest(fGetHttps(), Method.GET, "/fapi/v1/ping");
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    public void fSetLeverage(@NotNull String symbol, int leverage) {
        Map<String, Object> params = new HashMap<>();
        params.put("leverage", leverage);
        params.put("symbol", symbol.toUpperCase(Locale.US));
        sendSignedRequest(Method.POST, "/fapi/v1/leverage", params);
    }

    @Contract("_ -> new")
    public @NotNull BigDecimal fGetPrice(@NotNull String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        return new BigDecimal(sendPublicRequest(fGetHttps(), Method.GET, "/fapi/v1/premiumIndex", params).get("markPrice").asText());
    }

    @Contract("_ -> new")
    public @NotNull BinanceConnector.BookTick fGetFullPrice(@NotNull String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        JsonNode node = sendPublicRequest(fGetHttps(), Method.GET, "/fapi/v1/depth", params);
        JsonNode bid = node.get("bids").iterator().next();
        JsonNode ask = node.get("asks").iterator().next();
        BigDecimal bidPrice = new BigDecimal(bid.iterator().next().asText());
        BigDecimal askPrice = new BigDecimal(ask.iterator().next().asText());
        return new BookTick(bidPrice, BigDecimal.ZERO, askPrice, BigDecimal.ZERO);
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

    public void wfCreateBookTicker(@NotNull Consumer<BookTick> consumer, @NotNull String symbol) {
        UUID uuid = UUID.randomUUID();
        String stream = symbol.toLowerCase(Locale.US) + "@bookTicker";
        addConsumerStreams(fGetWWS() + "@" + stream, (payload) -> {
            String[] split = payload.split("\"");
            consumer.accept(new BookTick(new BigDecimal(split[17]), new BigDecimal(split[21]), new BigDecimal(split[25]), new BigDecimal(split[29])));
        });

        sendWebSocket(fGetWWS(), """
                {"method": "SUBSCRIBE","params": ["%s"],"id": "%s"}
                """.formatted(stream, uuid.toString().replace("-", "")));
    }

    public void wfRemoveBookTicker(@NotNull String symbol) {
        UUID uuid = UUID.randomUUID();
        String stream = symbol.toLowerCase(Locale.US) + "@bookTicker";
        removeConsumerStreams(fGetWWS() + "@" + stream);
        sendWebSocket(fGetWWS(), """
                {"method": "UNSUBSCRIBE","params": ["%s"],"id": "%s"}
                """.formatted(stream, uuid.toString().replace("-", "")));
    }

    public boolean cPossibleConvert(@NotNull String fromAsset, @NotNull String toAsset) {
        Map<String, Object> params = new HashMap<>();
        params.put("fromAsset", fromAsset);
        params.put("toAsset", toAsset);
        JsonNode node = sendPublicRequest(Method.GET, "/sapi/v1/convert/exchangeInfo", params);
        return !node.isEmpty();
    }

    public @Nullable Convert cGetMinMaxConvert(@NotNull String fromAsset, @NotNull String toAsset) {
        Map<String, Object> params = new HashMap<>();
        params.put("fromAsset", fromAsset);
        params.put("toAsset", toAsset);
        JsonNode raw = sendPublicRequest(Method.GET, "/sapi/v1/convert/exchangeInfo", params);
        for (JsonNode node : raw){
            if (node.get("fromAsset").asText().equals(fromAsset) && node.get("toAsset").asText().equals(toAsset)){
                return new Convert(node.get("fromAssetMinAmount").asDouble(),
                        node.get("fromAssetMaxAmount").asDouble(),
                        node.get("toAssetMinAmount").asDouble(),
                        node.get("toAssetMaxAmount").asDouble()
                );
            }
        }
        return null;
    }

    public String cConvert(@NotNull String fromAsset, @NotNull String toAsset, BigDecimal amount, boolean fromAmount) {
        Map<String, Object> params = new HashMap<>();
        params.put("fromAsset", fromAsset);
        params.put("toAsset", toAsset);
        params.put("walletType", "SPOT");
        if (fromAmount) {
            params.put("fromAmount", amount);
        }else {
            params.put("toAmount", amount);
        }
        return sendSignedRequest(Method.POST, "/sapi/v1/convert/getQuote", params).get("quoteId").asText();
    }

    public void cAccept(@NotNull String id) {
        Map<String, Object> params = new HashMap<>();
        params.put("quoteId", id);
        sendSignedRequest(Method.POST, "/sapi/v1/convert/acceptQuote", params);
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

    public BigDecimal mGetBorrowed(@NotNull String symbol) {
        JsonNode raw = sendSignedRequest(Method.GET, "/sapi/v1/margin/isolated/account");
        for (JsonNode node : raw) {
            if (node.get("symbol").asText().equals(symbol.toUpperCase(Locale.US))) {
                JsonNode base = node.get("baseAsset");
                return new BigDecimal(base.get("borrowed").asText()).add(new BigDecimal(base.get("interest").asText()));
            }
        }
        return BigDecimal.ZERO;
    }

    public void mBorrow(@NotNull String symbol, @NotNull String asset, @NotNull BigDecimal amount) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        params.put("asset", asset.toUpperCase(Locale.US));
        params.put("amount", amount);
        params.put("isIsolated", true);
        params.put("type", "BORROW");
        sendSignedRequest(Method.POST, "/sapi/v1/margin/borrow-repay", params);
    }

    @Contract("_ -> new")
    public @NotNull BalanceInsolated miGetBalance(@NotNull String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        JsonNode raw = sendSignedRequest(Method.GET, "/sapi/v1/margin/isolated/account", params);
        for (JsonNode node : raw.get("assets")) {
            if (node.get("symbol").asText().equals(symbol.toUpperCase(Locale.US))) {
                JsonNode baseNode = node.get("baseAsset");

                AssetMargin base = new AssetMargin(
                        baseNode.get("asset").asText(),
                        new BigDecimal(baseNode.get("free").asText()),
                        new BigDecimal(baseNode.get("borrowed").asText()),
                        new BigDecimal(baseNode.get("interest").asText())
                );
                JsonNode quoteNode = node.get("quoteAsset");
                AssetMargin quote = new AssetMargin(
                        quoteNode.get("asset").asText(),
                        new BigDecimal(quoteNode.get("free").asText()),
                        new BigDecimal(quoteNode.get("borrowed").asText()),
                        new BigDecimal(quoteNode.get("interest").asText())
                );
                return new BalanceInsolated(base, quote);
            }
        }
        throw new NullPointerException("No such borrowed asset");
    }

    public @NotNull AssetMargin mcGetBalance(@NotNull String asset) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", asset.toUpperCase(Locale.US));
        JsonNode raw = sendSignedRequest(Method.GET, "/sapi/v1/margin/account", params);
        for (JsonNode node : raw.get("userAssets")) {
            String a = node.get("asset").asText();
            if (a.equals(asset.toUpperCase(Locale.US))) {
                return new AssetMargin(
                        a,
                        new BigDecimal(node.get("free").asText()),
                        new BigDecimal(node.get("borrowed").asText()),
                        new BigDecimal(node.get("interest").asText())
                );
            }
        }
        return new AssetMargin(asset, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public void mSendOrderToMkt(@NotNull String symbol, @NotNull ActionOrden actionOrden, BigDecimal amount, @NotNull String nameOrder, boolean amountInBaseAsset) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        params.put("side", actionOrden);
        params.put("type", "MARKET");
        if (amountInBaseAsset) {
            params.put("quantity", cachedSymbols.get(symbol).roundTickSize(amount));
        } else {
            params.put("quoteOrderQty", cachedSymbols.get(symbol).roundQuote(amount));
        }
        params.put("isIsolated", true);
        params.put("newClientOrderId", nameOrder);
        sendSignedRequest(Method.POST, "/sapi/v1/margin/order", params);
    }

    public void mRepay(@NotNull String symbol, @NotNull String asset, BigDecimal amount) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        params.put("asset", asset.toUpperCase(Locale.US));
        params.put("amount", amount);
        params.put("isIsolated", true);
        params.put("type", "REPAY");
        sendSignedRequest(Method.POST, "/sapi/v1/margin/repay", params);
    }

    public record Position(BigDecimal quantity) {}

    public record BookTick(BigDecimal bidPrice, BigDecimal bidQty, BigDecimal askPrice, BigDecimal askQty){}

    public record AssetMargin(String asset, BigDecimal free, BigDecimal borrowed, BigDecimal interest) {
        @Contract(pure = true)
        public @NotNull BigDecimal totalRepay(){
            return borrowed.add(interest);
        }
    }

    public record BalanceInsolated(AssetMargin base, AssetMargin quote) {
        @Contract(pure = true)
        public @NotNull String symbol() {
            return base.asset + quote.asset;
        }

        public AssetMargin asset(String asset) {
            if (base.asset.equals(asset)) {
                return base;
            }else return quote;
        }
    }

    public record FundingRate(String symbol, double min, double max, int interval, double nextFundingRate, long nextFundingTime) {
        public double rate24h(){
            return  (24d / interval) * nextFundingRate;
        }

        public double reate24hAbs(){
            return Math.abs(rate24h());
        }
    }

    private record FundingConfig(double min, double max, int interval) {}

    public record Order(BigDecimal baseAmount, BigDecimal quoteAmount) {}

}
