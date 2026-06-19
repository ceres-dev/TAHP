package dev.cerez.tahp.utils;

import dev.cerez.tahp.engine.SearchTriangularEngine;
import dev.cerez.tahp.model.Action;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@UtilityClass
public class SimulateCycles {

    public @Nullable SimulationResult simulateCycleWithStepSize(@NotNull List<SearchTriangularEngine.ArbitrageEdge> cycleEdges) {
        return SimulateCycles.simulateCycleWithStepSize(cycleEdges.toArray(new SearchTriangularEngine.ArbitrageEdge[0]));
    }

    public @Nullable SimulationResult simulateCycleWithStepSize(@NotNull SearchTriangularEngine.ArbitrageEdge[] cycleEdges) {
        double amount = SearchTriangularEngine.DEFAULT_START_AMOUNT;
        for (SearchTriangularEngine.ArbitrageEdge edge : cycleEdges) {
//            if (!Double.isFinite(amount) || amount <= 0.0) {
//                return null;
//            }

            Action action = edge.getAction();
            double referencePrice = edge.getReferencePrice();
            double referenceLiquidity = edge.getReferenceLiquidity();
            double stepSize = edge.getStepSize();

            if (Action.SELL == action) {
                double quantity = roundDownToStepSize(amount, stepSize);

//                if (quantity <= 0.0) {
//                    return null;
//                }

                if (quantity > referenceLiquidity) {
                    return null;
                }

                amount = quantity * referencePrice * (1.0 - SearchTriangularEngine.DEFAULT_FEE_RATE);
//                continue;
            }

            else /* if  (Action.BUY  == action) */{
                double quantity = roundDownToStepSize(amount / referencePrice, stepSize);

//                if (quantity <= 0.0) {
//                    return null;
//                }

                if (quantity > referenceLiquidity) {
                    return null;
                }

                amount = quantity * (1.0 - SearchTriangularEngine.DEFAULT_FEE_RATE);
//                continue;
            }

//            return null;
        }

        if (!Double.isFinite(amount) || amount <= 0.0) {
            return null;
        }
        return new SimulationResult(amount / SearchTriangularEngine.DEFAULT_START_AMOUNT, amount);
    }

    private double roundDownToStepSize(double amount, double stepSize) {
        return (int) (amount *  (1 /stepSize)) * stepSize;
    }

    public record SimulationResult(
            double rateProduct,
            double endAmount
    ) {}
}
