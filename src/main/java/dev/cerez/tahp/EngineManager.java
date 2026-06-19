package dev.cerez.tahp;

import dev.cerez.tahp.connector.Connector;
import dev.cerez.tahp.connector.model.*;
import dev.cerez.tahp.engine.SearchTriangularEngine;
import dev.cerez.tahp.model.*;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

@RequiredArgsConstructor
public class EngineManager implements Switch {

    public static final int MAX_SYMBOL = SearchTriangularEngine.MAX_SYMBOLS;
    public static final boolean IS_TESTNET = true;

    private final Connector exchangeApi;
    private final Consumer<SearchTriangularEngine.OnOpportunities> onUpdate;

    private volatile boolean started = false;

    @Nullable private SearchTriangularEngine engine = null;

    @Nullable private ExchangeInfo exchangeInfoSpot = null;
    @Nullable private Consumer<BookTicker> streamListener = null;

    @Contract(value = "_ -> this")
    public EngineManager setEngine(@NotNull SearchTriangularEngine engine) {
        this.engine = engine;
        return this;
    }

    @Blocking
    public void start() {
        if (started) {
            return;
        }
        started = true;
        if (engine == null) throw new IllegalStateException("Engine is not setting");

        CompletableFuture<ExchangeInfo> exchangeInfoFuture = CompletableFuture.supplyAsync(
                exchangeApi::getExchangeInfo
        );
        CompletableFuture<Map<String, BookTicker>> tickersFuture = CompletableFuture.supplyAsync(
                exchangeApi::getBookTickers
        );

        try {
            Log.info("Starting engine...");
            exchangeInfoSpot = exchangeInfoFuture.get();
            Map<String, BookTicker> tickers = tickersFuture.get();
            if (exchangeInfoSpot == null) {
                Log.error("No exchange info found");
                return;
            }

            HashMap<String, Ticker24H> bookTicker24H = new HashMap<>();
            for (Ticker24H ticker : exchangeApi.getTicker24H()) {
                bookTicker24H.put(ticker.symbol(), ticker);
            }

            Set<String> symbolsToSubscribe = getSpotTradingSymbols(exchangeInfoSpot, tickers, bookTicker24H);
            engine.configure(exchangeInfoSpot);
            Log.info("Starting Api...");
            exchangeApi.setConsumerBookTicker(streamListener = this::onBookTickerUpdate);
            exchangeApi.subscribeBookTicker(symbolsToSubscribe);
            exchangeApi.start();
//                onBookTickerUpdate(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            Log.exception("Error iniciando stream de arbitraje", e);
        } catch (ExecutionException e) {
            stop();
            Log.exception("Error al hacer solicitud a binance", e);
        } catch (Exception e) {
            stop();
            Log.exception("Error suscribiendo streams de bookTicker", e);
        }
    }

    public void stop() {
        started = false;
        Consumer<BookTicker> listener = streamListener;
        if (listener != null) {
            exchangeApi.unsubscribeBookTicker(listener);
        }
        exchangeApi.stop();
        streamListener = null;
        exchangeInfoSpot = null;
    }

    private void onBookTickerUpdate(@NotNull BookTicker updatedTicker) {
        if (!started) {
            return;
        }
        try {
            long currentNano = System.nanoTime();

            List<TriangularArbitrageOpportunity> list = Objects.requireNonNull(engine, "Engine no asignado")
                    .computeTriangularArbitrageOpportunities(
                            // Si es nulo se hará una analizáis total al grafo
                            updatedTicker
                    );
            onUpdate.accept(new SearchTriangularEngine.OnOpportunities(list, currentNano));
        } catch (Exception e) {
            stop();
            Log.exception("Error calculando arbitrajes triangulares", e);
        }
    }

