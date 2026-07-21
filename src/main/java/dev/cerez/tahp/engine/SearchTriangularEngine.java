package dev.cerez.tahp.engine;

import dev.cerez.tahp.connector.model.BookTicker;
import dev.cerez.tahp.connector.model.Symbol;
import dev.cerez.tahp.engine.model.NameAsset;
import dev.cerez.tahp.model.Action;
import dev.cerez.tahp.model.TriangularArbitrageOpportunity;
import lombok.*;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@RequiredArgsConstructor
public abstract class SearchTriangularEngine {

    public static final double PROFIT_EPSILON = 1e-12;

    protected final EngineConfig engineConfig;
    protected final ConcurrentMap<String, TriangularArbitrageOpportunity> lastTriangular = new ConcurrentHashMap<>();
    protected final ConcurrentMap<String, BookTicker> liveTickers = new ConcurrentHashMap<>();
    protected final ConcurrentMap<String, NameAssetIndexed> nameAssetCache = new ConcurrentHashMap<>();
    protected final ConcurrentMap<NameAsset, ArrayList<ArbitrageEdge>> outgoingByFromAsset = new ConcurrentHashMap<>();

    @SuppressWarnings("DataFlowIssue")
    @NotNull(value = "Call configure first")
    protected Map<String, Symbol> allSymbolsMap = null;

    public abstract List<TriangularArbitrageOpportunity> computeTriangularArbitrageOpportunities(
            @NotNull BookTicker updatedTicker
    );

    public void configure(@NotNull Map<String, Symbol> allSymbolMap, @NotNull Map<String, BookTicker> liveTickers) {
        this.allSymbolsMap = allSymbolMap;
        buildGraf(allSymbolMap, liveTickers);
    }

    public int getTotalCycle(){
        int totalCycle = 0;
        for (NameAsset a : outgoingByFromAsset.keySet()) {
            List<ArbitrageEdge> edgesAB = List.copyOf(outgoingByFromAsset.get(a));

            for (ArbitrageEdge ab : edgesAB) {
                NameAsset b = ab.getToAsset();
                List<ArbitrageEdge> edgesBC = List.copyOf(outgoingByFromAsset.get(b));

                for (ArbitrageEdge bc : edgesBC) {
                    NameAsset c = bc.getToAsset();
                    if (c.equals(a)) continue;
                    List<ArbitrageEdge> edgesCA = List.copyOf(outgoingByFromAsset.get(c));
                    for (ArbitrageEdge ca : edgesCA) {
                        if (ca.getToAsset().equals(a)) {
                            totalCycle++;
                        }
                    }
                }
            }
        }
        return totalCycle;
    }

    protected abstract void buildGraf(@NotNull Map<String, Symbol> exchangeInfoSpot, @NotNull Map<String, BookTicker> liveTickers);

    protected abstract @NotNull Map<NameAsset, ArrayList<ArbitrageEdge>> updateGraf(
            @NotNull Map<String, Symbol> exchangeInfoSpot,
            @NotNull BookTicker updatedTicker
    );

    protected @NotNull List<ArbitrageEdge> rotateCycleToPreferredStart(@NotNull List<ArbitrageEdge> cycleEdges) {
        int preferredIndex = -1;
        int i = 0;
        for (ArbitrageEdge edge : cycleEdges) {
            if (engineConfig.getPreferredStartAsset().equals(edge.getFromAsset().getName())) {
                preferredIndex = i;
                break;
            }
            i++;
        }
        if (preferredIndex <= 0) {
            return cycleEdges;
        }

        List<ArbitrageEdge> rotated = new ArrayList<>(cycleEdges);
        Collections.rotate(rotated, -preferredIndex);
        return rotated;
    }

    @Data
    @AllArgsConstructor
    public static final class ArbitrageEdge {
        private final String symbol;
        private final NameAsset fromAsset;
        private final NameAsset toAsset;
        private volatile double rate;
        private volatile double weight;
        private final @NotNull Action action;
        private volatile double referencePrice;
        private volatile double referenceLiquidity;
        private final double stepSize;
    }

    @Getter
    public static class LifeTime {
        private int ticks = 0;

        public void nextTicks() {
            this.ticks++;
        }

        public void resetTicks() {
            this.ticks = 0;
        }
    }

    public record OnOpportunities(
            List<TriangularArbitrageOpportunity> opportunities,
            long AbsoluteDelayComputeNanoTime
    ){}

    @Getter
    public static final class NameAssetIndexed extends NameAsset {

        private static final AtomicInteger indexCurrent = new AtomicInteger(0);
        private static final ConcurrentMap<String, Integer> indexes = new ConcurrentHashMap<>();

        public NameAssetIndexed(String asset) {
            super(asset, indexes.computeIfAbsent(asset, (a) -> indexCurrent.getAndIncrement()));
        }

        public static int currentIndex() {
            return indexCurrent.get();
        }
    }

    @Data
    @Builder
    public static final class EngineConfig {
        @Builder.Default public int maxSymbols = 1500;
        @Builder.Default public double defaultFeeRate = 0.001;
        @Builder.Default public double defaultStartAmount = 10d;
        @Builder.Default public int minCycleLength = 3;
        @Builder.Default public int maxCycleLength = 3;
        @Builder.Default public String preferredStartAsset = "USDT";
    }
}
