package dev.cerez.tahp.utils;

import dev.cerez.tahp.engine.SearchTriangularEngine;
import dev.cerez.tahp.engine.model.Action;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@UtilityClass
public class SimulateCycles {

    public @Nullable SimulationResult simulateCycleWithStepSize(@NotNull List<SearchTriangularEngine.ArbitrageEdge> cycleEdges, double startedAmount, double feeRate) {
        return SimulateCycles.simulateCycleWithStepSize(cycleEdges.toArray(new SearchTriangularEngine.ArbitrageEdge[0]), startedAmount, feeRate);
    }

    public @Nullable SimulationResult simulateCycleWithStepSize(@NotNull SearchTriangularEngine.ArbitrageEdge[] cycleEdges, double startedAmount, double feeRate) {
        double amount = startedAmount;
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

                amount = quantity * referencePrice * (1.0 - feeRate);
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

                amount = quantity * (1.0 - feeRate);
//                continue;
            }

//            return null;
        }

        if (!Double.isFinite(amount) || amount <= 0.0) {
            return null;
        }
        return new SimulationResult(amount / startedAmount, amount);
    }

    private double roundDownToStepSize(double amount, double stepSize) {
        return (int) (amount *  (1 /stepSize)) * stepSize;
    }

    public record SimulationResult(
            double rateProduct,
            double endAmount
    ) {}
}
