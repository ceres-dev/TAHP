package dev.cerez.tahp.connector.connectors;

import com.binance.connector.client.common.configuration.SignatureConfiguration;
import com.binance.connector.client.common.websocket.adapter.stream.StreamConnectionWrapper;
import com.binance.connector.client.common.websocket.configuration.WebSocketClientConfiguration;
import com.binance.connector.client.common.websocket.dtos.BaseDTO;
import com.binance.connector.client.common.websocket.dtos.RequestWrapperDTO;
import com.binance.connector.client.common.websocket.service.StreamBlockingQueue;
import com.binance.connector.client.spot.websocket.api.SpotWebSocketApiUtil;
import com.binance.connector.client.spot.websocket.api.api.SpotWebSocketApi;
import com.binance.connector.client.spot.websocket.api.model.*;
import com.binance.connector.client.spot.websocket.stream.SpotWebSocketStreamsUtil;
import com.binance.connector.client.spot.websocket.stream.api.SpotWebSocketStreams;
import com.binance.connector.client.spot.websocket.stream.model.BookTickerResponse;
import dev.cerez.tahp.Log;
import dev.cerez.tahp.connector.*;
import dev.cerez.tahp.connector.model.*;
import dev.cerez.tahp.io.IOdata;
import dev.cerez.tahp.model.Action;
import dev.cerez.tahp.model.MarketStatus;
import lombok.Setter;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

public final class BinanceConnector extends BaseConnector implements AutoCloseable {

    private static final String BASE_HTTPS = "https://api.binance.com";
    private static final String BASE_WWS = "wss://ws-api-spot.kucoin.com";

    private final SpotWebSocketApi api;
    private final SpotWebSocketStreams streams;
    private final StreamConnectionWrapper streamConnection;
    private final List<StreamBlockingQueue<String>> bookTickerQueues = new CopyOnWriteArrayList<>();
    private final Executor streamExecutor;
    private final boolean isTestNet;

    private volatile boolean bookTickerReaderRunning = false;
    @Nullable private volatile ExchangeInfo exchangeInfoSpot = null;

    public BinanceConnector(boolean isTestNet) {
        this.streamExecutor = Executors.newThreadPerTaskExecutor(new FactoryThreadWebSocket());
        this.isTestNet = isTestNet;

        WebSocketClientConfiguration apiConfiguration = SpotWebSocketApiUtil.getClientConfiguration();
        if (isTestNet) {
            apiConfiguration.setUrl(Endpoints.API_WSS_TEST.getEndpoint());
        }else apiConfiguration.setUrl(Endpoints.API_WSS.getEndpoint());

        IOdata.ApiKeysBinance apiKeys = IOdata.loadApiKeysBinance();
        SignatureConfiguration signatureConfiguration = new SignatureConfiguration();
        signatureConfiguration.setApiKey(apiKeys.key());
        signatureConfiguration.setSecretKey(apiKeys.secret());
        apiConfiguration.setSignatureConfiguration(signatureConfiguration);
        apiConfiguration.setMessageMaxSize((long) (Integer.MAX_VALUE));

        try {
            this.api = new SpotWebSocketApi(apiConfiguration);
        }catch (com.binance.connector.client.common.ApiException e){
            throw new  RuntimeException(e);
        }

        WebSocketClientConfiguration streamConfiguration = SpotWebSocketStreamsUtil.getClientConfiguration();
        streamConfiguration.setUsePool(true);
        streamConfiguration.setPoolSize(4);
        if (isTestNet) streamConfiguration.setUrl(Endpoints.STREAM_WSS_TEST.getEndpoint());
        else streamConfiguration.setUrl(Endpoints.STREAM_WSS.getEndpoint());
        this.streamConnection = new StreamConnectionWrapper(
                streamConfiguration
        ){
            private volatile int hashCodeLast = 0;

            @Override
            public void innerSend(RequestWrapperDTO requestWrapperDTO) {
                int hashCode = requestWrapperDTO.hashCode();
                if (hashCode != hashCodeLast) {
                    hashCodeLast = hashCode;
                    send(requestWrapperDTO);
                }
            }
        };
        this.streams = new SpotWebSocketStreams(streamConnection);
    }

