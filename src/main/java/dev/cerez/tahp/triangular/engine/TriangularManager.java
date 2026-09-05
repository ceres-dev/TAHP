package dev.cerez.tahp.triangular.engine;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.connector.Connector;
import dev.cerez.tahp.connector.model.AssetRate;
import dev.cerez.tahp.connector.model.BookTickDouble;
import dev.cerez.tahp.connector.model.Symbol;
import dev.cerez.tahp.connector.model.Volume24H;
import dev.cerez.tahp.discord.StatusProfiler;
import dev.cerez.tahp.utils.Switch;
import dev.cerez.tahp.triangular.utils.TriangularArbitrageOpportunity;
import dev.cerez.tahp.utils.Telemetry;
import lombok.Builder;
import lombok.Data;
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
public class TriangularManager implements Switch, StatusProfiler {

    private final Connector exchangeApi;
    private final ManagerConfig config;
    private final Consumer<SearchTriangularEngine.OnOpportunities> onUpdate;

    private volatile boolean started = false;

    @Nullable private SearchTriangularEngine engine = null;
    @Nullable private Telemetry telemetry;

    @Nullable private Map<String, Symbol> allSymbolsMap = null;
    @Nullable private Consumer<BookTickDouble> streamListener = null;

    @Contract(value = "_ -> this")
    public TriangularManager setEngine(@NotNull SearchTriangularEngine engine) {
        this.engine = engine;
        return this;
    }

    @Contract(value = "_ -> this")
    public TriangularManager setTelemetry(@NotNull Telemetry telemetry) {
        this.telemetry = telemetry;
        return this;
    }

    @Blocking
    public void start() {
        if (started) {
            return;
        }
        started = true;
        if (engine == null) throw new IllegalStateException("Engine is not setting");
        CompletableFuture<Map<String, Symbol>> allSymbolsMapFuture = CompletableFuture.supplyAsync(
                exchangeApi::sGetAllSymbols
        );
        CompletableFuture<Map<String, BookTickDouble>> tickersFuture = CompletableFuture.supplyAsync(
                exchangeApi::sGetAllBooks
        );
        try {
            Log.info("Send Request...");
            allSymbolsMap = allSymbolsMapFuture.get();
            Map<String, BookTickDouble> tickersMap = tickersFuture.get();
            if (allSymbolsMap == null) {
                Log.error("No exchange info found");
                return;
            }

            Map<String, Volume24H> volume24H = exchangeApi.sGetVolume24H();
            Set<String> symbolsToSubscribe = getSpotTradingSymbols(allSymbolsMap, tickersMap, volume24H);
            Log.info("<green>Request Received: %s Total Symbols.", allSymbolsMap.size());
            Log.info("Starting engine...");
            engine.configure(allSymbolsMap, tickersMap);
            Log.info("<green>Engine Ready: %s.", engine.getClass().getName());
            Log.info("Starting Api...");
            exchangeApi.setConsumerBookTicker(streamListener = this::onBookTickerUpdate);
            exchangeApi.subscribeBookTicker(symbolsToSubscribe);
            exchangeApi.start();
            Log.info("<green>Connector Running: %s", exchangeApi.getClass().getName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.exception("Error iniciando stream de arbitraje", e);
            stop();
        } catch (ExecutionException e) {
            Log.exception("Error al hacer solicitud a exchange", e);
            stop();
        } catch (Exception e) {
            Log.exception("Error suscribiendo streams de bookTicker", e);
            stop();
        }
    }

    public void stop() {
        started = false;
        Consumer<BookTickDouble> listener = streamListener;
        if (listener != null) {
            exchangeApi.unsubscribeBookTicker(listener);
        }
        exchangeApi.stop();
        streamListener = null;
        allSymbolsMap = null;
    }

    private void onBookTickerUpdate(@NotNull BookTickDouble updatedTicker) {
        if (!started) {
            return;
        }
        try {
            long currentNanoTime = System.nanoTime();

            List<TriangularArbitrageOpportunity> list = Objects.requireNonNull(engine, "Engine no asignado")
                    .computeTriangularArbitrageOpportunities(
                            // Si es nulo se hará una analizáis total al grafo
                            updatedTicker
                    );
            onUpdate.accept(new SearchTriangularEngine.OnOpportunities(list, currentNanoTime));
            if (telemetry != null) {
                telemetry.addDeltaDelayComputeNanoTime(System.nanoTime() - currentNanoTime);
                telemetry.incrementUpdateCounter();
                telemetry.addOpportunities(list);
            }
        } catch (Exception e) {
            stop();
            Log.exception("Error calculando arbitrajes triangulares", e);
        }
    }

    private @NotNull Set<String> getSpotTradingSymbols(@NotNull Map<String, Symbol> allSymbols,
                                                       @NotNull Map<String, BookTickDouble> liveTickers,
                                                       @NotNull Map<String, Volume24H> bookTicker24H) {
        Map<String, List<AssetRate>> conversionGraph;
        conversionGraph = buildAssetConversionGraph(allSymbols, liveTickers);
        List<SymbolVolume> candidates = new ArrayList<>();

        for (Symbol symbol : allSymbols.values()) {
            if (!symbol.getIsAllowTrading()) {
                continue;
            }
            if (config.getBanAssets().contains(symbol.getBaseAsset()) || config.getBanAssets().contains(symbol.getQuoteAsset())) {
                continue;
            }

            Volume24H volume24H = bookTicker24H.get(symbol.name());
            if (volume24H == null) {
                continue;
            }

            double quoteVolume = volume24H.quoteVolumen() == null ? 0.0 : volume24H.quoteVolumen();
            double baseVolume = volume24H.baseVolumen() == null ? 0.0 : volume24H.baseVolumen();
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
        int limit = Math.min(config.getMaxSymbols(), candidates.size());
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

    private @NotNull Map<String, List<AssetRate>> buildAssetConversionGraph(@NotNull Map<String, Symbol> exchangeInfo,
                                                                            @NotNull Map<String, BookTickDouble> liveTickers) {
        Map<String, List<AssetRate>> graph = new HashMap<>();
        for (Symbol symbol : exchangeInfo.values()) {
            BookTickDouble ticker = liveTickers.get(symbol.name());
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

    @Override
    public @NotNull StatusProfiler.PresenceProfile getPresenceProfile() {
        return null;
    }

    protected record SymbolVolume(
            String symbol,
            double volumeUsdt
    ) {}

    @Data
    @Builder
    public static final class ManagerConfig {
        @Builder.Default public int maxSymbols = 1500;
        @Builder.Default public Set<String> banAssets = Set.of();
    }
}
