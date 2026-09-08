package dev.cerez.tahp.connector.connectors;

import com.fasterxml.jackson.databind.JsonNode;
import dev.cerez.tahp.Main;
import dev.cerez.tahp.connector.connectors.exception.BinanceApiException;
import dev.cerez.tahp.connector.connectors.exception.PostOnlyRejectException;
import dev.cerez.tahp.connector.exception.ApiException;
import dev.cerez.tahp.connector.exception.UnknownOrderException;
import dev.cerez.tahp.connector.model.SideOrder;
import dev.cerez.tahp.connector.BaseConnector;
import dev.cerez.tahp.connector.model.*;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.net.http.HttpRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
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
    private static final String BASE_WWS_FUTURE_USERDATA = "wss://fstream.binance.com/private/ws/%s";

    private String listenKey = null;

    public BinanceConnector() {
        super(Main.IS_TESTNET);
    }

    @Override
    public void invalidateCache(){
        fCachedSymbols.clear();
        super.invalidateCache();
    }

    @SneakyThrows
    @Override
    protected void handleStreamRaw(@NotNull String wwsURL, @NotNull String contentToParse) {
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
        JsonNode node = mapper.readTree(contentToParse);
        String stream;
        if (node.has("stream")) {
            stream = node.get("stream").asText();
        }else {
            stream = "";
        }
        String key = wwsURL + (stream.isEmpty() ? "" : "@") + stream;
        Consumer<JsonNode> consumer = consumerStreamsMap.get(key);
        if (consumer == null) return;
        if (node.has("data")) {
            consumer.accept(node.get("data"));
        }else {
            consumer.accept(node);
        }
    }

    @Override
    public void start(){
        super.start();
        fStartUserData();
    }

    @Override
    public void stop(){
        if (listenKey != null) {
            fCloseUserData();
        }
        super.stop();
    }

    @Override
    protected @Nullable String getPingPayload(@NotNull String wwsURL) {
        return null;
    }

    @Contract(value = " -> new", pure = true)
    @Override
    protected @NotNull @Unmodifiable Set<String> getBlackListEndpointLog() {
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
        cachedSymbols.clear();
        cachedSymbols.putAll(symbols);
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
    public void sSendOrderToMkt(@NotNull String symbol,
                                @NotNull SideOrder sideOrder,
                                @NotNull BigDecimal amount,
                                @Nullable String nameOrder,
                                boolean amountInBaseAsset
    ) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        params.put("side", sideOrder);
        params.put("type", "MARKET");
        if (amountInBaseAsset) {
            params.put("quantity", cachedSymbols.get(symbol).roundTickSize(amount));
        } else {
            params.put("quoteOrderQty", cachedSymbols.get(symbol).roundQuote(amount));
        }
//        params.put("isIsolated", true);
//        params.put("newClientOrderId", nameOrder);
        sendSignedRequest(Method.POST, "/api/v3/order", params);
    }

    @Override
    public @NotNull Long getTimeSever() {
        return sendPublicRequest(Method.GET, "/api/v3/time").get("serverTime").asLong();
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

    public static class BinanceKeys extends Keys {
        public BinanceKeys(String key, String secret) {
            super(key, secret);
        }
    }

    @Override
    protected void checkResponse(@NotNull JsonNode response, @NotNull HttpRequest request) throws ApiException {
        if (response.has("code")) {
            int code = response.get("code").asInt();
            String msg = "Error: Code=%d Message=%s Request=%s Method=%s".formatted(code, response.get("msg").asText(), request.uri().toString(), request.method());
            if (code != 200) switch (code) {
                case -2011 -> throw new UnknownOrderException(code, msg, request);
                case -5022 -> throw new PostOnlyRejectException(code, msg, request);
                default -> throw new BinanceApiException(code, msg, request);
            }
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
            case SPOT_TO_FUTURE -> fGetBalance().get(asset);
        };
        sendSignedRequest(Method.POST, "/sapi/v1/asset/transfer", params0);
        int attempts = 0;
        while (attempts++ < 10){
            BigDecimal postBalance = switch (transfer){
                case FUTURE_TO_SPOT, MARGIN_TO_SPOT -> sGetBalance().get(asset);
                case MARGIN_TO_ISOLATED -> miGetBalance(symbol).asset(asset).free();
                case ISOLATED_TO_MARGIN, SPOT_TO_MARGIN -> mcGetBalance(asset).free();
                case SPOT_TO_FUTURE -> fGetBalance().get(asset);
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

    public @Nullable BinanceConnector.SpotOrder sGetOrder(@NotNull String symbol, @NotNull String nameOrder) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        JsonNode raw =  sendSignedRequest(Method.GET, "/api/v3/allOrders", params);
        for (JsonNode node : raw){
            if (node.get("clientOrderId").asText().equals(nameOrder)) {
                return new SpotOrder(new BigDecimal(node.get("executedQty").asText()), new BigDecimal(node.get("cummulativeQuoteQty").asText()));
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
            String[] split = payload.toString().split("\"");
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

    public @NotNull String uGetWWS(){
        return BASE_WWS_FUTURE_USERDATA.formatted(listenKey);
    }

    public @NotNull @Unmodifiable Map<String, BigDecimal> fGetBalance() {
        JsonNode raw = sendSignedRequest(fGetHttps(), Method.GET, "/fapi/v3/balance");
        Map<String, BigDecimal> result = new HashMap<>();
        for (JsonNode node : raw){
            result.put(node.get("asset").asText(), new BigDecimal(node.get("maxWithdrawAmount").asText()));
        }
        return result;
    }

    public @NotNull @Unmodifiable Map<String, BigDecimal> fGetBalanceTotal() {
        JsonNode raw = sendSignedRequest(fGetHttps(), Method.GET, "/fapi/v3/balance");
        Map<String, BigDecimal> result = new HashMap<>();
        for (JsonNode node : raw){
            result.put(node.get("asset").asText(), new BigDecimal(node.get("balance").asText()));
        }
        return result;
    }

    public @NotNull @Unmodifiable Map<String, BigDecimal> fGetUnPNL() {
        JsonNode raw = sendSignedRequest(fGetHttps(), Method.GET, "/fapi/v3/balance");
        Map<String, BigDecimal> result = new HashMap<>();
        for (JsonNode node : raw){
            result.put(node.get("asset").asText(), new BigDecimal(node.get("crossUnPnl").asText()));
        }
        return result;
    }


    public void fSendOrderToMkt(@NotNull String symbol, @NotNull SideOrder sideOrder, BigDecimal amountBase, @Nullable String nameOrder, boolean reduceOnly) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        params.put("side", sideOrder);
        params.put("type", "MARKET");
        params.put("quantity", fGetAllSymbols().get(symbol).roundTickSize(amountBase));
        params.put("newOrderRespType", "RESULT");
        params.put("reduceOnly", reduceOnly);
        if (nameOrder != null) params.put("newClientOrderId", nameOrder);
        sendSignedRequest(fGetHttps(), Method.POST, "/fapi/v1/order", params);
    }

    public void fSendOrderToLimit(@NotNull String symbol, @NotNull SideOrder sideOrder, @NotNull BigDecimal amountBase, @Nullable String nameOrder, @NotNull BigDecimal price, boolean reduceOnly) throws PostOnlyRejectException {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        params.put("side", sideOrder);
        params.put("type", "LIMIT");
        params.put("quantity", fGetAllSymbols().get(symbol).roundTickSize(amountBase));
        params.put("newOrderRespType", "RESULT");
        params.put("reduceOnly", reduceOnly);
        params.put("timeInForce", "GTX");
        params.put("price", price);
        if (nameOrder != null) params.put("newClientOrderId", nameOrder);
        sendSignedRequest(fGetHttps(), Method.POST, "/fapi/v1/order", params);
    }

    public void fCancelOrderAll(@NotNull String symbol) {
        HashMap<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        sendSignedRequest(fGetHttps(), Method.DELETE, "/fapi/v1/allOpenOrders", params);
    }

    public void fCancelOrder(@NotNull String symbol, @NotNull String nameOrder) throws UnknownOrderException {
        HashMap<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        params.put("origClientOrderId", nameOrder);
        sendSignedRequest(fGetHttps(), Method.DELETE, "/fapi/v1/order", params);
    }

    public @Nullable BinanceConnector.FuturePosition fGetPosition(@NotNull String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        JsonNode raw = sendSignedRequest(fGetHttps(), Method.GET, "/fapi/v3/positionRisk", params);
        for (JsonNode node : raw) {
            if (node.get("symbol").asText().equals(symbol.toUpperCase(Locale.US))) {
                return new FuturePosition(new BigDecimal(node.get("positionAmt").asText()));
            }
        }
        return null;
    }

    private final ConcurrentHashMap<String, Symbol> fCachedSymbols = new ConcurrentHashMap<>();

    @SuppressWarnings("DuplicatedCode")
    public @NotNull @Unmodifiable Map<String, Symbol> fGetAllSymbols() {
        if (!fCachedSymbols.isEmpty()){
            return Map.copyOf(fCachedSymbols);
        }
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
        return Map.copyOf(symbols);
    }

    public @NotNull List<FutureOrder> fGetAllOrder(@NotNull String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        JsonNode raw = sendSignedRequest(fGetHttps(), Method.GET, "/fapi/v1/allOrders", params);
        List<FutureOrder> orders = new ArrayList<>();
        for (JsonNode node : raw) {
            orders.add(new FutureOrder(
                    node.get("clientOrderId").asText(),
                    new BigDecimal(node.get("price").asText()),
                    new BigDecimal(node.get("origQty").asText()),
                    SideOrder.valueOf(node.get("side").asText()),
                    switch (node.get("status").asText()){
                        case "NEW" -> StatusOrder.NEW;
                        case "FILLED" -> StatusOrder.FILLED;
                        case "CANCELED" -> StatusOrder.CANCELED;
                        default -> StatusOrder.OTHER;
                    },
                    node.get("reduceOnly").asBoolean(),
                    node.get("time").asLong(),
                    node.get("updateTime").asLong()
            ));
        }
        return orders;
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

    public @NotNull Map<String, FundingRate> fGetFundingRate(){
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

    public @NotNull Map<String, BigDecimal> fGetAllFundingRateHistory(){
        var mapAllSymbols = fGetAllSymbols();
        HashMap<String, BigDecimal> resultAllSymbols = new HashMap<>();
        HashMap<String, Object> params = new HashMap<>();
        params.put("limit", mapAllSymbols.size());
        JsonNode raw = sendPublicRequest(fGetHttps(), Method.GET, "/fapi/v1/fundingRate", params);
        for (JsonNode node : raw){
            resultAllSymbols.put(node.get("symbol").asText(), new BigDecimal(node.get("fundingRate").asText()));
        }
        return resultAllSymbols;
    }

    public @NotNull Map<String, BigDecimal> fGetAllFundingRateCurrent(){
        HashMap<String, BigDecimal> resultAllSymbols = new HashMap<>();
        JsonNode raw = sendPublicRequest(fGetHttps(), Method.GET, "/fapi/v1/premiumIndex");
        for (JsonNode node : raw){
            resultAllSymbols.put(node.get("symbol").asText(), new BigDecimal(node.get("lastFundingRate").asText()));
        }
        return resultAllSymbols;
    }

    public void fStartUserData(){
        listenKey = sendSignedRequest(fGetHttps(), Method.POST, "/fapi/v1/listenKey").get("listenKey").asText();
        executor.execute(() -> {
            while (listenKey != null) {
                fKeepLiveUserData();
                LockSupport.parkNanos(TimeUnit.MINUTES.toNanos(20));
            }
        });
    }

    public void fKeepLiveUserData(){
        sendSignedRequest(fGetHttps(), Method.PUT, "/fapi/v1/listenKey");
    }

    public void fCloseUserData(){
        this.listenKey = null;
        sendSignedRequest(fGetHttps(), Method.DELETE, "/fapi/v1/listenKey");
    }

    public void wfCreateBookTicker(@NotNull Consumer<BookTick> consumer, @NotNull String symbol) {
        UUID uuid = UUID.randomUUID();
        String stream = symbol.toLowerCase(Locale.US) + "@bookTicker";
        addConsumerStreams(fGetWWS() + "@" + stream, (payload) -> {
            String[] split = payload.toString().split("\"");
            consumer.accept(new BookTick(new BigDecimal(split[17]), new BigDecimal(split[21]), new BigDecimal(split[25]), new BigDecimal(split[29])));
        });

        sendWebSocket(fGetWWS(), """
                {"method":"SUBSCRIBE","params":["%s"],"id":"%s"}
                """.formatted(stream, uuid.toString().replace("-", "")));
    }

    public void wfRemoveBookTicker(@NotNull String symbol) {
        UUID uuid = UUID.randomUUID();
        String stream = symbol.toLowerCase(Locale.US) + "@bookTicker";
        removeConsumerStreams(fGetWWS() + "@" + stream);
        sendWebSocket(fGetWWS(), """
                {"method":"UNSUBSCRIBE","params":["%s"],"id":"%s"}
                """.formatted(stream, uuid.toString().replace("-", "")));
    }

    public void uEventOrderTradeUpdate(Consumer<JsonNode> consumer){
        addConsumerStreams(uGetWWS(), (payload) -> {
            if (payload.get("e").asText().equals("ORDER_TRADE_UPDATE")) {
                consumer.accept(payload);
            }
        });
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

    public void cConvert(@NotNull String fromAsset, @NotNull String toAsset, BigDecimal amount, boolean fromAmount) {
        Map<String, Object> paramsRequest = new HashMap<>();
        paramsRequest.put("fromAsset", fromAsset);
        paramsRequest.put("toAsset", toAsset);
        paramsRequest.put("walletType", "SPOT");
        if (fromAmount) {
            paramsRequest.put("fromAmount", amount);
        }else {
            paramsRequest.put("toAmount", amount);
        }
        String id = sendSignedRequest(Method.POST, "/sapi/v1/convert/getQuote", paramsRequest).get("quoteId").asText();
        Map<String, Object> paramsAccept = new HashMap<>();
        paramsAccept.put("quoteId", id);
        sendSignedRequest(Method.POST, "/sapi/v1/convert/acceptQuote", paramsAccept);
    }

    public BigDecimal mGetInterest(@NotNull String asset) {
        Map<String, Object> params = new HashMap<>();
        params.put("assets", asset.toUpperCase(Locale.US));
        params.put("isIsolated", true);
        JsonNode raw = sendSignedRequest(Method.GET, "/sapi/v1/margin/next-hourly-interest-rate", params);
        for (JsonNode node : raw) {
            if (node.get("asset").asText().equals(asset.toUpperCase(Locale.US))) {
                return new BigDecimal(node.get("nextHourlyInterestRate").asText());
            }
        }
        return BigDecimal.ZERO;
    }

    public void mSetEnableInsolated(@NotNull String symbol, boolean enable) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        if (enable) {
            sendSignedRequest(Method.POST, "/sapi/v1/margin/isolated/account", params);
        } else {
            sendSignedRequest(Method.DELETE, "/sapi/v1/margin/isolated/account", params);
        }
    }

    public boolean mIsEnableInsolated(@NotNull String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbols", symbol.toUpperCase(Locale.US));
        JsonNode raw = sendSignedRequest(Method.GET, "/sapi/v1/margin/isolated/account", params);
        for (JsonNode node : raw.get("assets")) {
            if (node.get("symbol").asText().equals(symbol.toUpperCase(Locale.US))) {
                return node.get("enabled").asBoolean();
            }
        }
        return false;
    }

    public boolean mIsAllowInsolated(@NotNull String symbol) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbols", symbol.toUpperCase(Locale.US));
        JsonNode raw = sendSignedRequest(Method.GET, "/sapi/v1/margin/isolated/account", params);
        for (JsonNode node : raw.get("assets")) {
            if (node.get("symbol").asText().equals(symbol.toUpperCase(Locale.US))) {
                return node.get("tradeEnabled").asBoolean() && node.get("isolatedCreated").asBoolean();
            }
        }
        return false;
    }

    public double mGetMaxBorrowable(@Nullable String symbol, @NotNull String asset) {
        Map<String, Object> params = new HashMap<>();
        if (symbol != null) params.put("isolatedSymbol", symbol.toUpperCase(Locale.US));
        params.put("asset", asset.toUpperCase(Locale.US));

        try {
            JsonNode node = sendSignedRequest(Method.GET, "/sapi/v1/margin/maxBorrowable", params);
            double amount =  node.get("amount").asDouble();
            double limit = node.get("borrowLimit").asDouble();
            return Math.min(limit, amount);
        } catch (BinanceApiException e) {
            if (e.getCode() == -3045){
                return -1d;
            }else{
                throw e;
            }
        }
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
        params.put("symbols", symbol.toUpperCase(Locale.US));
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

    public void mSendOrderToMkt(@NotNull String symbol, @NotNull SideOrder sideOrder, BigDecimal amount, @NotNull String nameOrder, boolean amountInBaseAsset) {
        Map<String, Object> params = new HashMap<>();
        params.put("symbol", symbol.toUpperCase(Locale.US));
        params.put("side", sideOrder);
        params.put("newOrderRespType", "RESULT");
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

        sendSignedRequest(
                Method.POST,
                "/sapi/v1/margin/borrow-repay",
                params
        );
    }

    public record Convert(double fromMin, double fromMax, double toMin, double toMax) {}

    public record FuturePosition(BigDecimal quantity) {}

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

    public record SpotOrder(@NotNull BigDecimal baseAmount, @NotNull BigDecimal quoteAmount) {}

    public record FutureOrder(String nameOrder, BigDecimal price, BigDecimal amountBaseAsset, SideOrder sideOrder, StatusOrder statusOrder, boolean reduceOnly, long dateCreate, long dateFilled) {

    }

}