    @SneakyThrows
    public void checkApikey() {
        checkResult(
                api.accountStatus(new AccountStatusRequest().omitZeroBalances(true).recvWindow(20_000.0)).get(TIMEOUT, TimeUnit.SECONDS)
        );
    }

    public void invalidedCache() {
        exchangeInfoSpot = null;
    }

    @Override
    protected URL getURL() {
        return new URL(BASE_WWS,  BASE_HTTPS);
    }

    @Override
    protected void sendPing() {
        throw new UnsupportedOperationException();
    }

    @Override
    @SneakyThrows
    @SuppressWarnings("DataFlowIssue")
    public @NotNull ExchangeInfo getExchangeInfo() {
        ExchangeInfo cached = exchangeInfoSpot;
        if (cached != null) {
            return cached;
        }

        var response = api.exchangeInfo(new ExchangeInfoRequest().showPermissionSets(true)).get(TIMEOUT, TimeUnit.SECONDS);
        HashMap<String, Symbol> symbols = new HashMap<>();
        checkResult(response);
        for (ExchangeInfoResponseResultSymbolsInner symbol : response.getResult().getSymbols()) {
            var s = toSymbolConfigurable(symbol);
            if (!isTestNet) if (!s.getPermissions().contains("TRD_GRP_074")) continue;
            symbols.put(symbol.getSymbol(), s);
        }
        exchangeInfoSpot = new ExchangeInfo(List.of(), symbols);
        return exchangeInfoSpot;
    }

    @Override
    @SneakyThrows
    @SuppressWarnings("DataFlowIssue")
    public @NotNull Map<String, BookTicker> getBookTickers() {
        Map<String, BookTicker> result = new HashMap<>();
        Object actual = api.tickerBook(new TickerBookRequest()).get(30, TimeUnit.SECONDS).getActualInstance();
        if (actual instanceof com.binance.connector.client.spot.websocket.api.model.TickerBookResponse2 response) {
            for (TickerBookResponse1Result ticker : response.getResult()) {
                result.put(ticker.getSymbol(), toBookTicker(ticker));
            }
        } else if (actual instanceof com.binance.connector.client.spot.websocket.api.model.TickerBookResponse1 response) {
            TickerBookResponse1Result ticker = response.getResult();
            result.put(ticker.getSymbol(), toBookTicker(ticker));
        }
        return result;
    }

    @SuppressWarnings("DataFlowIssue")
    @Override
    @SneakyThrows
    public @NotNull Map<String, Volume24H> getVolume24H() {
        Map<String, Volume24H> result = new HashMap<>();
        Object actual = api.ticker24hr(new Ticker24hrRequest()).get(30, TimeUnit.SECONDS).getActualInstance();

        if (actual instanceof com.binance.connector.client.spot.websocket.api.model.Ticker24hrResponse2 response) {
            for (Ticker24hrResponse2ResultInner ticker : response.getResult()) {
                var v = toTicker24H(ticker);
                result.put(v.symbol(), v);
            }
        } else if (actual instanceof com.binance.connector.client.spot.websocket.api.model.Ticker24hrResponse1 response) {
            var v = toTicker24H(response.getResult());
            result.put(v.symbol(), v);
        }
        return result;
    }

    @Override
    @SneakyThrows
    @SuppressWarnings("DataFlowIssue")
    public @NotNull HashMap<String, Double> getBalance() {
        HashMap<String, Double> balances = new HashMap<>();
        var response = api.accountStatus(new AccountStatusRequest().recvWindow(20_000.0))
                .get(15, TimeUnit.SECONDS);
        checkResult(response);
        for (AccountStatusResponseResultBalancesInner balance : response.getResult().getBalances()) {
            balances.put(balance.getAsset(), parseDouble(balance.getFree()));
        }
        return balances;
    }

