package dev.cerez.tahp.engine.engines;

import dev.cerez.tahp.connector.model.BookTicker;
import dev.cerez.tahp.connector.model.ExchangeInfo;
import dev.cerez.tahp.connector.model.Symbol;
import dev.cerez.tahp.engine.NotConfiguredException;
import dev.cerez.tahp.engine.SearchTriangularEngine;
import dev.cerez.tahp.engine.model.NameAsset;
import dev.cerez.tahp.model.*;
import dev.cerez.tahp.utils.SimulateCycles;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class SearchTriangularEngineJava extends SearchTriangularEngine {

    private final ArbitrageEdge[][] outgoingByFromArray = new ArbitrageEdge[MAX_SYMBOLS][MAX_CYCLE_LENGTH];

    @Override
    @SneakyThrows
    public @NotNull List<TriangularArbitrageOpportunity> computeTriangularArbitrageOpportunities(
            @NotNull BookTicker updatedTicker
    ) {
        if (exchangeInfo == null) throw new NotConfiguredException("engine no fue configurado");
        Map<NameAsset, ArrayList<ArbitrageEdge>> outgoingByFromAsset = updateGraf(exchangeInfo, updatedTicker);
        String updatedSymbol = updatedTicker.symbol();
        Set<NameAsset> trackedAssets = trackedAssetsFromLastTriangular();

        Set<NameAsset> startAssetsToAnalyze;
        final boolean detectTriangularPrev;

        startAssetsToAnalyze = new LinkedHashSet<>(2);
        Symbol symbol = exchangeInfo.symbols().get(updatedSymbol);
        if (symbol == null) {
            return List.of();
        }
        NameAsset baseAssetName = nameAssetCache.computeIfAbsent(symbol.getBaseAsset(), NameAssetIndexed::new);
        NameAsset quoteAssetName = nameAssetCache.computeIfAbsent(symbol.getQuoteAsset(), NameAssetIndexed::new);
        startAssetsToAnalyze.add(baseAssetName);
        startAssetsToAnalyze.add(quoteAssetName);
        if (
                trackedAssets.contains(quoteAssetName) ||
                        trackedAssets.contains(baseAssetName)
        ) {
            detectTriangularPrev = true;
            startAssetsToAnalyze.addAll(trackedAssets);
        }else {
            detectTriangularPrev = false;
        }

        if (outgoingByFromAsset.size() < MIN_CYCLE_LENGTH) {
            return List.of();
        }

        // Descarta símbolos no registrados
        startAssetsToAnalyze.retainAll(outgoingByFromAsset.keySet());
        if (startAssetsToAnalyze.isEmpty()) {
            return List.of();
        }

        HashSet<String> seenCycles = new HashSet<>();
        LinkedList<TriangularArbitrageOpportunity> opportunities = new LinkedList<>();
        Set<NameAsset> startAssets = new LinkedHashSet<>(
                Objects.requireNonNullElseGet(startAssetsToAnalyze, outgoingByFromAsset::keySet)
        );
        startAssets.retainAll(outgoingByFromAsset.keySet());
        if (startAssets.isEmpty()) {
            return List.of();
        }

        for (NameAsset startAsset : startAssets) {
            List<ArbitrageEdge> path = new LinkedList<>();
            List<Integer> visitedAssets = new LinkedList<>();
            visitedAssets.add(startAsset.hashPrimitive);

            searchCyclesFrom(
                    startAsset,
                    startAsset,
                    outgoingByFromArray,
                    path,
                    new IntegerAtomic(0),
                    visitedAssets,
                    seenCycles,
                    opportunities
            );
        }

        Set<String> activeCycleKeys = new HashSet<>();
        for (TriangularArbitrageOpportunity opportunity : opportunities) {
            activeCycleKeys.add(canonicalCycleKey(opportunity.getAssetsCycle()));
        }

        lastTriangular.keySet().removeIf(key -> {
            if (activeCycleKeys.contains(key)) {
                return false;
            }else {
                return detectTriangularPrev;
            }
        });
        opportunities.addAll(lastTriangular.values());
        if (opportunities.isEmpty()) {
            return List.of();
        }
        // No usar Lambdas: por qué llama linkToTargetMethod
        //noinspection Convert2MethodRef
        opportunities.sort(Comparator.comparingDouble((TriangularArbitrageOpportunity t) -> t.getProfitPercent()).reversed());

        return opportunities;
    }

    @Override
    public void buildGraf(@NotNull ExchangeInfo exchangeInfoSpot){
        outgoingByFromAsset.clear();
        this.exchangeInfo = exchangeInfoSpot;

        for (Symbol symbol : exchangeInfoSpot.symbols().values()) {
            if (!MarketStatus.TRADING.equals(symbol.getMarketStatus())) {
                continue;
            }

            String symbolName = symbol.name();
            BookTicker ticker = liveTickers.get(symbolName);
            if (ticker == null) {
                continue;
            }

            double bid =          ticker.bidPrice();
            double ask =          ticker.askPrice();
            double bidLiquidity = ticker.bidQty();
            double askLiquidity = ticker.askQty();

            String baseAsset = symbol.getBaseAsset();
            String quoteAsset = symbol.getQuoteAsset();
            if (baseAsset.equals("?") || quoteAsset.equals("?")) {
                continue;
            }

            double sellRate = bid * (1.0 - DEFAULT_FEE_RATE);
            double buyRate = (1.0 / ask) * (1.0 - DEFAULT_FEE_RATE);

            NameAsset baseAssetName = nameAssetCache.computeIfAbsent(baseAsset, NameAssetIndexed::new);
            NameAsset quoteAssetName = nameAssetCache.computeIfAbsent(quoteAsset, NameAssetIndexed::new);

            if (sellRate > 0.0) {
                addEdge(outgoingByFromAsset, new ArbitrageEdge(
                        symbolName,
                        baseAssetName,
                        quoteAssetName,
                        sellRate,
                        -Math.log(sellRate),
                        Action.SELL,
                        bid,
                        bidLiquidity,
                        symbol.getStepSizeRaw()
                ));
            }

            if (buyRate > 0.0) {
                addEdge(outgoingByFromAsset, new ArbitrageEdge(
                        symbolName,
                        quoteAssetName,
                        baseAssetName,
                        buyRate,
                        -Math.log(buyRate),
                        Action.BUY,
                        ask,
                        askLiquidity,
                        symbol.getStepSizeRaw()
                ));
            }
        }
    }

    @Override
    @NotNull
    protected Map<NameAsset, ArrayList<ArbitrageEdge>> updateGraf(@NotNull ExchangeInfo exchangeInfoSpot, @NotNull BookTicker updatedTicker) {
        liveTickers.put(updatedTicker.symbol(), updatedTicker);

        Symbol symbol = exchangeInfoSpot.symbols().get(updatedTicker.symbol());
        if (symbol == null || !isTradableSpot(symbol)) {
            return outgoingByFromAsset;
        }

        String baseAsset = symbol.getBaseAsset();
        String quoteAsset = symbol.getQuoteAsset();
        if (baseAsset.equals("?") || quoteAsset.equals("?")) {
            return outgoingByFromAsset;
        }

        double bid =          updatedTicker.bidPrice();
        double ask =          updatedTicker.askPrice();
        double bidLiquidity = updatedTicker.bidQty();
        double askLiquidity = updatedTicker.askQty();

        NameAsset baseAssetName = nameAssetCache.computeIfAbsent(baseAsset, NameAssetIndexed::new);
        NameAsset quoteAssetName = nameAssetCache.computeIfAbsent(quoteAsset, NameAssetIndexed::new);

        double sellRate = bid * (1.0 - DEFAULT_FEE_RATE);
        if (sellRate > 0.0) {
            upsertEdge(
                    updatedTicker.symbol(),
                    baseAssetName,
                    quoteAssetName,
                    sellRate,
                    -Math.log(sellRate),
                    Action.SELL,
                    bid,
                    bidLiquidity,
                    symbol.getStepSizeRaw()
            );
        }

        double buyRate = (1.0 / ask) * (1.0 - DEFAULT_FEE_RATE);
        if (buyRate > 0.0) {
            upsertEdge(
                    updatedTicker.symbol(),
                    quoteAssetName,
                    baseAssetName,
                    buyRate,
                    -Math.log(buyRate),
                    Action.BUY,
                    ask,
                    askLiquidity,
                    symbol.getStepSizeRaw()
            );
        }

        return outgoingByFromAsset;
    }

    private void addEdge(@NotNull Map<NameAsset, ArrayList<ArbitrageEdge>> outgoingByFromAsset,
                         @NotNull ArbitrageEdge edge
    ) {
        ArrayList<ArbitrageEdge> outgoing = outgoingByFromAsset.computeIfAbsent(edge.getFromAsset(), key -> {
            ArrayList<ArbitrageEdge> list = new ArrayList<>(3);
            outgoingByFromArray[key.index] = list.toArray(new ArbitrageEdge[0]);
            return list;
        });

        synchronized (outgoing) {
            outgoing.add(edge);
            outgoingByFromArray[edge.getFromAsset().index] = outgoing.toArray(new ArbitrageEdge[0]);
        }
    }

    private boolean isTradableSpot(@NotNull Symbol symbol) {
        return MarketStatus.TRADING.equals(symbol.getMarketStatus());
    }

    private void upsertEdge(
            @NotNull String symbol,
            @NotNull NameAsset fromAsset,
            @NotNull NameAsset toAsset,
            double rate,
            double weight,
            @NotNull Action action,
            double referencePrice,
            double referenceLiquidity,
            double stepSize
    ) {
        ArrayList<ArbitrageEdge> outgoing = outgoingByFromAsset.computeIfAbsent(fromAsset, key -> {
            ArrayList<ArbitrageEdge> list = new ArrayList<>(3);
            outgoingByFromArray[key.index] = list.toArray(new ArbitrageEdge[0]);
            return list;
        });
        synchronized (outgoing) {
            for (ArbitrageEdge edge : outgoing) {
                if (symbol.equals(edge.getSymbol()) && action.equals(edge.getAction())) {
                    synchronized (edge) {
                        edge.setRate(rate);
                        edge.setWeight(weight);
                        edge.setReferencePrice(referencePrice);
                        edge.setReferenceLiquidity(referenceLiquidity);
                    }
                    return;
                }
            }
            ArbitrageEdge edge = new ArbitrageEdge(
                    symbol,
                    fromAsset,
                    toAsset,
                    rate,
                    weight,
                    action,
                    referencePrice,
                    referenceLiquidity,
                    stepSize
            );
            outgoing.add(edge);
            outgoingByFromArray[fromAsset.index] = outgoing.toArray(new ArbitrageEdge[0]);
        }
    }

    private void removeEdgesForSymbol(@NotNull String symbol) {
        for (ArrayList<ArbitrageEdge> outgoing : outgoingByFromAsset.values()) {
            synchronized (outgoing) {
                outgoing.removeIf(edge -> {
                    boolean b = symbol.equals(edge.getSymbol());
                    outgoingByFromArray[edge.getFromAsset().index] = null;
                    return b;
                });
            }
        }
    }

    private void searchCyclesFrom(
            @NotNull NameAsset startAsset,
            @NotNull NameAsset currentAsset,
            @NotNull ArbitrageEdge[][] outgoingByFromAsset,
            @NotNull List<ArbitrageEdge> path,
            @NotNull IntegerAtomic sizePath,
            @NotNull List<Integer> visitedAssets,
            @NotNull HashSet<String> seenCycles,
            @NotNull LinkedList<TriangularArbitrageOpportunity> opportunities
    ) {

        ArbitrageEdge[] outgoing = outgoingByFromAsset[currentAsset.index];
        if (outgoing == null) {
            return;
        }

        int nextLength = sizePath.value + 1;
        for (ArbitrageEdge edge : outgoing) {

            if (edge == null) continue;

            if ((startAsset.hashPrimitive == edge.getToAsset().hashPrimitive)) {

                if (nextLength < MIN_CYCLE_LENGTH || nextLength > MAX_CYCLE_LENGTH) {
                    continue;
                }

                path.addLast(edge);
                TriangularArbitrageOpportunity opportunity = buildOpportunityFromEdges(path);
                path.removeLast();

                if (opportunity == null) {
                    continue;
                }

                String canonicalKey = canonicalCycleKey(opportunity.getAssetsCycle());
                if (seenCycles.add(canonicalKey)) {
                    opportunities.add(opportunity);
                }
                continue;
            }

            if (nextLength >= MAX_CYCLE_LENGTH) {
                continue;
            }

            if (visitedAssets.getFirst().equals(edge.getToAsset().hashObject) ||
                    visitedAssets.getLast().equals(edge.getToAsset().hashObject)
            ) {
                continue;
            }

            path.addLast(edge);
            sizePath.increment();
            visitedAssets.add(edge.getToAsset().hashObject);
            searchCyclesFrom(
                    startAsset,
                    edge.getToAsset(),
                    outgoingByFromAsset,
                    path,
                    sizePath,
                    visitedAssets,
                    seenCycles,
                    opportunities
            );
            visitedAssets.remove(edge.getToAsset().hashObject);
            sizePath.decrement();
            path.removeLast();
        }
    }

    private @Nullable TriangularArbitrageOpportunity buildOpportunityFromEdges(@NotNull List<ArbitrageEdge> cycleEdges) {
        int cycleLength = MAX_CYCLE_LENGTH;

//        if (cycleLength < MIN_CYCLE_LENGTH || cycleLength > MAX_CYCLE_LENGTH) {
//            return null;
//        }
//        List<ArbitrageEdge> cycleEdgesRotate = rotateCycleToPreferredStart(cycleEdges);
        ArbitrageEdge first = cycleEdges.getFirst();
        NameAsset startAsset = first.getFromAsset();
        NameAsset currentAsset = startAsset;

        List<NameAsset> cycleAssets = new LinkedList<>();
        cycleAssets.add(startAsset);

        LinkedList<NameAsset> distinctAssets = new LinkedList<>();
        distinctAssets.add(startAsset);

        int i = 0;
        for (ArbitrageEdge edge : cycleEdges) {
            if (!(currentAsset.hashPrimitive == edge.getFromAsset().hashPrimitive)) {
                return null;
            }

            currentAsset = edge.getToAsset();
            cycleAssets.add(currentAsset);

            if (i < cycleLength - 1 && !distinctAssets.add(currentAsset)) {
                return null;
            }
            i++;
        }

        if (!(startAsset.hashPrimitive == currentAsset.hashPrimitive)) {
            return null;
        }
        if (distinctAssets.size() != cycleLength) {
            return null;
        }

        SimulateCycles.SimulationResult simulationResult = SimulateCycles.simulateCycleWithStepSize(cycleEdges);
        if (simulationResult == null) {
            return null;
        }

        double rateProduct = simulationResult.rateProduct();
        double totalWeight = -Math.log(rateProduct);
        if (rateProduct <= 1.0 + PROFIT_EPSILON) {
            return null;
        }
        if (totalWeight >= -PROFIT_EPSILON) {
            return null;
        }
        List<String> cycleStringAssets = cycleAssets.stream().map(NameAsset::getAsset).toList();
        String cycleKey = canonicalCycleKey(cycleStringAssets);
        return lastTriangular.computeIfAbsent(cycleKey, s -> new TriangularArbitrageOpportunity())
                .updateDataAndNextTick(
                        cycleStringAssets,
                        new ArrayList<>(cycleEdges),
                        rateProduct,
                        (rateProduct - 1.0) * 100.0,
                        totalWeight
                );
    }

    private @NotNull Set<NameAsset> trackedAssetsFromLastTriangular() {
        Set<NameAsset> trackedAssets = new HashSet<>();
        for (String cycleKey : lastTriangular.keySet()) {
            String[] assets = cycleKey.split("->");
            int limit = Math.max(0, assets.length - 1); // el último repite el inicio
            trackedAssets.addAll(Arrays.asList(assets).subList(0, limit).stream().map(NameAssetIndexed::new).toList());
        }
        return trackedAssets;
    }

    private @NotNull String canonicalCycleKey(@NotNull List<String> cycleAssets) {
        List<String> raw = new ArrayList<>(cycleAssets.subList(0, cycleAssets.size() - 1));
        int size = raw.size();

        List<String> best = null;
        for (int shift = 0; shift < size; shift++) {
            List<String> rotated = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                rotated.add(raw.get((shift + i) % size));
            }

            if (best == null || compareLex(rotated, best) < 0) {
                best = rotated;
            }
        }

        return String.join("->", Objects.requireNonNull(best, "Error de calculo, No se determino el \"Mejor\"")) + "->" + best.getFirst();
    }

    private int compareLex(@NotNull List<String> a, @NotNull List<String> b) {
        for (int i = 0; i < a.size(); i++) {
            int cmp = a.get(i).compareTo(b.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static class IntegerAtomic {
        public int value;
        public IntegerAtomic(int value) {
            this.value = value;
        }

        public void increment() {
            value++;
        }

        public void decrement() {
            value--;
        }
    }
}