    private @NotNull Set<String> getSpotTradingSymbols(@NotNull ExchangeInfo exchangeInfo,
                                                       @NotNull Map<String, BookTicker> liveTickers,
                                                       @NotNull Map<String, Ticker24H> bookTicker24H) {
        Map<String, List<AssetRate>> conversionGraph;
        conversionGraph = buildAssetConversionGraph(exchangeInfo, liveTickers);
        List<SymbolVolume> candidates = new ArrayList<>();

        for (Symbol symbol : exchangeInfo.symbols().values()) {
            if (!symbol.getIsSpot()) continue;
            if (!MarketStatus.TRADING.equals(symbol.getMarketStatus())) continue;
            if (!symbol.getIsAllowTrading()) continue;
            if (!IS_TESTNET) if (!symbol.getPermissions().contains("TRD_GRP_074")) continue;




            Ticker24H ticker24H = bookTicker24H.get(symbol.name());
            if (ticker24H == null) continue;

            double quoteVolume = ticker24H.quoteVolumen() == null ? 0.0 : ticker24H.quoteVolumen();
            double baseVolume = ticker24H.baseVolumen() == null ? 0.0 : ticker24H.baseVolumen();
            double volumeUsdt = 0.0;

            if (quoteVolume > 0.0) {
                volumeUsdt = convertAssetAmountToUsdt(symbol.getQuoteAsset(), quoteVolume, conversionGraph);
            }
            if (volumeUsdt <= 0.0 && baseVolume > 0.0) {
                volumeUsdt = convertAssetAmountToUsdt(symbol.getBaseAsset(), baseVolume, conversionGraph);
            }

            candidates.add(new SymbolVolume(symbol.name(), volumeUsdt));
        }

        candidates.sort((a, b) -> Double.compare(b.volumeUsdt(), a.volumeUsdt()));
        int limit = Math.min(MAX_SYMBOL, candidates.size());
        Set<String> result = new HashSet<>(limit);
        for (int i = 0; i < limit; i++) {
            result.add(candidates.get(i).symbol());
        }

        return result;
    }

    private double convertAssetAmountToUsdt(
            @NotNull String asset,
            double amount,
            @NotNull Map<String, List<AssetRate>> conversionGraph
    ) {
        if (amount <= 0.0) return 0.0;
        if ("USDT".equalsIgnoreCase(asset)) return amount;

        record Node(String asset, double amount) {}

        Deque<Node> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new Node(asset, amount));
        visited.add(asset);

        while (!queue.isEmpty()) {
            Node current = queue.poll();
            List<AssetRate> rates = conversionGraph.get(current.asset());
            if (rates == null) continue;

            for (AssetRate rate : rates) {
                double convertedAmount = current.amount() * rate.rate();
                if (convertedAmount <= 0.0) continue;

                if ("USDT".equalsIgnoreCase(rate.toAsset())) {
                    return convertedAmount;
                }
                if (visited.add(rate.toAsset())) {
                    queue.add(new Node(rate.toAsset(), convertedAmount));
                }
            }
        }

        return 0.0;
    }

    private @NotNull Map<String, List<AssetRate>> buildAssetConversionGraph(@NotNull ExchangeInfo exchangeInfo,
                                                                            @NotNull Map<String, BookTicker> liveTickers) {
        Map<String, List<AssetRate>> graph = new HashMap<>();
        for (Symbol symbol : exchangeInfo.symbols().values()) {
            if (!symbol.getIsSpot()) continue;
            if (!MarketStatus.TRADING.equals(symbol.getMarketStatus())) continue;

            BookTicker ticker = liveTickers.get(symbol.name());
            if (ticker == null) continue;

            double bid = ticker.bidPrice();
            double ask = ticker.askPrice();
            if (bid <= 0.0 || ask <= 0.0) continue;

            double midPrice = (bid + ask) / 2.0;
            if (midPrice <= 0.0) continue;

            String base = symbol.getBaseAsset();
            String quote = symbol.getQuoteAsset();
            graph.computeIfAbsent(base, k -> new ArrayList<>()).add(new AssetRate(quote, midPrice));
            graph.computeIfAbsent(quote, k -> new ArrayList<>()).add(new AssetRate(base, 1.0 / midPrice));
        }
        return graph;
    }

    protected record SymbolVolume(
            String symbol,
            double volumeUsdt
    ) {}
}