    @Override
    @SneakyThrows
    public @NotNull OrderResult placeMarketOrder(@NotNull Symbol symbol,
                                                 @NotNull Action side,
                                                 @NotNull Double amount,
                                                 @NotNull Boolean useQuantity
    ) {
        OrderPlaceRequest request = new OrderPlaceRequest()
                .symbol(symbol.name())
                .side(side == Action.BUY ? Side.BUY : Side.SELL)
                .type(OrderType.MARKET)
                .newOrderRespType(NewOrderRespType.FULL)
                .recvWindow(20_000.0);
        checkResult(request);
        if (useQuantity) {
            request.quantity(Double.valueOf(symbol.formatQuantity(amount)));
        } else {
            request.quoteOrderQty(Double.valueOf(symbol.formatQuoteOrderQty(amount)));
        }

        OrderPlaceResponse response = api.orderPlace(request).get(30, TimeUnit.SECONDS);
        OrderPlaceResponseResult order = response.getResult();
        if (response.getError() != null) {
            Log.error(response.getError().getMsg() + " -> " + response.getError().getCode());
            throw new ApiException(response.getError().getMsg());
        }
        double executedQty = parseDouble(order.getExecutedQty());
        double cumulativeQuoteQty = parseDouble(order.getCummulativeQuoteQty());
        double receivedQty = side == Action.BUY ? executedQty : cumulativeQuoteQty;
        receivedQty -= getCommissionPaidInReceivedAsset(order, side == Action.BUY ? symbol.getBaseAsset() : symbol.getQuoteAsset());

        return new OrderResult(
                Long.toString(order.getOrderId()),
                executedQty,
                cumulativeQuoteQty,
                Math.max(0.0, receivedQty)
        );
    }

    @Setter
    private Consumer<BookTicker> consumerBookTicker;

