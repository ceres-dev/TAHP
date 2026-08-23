package dev.cerez.tahp.utils;

import dev.cerez.tahp.engine.SearchTriangularEngine;
import lombok.Data;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@Data
public final class TriangularArbitrageOpportunity {
    private List<String> assetsCycle;
    private List<SearchTriangularEngine.ArbitrageEdge> edges;
    private final SearchTriangularEngine.LifeTime lifeTime;
    private double rateProduct;
    private double profitPercent;
    private double totalWeight;

    public TriangularArbitrageOpportunity(
            List<String> assetsCycle,
            List<SearchTriangularEngine.ArbitrageEdge> edges,
            SearchTriangularEngine.LifeTime lifeTime,
            double rateProduct,
            double profitPercent,
            double totalWeight
    ) {
        this.assetsCycle = assetsCycle;
        this.edges = edges;
        this.lifeTime = lifeTime;
        this.rateProduct = rateProduct;
        this.profitPercent = profitPercent;
        this.totalWeight = totalWeight;
    }

    public TriangularArbitrageOpportunity() {
        this.assetsCycle = List.of();
        this.edges =  List.of();
        this.lifeTime = new SearchTriangularEngine.LifeTime();
        this.rateProduct = -1;
        this.profitPercent = -1;
        this.totalWeight = -1;
    }

    @Contract("_, _, _, _, _ -> this")
    public TriangularArbitrageOpportunity updateDataAndNextTick(
            List<String> assetsCycle,
            List<SearchTriangularEngine.ArbitrageEdge> edges,
            double rateProduct,
            double profitPercent,
            double totalWeight
    ) {
        this.assetsCycle = assetsCycle;
        this.edges = edges;
        this.lifeTime.nextTicks();
        this.rateProduct = rateProduct;
        this.profitPercent = profitPercent;
        this.totalWeight = totalWeight;
        return this;
    }

    @Contract(value = " -> new", pure = true)
    public @NotNull TriangularArbitrageOpportunity copy() {
        return new TriangularArbitrageOpportunity(
                assetsCycle,
                edges,
                lifeTime,
                rateProduct,
                profitPercent,
                totalWeight
        );
    }

    @Contract(value = "_, _ -> new", pure = true)
    public @NotNull TriangularArbitrageOpportunity copyAndSetAssetsCycle(List<String> assetsCycle,
                                                                         List<SearchTriangularEngine.ArbitrageEdge> edges
    ) {
        return new TriangularArbitrageOpportunity(
                assetsCycle,
                edges,
                lifeTime,
                rateProduct,
                profitPercent,
                totalWeight
        );
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof TriangularArbitrageOpportunity t) {
            return t.totalWeight == this.totalWeight && t.assetsCycle.equals(this.assetsCycle);
        }
        return false;
    }
}