    @Override
    public void subscribeBookTicker(@NotNull Collection<String> symbols) {
        if (symbols.isEmpty()) {
            return;
        }

        List<String> streams = new ArrayList<>();
        for (String symbol : symbols) {
            if (symbol == null || symbol.isBlank()) {
                continue;
            }
            streams.add(symbol.toLowerCase(Locale.ROOT) + "@bookTicker");
        }

        for (int i = 0; i < streams.size(); i += MAX_SYMBOLS_PER_SUBSCRIBE) {
            int end = Math.min(i + MAX_SYMBOLS_PER_SUBSCRIBE, streams.size());
            subscribeBookTickerBatch(streams.subList(i, end));
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(SUBSCRIBE_BATCH_DELAY_MS));
        }

    }

    @Override
    public void unsubscribeBookTicker(@NotNull Consumer<BookTicker> listener) {
        for (StreamBlockingQueue<String> queue : bookTickerQueues) {
            streamConnection.unsubscribe(queue);
        }
        bookTickerQueues.clear();
    }

    @Override
    public void start() {
        if (bookTickerReaderRunning) {
            return;
        }
        bookTickerReaderRunning = true;
        ensureBookTickerReader();
    }

    @Override
    public void stop() {
        bookTickerReaderRunning = false;
        super.stop();
    }

    private void subscribeBookTickerBatch(@NotNull List<String> streams) {
        if (streams.isEmpty()) {
            return;
        }
        UUID uuid = UUID.randomUUID();
        RequestWrapperDTO<Set<String>, Object> request = new RequestWrapperDTO.Builder<Set<String>, Object>()
                .id(uuid.toString())
                .method("SUBSCRIBE")
                .params(Set.copyOf(streams))
                .build();
        bookTickerQueues.addAll(streamConnection.subscribe(request).values());
    }

    private void ensureBookTickerReader() {
        for (StreamBlockingQueue<String> queue : bookTickerQueues) {
            streamExecutor.execute(() -> {
                while (bookTickerReaderRunning) {
                    try {
                        String payload = queue.take();
                        try {
                            // 0 : {
                            // 1 : u
                            // 2 : :77098407174,
                            // 3 : s
                            // 4 : :
                            // 5 : ETHUSDT
                            // 6 : ,
                            // 7 : b
                            // 8 : :
                            // 9 : 1586.81000000
                            // 10: ,
                            // 11: B
                            // 12: :
                            // 13: 38.98270000
                            // 14: ,
                            // 15: a
                            // 16: :
                            // 17: 1586.82000000
                            // 18: ,
                            // 19: A
                            // 20: :
                            // 21: 1.12960000
                            String[] split = payload.split("\"");
                            BookTicker bookTicker = new BookTicker(
                                    split[5],
                                    fastParseDouble(split[9]),
                                    fastParseDouble(split[13]),
                                    fastParseDouble(split[17]),
                                    fastParseDouble(split[21])
                            );
                            if (consumerBookTicker != null) this.consumerBookTicker.accept(bookTicker);
                        } catch (Exception e) {
                            Log.exception("Error procesando bookTicker del conector de Binance", e);
                        }
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private @NotNull Symbol toSymbolConfigurable(@NotNull ExchangeInfoResponseResultSymbolsInner symbol) {
        Set<String> permissions = new HashSet<>();
        if (symbol.getPermissions() != null) {
            permissions.addAll(symbol.getPermissions());
        }
        if (symbol.getPermissionSets() != null && !symbol.getPermissionSets().isEmpty()) {
            permissions.addAll(symbol.getPermissionSets().getFirst());
        }

        return new Symbol(
                symbol.getSymbol(),
                Objects.requireNonNullElse(symbol.getQuoteAssetPrecision(), symbol.getQuotePrecision()).intValue(),
                symbol.getQuotePrecision().intValue(),
                MarketStatus.valueOf(symbol.getStatus()),
                symbol.getBaseAsset(),
                symbol.getQuoteAsset(),
                Boolean.TRUE.equals(symbol.getIsSpotTradingAllowed()),
                extractStepSize(symbol),
                permissions
        );
    }

    private @Nullable Double extractStepSize(@NotNull ExchangeInfoResponseResultSymbolsInner symbol) {
        if (symbol.getFilters() == null) {
            return null;
        }
        for (SymbolFilters filter : symbol.getFilters()) {
            Object actual = filter.getActualInstance();
            if (actual instanceof LotSizeFilter lotSizeFilter) {
                return parseDouble(lotSizeFilter.getStepSize());
            }
        }
        return null;
    }

    private @NotNull BookTicker toBookTicker(@NotNull TickerBookResponse1Result ticker) {
        return new BookTicker(
                ticker.getSymbol(),
                parseDouble(ticker.getBidPrice()),
                parseDouble(ticker.getBidQty()),
                parseDouble(ticker.getAskPrice()),
                parseDouble(ticker.getAskQty())
        );
    }

    @SuppressWarnings("DataFlowIssue")
    private @NotNull BookTicker toBookTicker(@NotNull BookTickerResponse ticker) {
        return new BookTicker(
                ticker.getsLowerCase(),
                Double.valueOf(ticker.getbLowerCase()),
                Double.valueOf(ticker.getB()),
                Double.valueOf(ticker.getaLowerCase()),
                Double.valueOf(ticker.getA())
        );
    }

    private @NotNull Volume24H toTicker24H(@NotNull Ticker24hrResponse1Result ticker) {
        return new Volume24H(
                ticker.getSymbol(),
                parseDouble(ticker.getQuoteVolume()),
                parseDouble(ticker.getVolume())
        );
    }

    private @NotNull Volume24H toTicker24H(@NotNull Ticker24hrResponse2ResultInner ticker) {
        return new Volume24H(
                ticker.getSymbol(),
                parseDouble(ticker.getQuoteVolume()),
                parseDouble(ticker.getVolume())
        );
    }

    private double getCommissionPaidInReceivedAsset(@NotNull OrderPlaceResponseResult order, @NotNull String receivedAsset) {
        if (order.getFills() == null) {
            return 0.0;
        }

        double commission = 0.0;
        for (OrderPlaceResponseResultFillsInner fill : order.getFills()) {
            if (receivedAsset.equals(fill.getCommissionAsset())) {
                commission += parseDouble(fill.getCommission());
            }
        }
        return commission;
    }

    private double parseDouble(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        return Double.parseDouble(value);
    }

    @Override
    public void close() {
        bookTickerReaderRunning = false;
        try {
            streams.stop();
        } catch (Exception e) {
            throw new com.binance.connector.client.common.ApiException(e);
        }
        try {
            api.stop();
        } catch (Exception e) {
            throw new com.binance.connector.client.common.ApiException(e);
        }
    }

    public void checkResult(BaseDTO result){
        if (result.getError() != null && result.getError().getCode() != 200) {
            throw new IllegalStateException(result.getError().getMsg());
        }
    }

    @Override
    protected @NotNull String getApiRestURL(@NotNull String baseURL) {
        return BASE_HTTPS;
    }

    @Override
    protected void handleStreamMessage(@NotNull String contentToParse) {

    }
}
