package dev.cerez.tahp.connector.model;

import dev.cerez.tahp.model.MarketStatus;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

public class Symbol {

    @NotNull private final String symbol;

    @NotNull @Getter private final Boolean isTradFi;
    @NotNull @Getter private final Boolean isSpot;
    @NotNull @Getter private final Boolean isAllowTrading;
    @NotNull @Getter private final Integer pricePrecision;
    @NotNull @Getter private final Integer quantityPrecision;
    @NotNull @Getter private final MarketStatus marketStatus;
    @NotNull @Getter private final String baseAsset;
    @NotNull @Getter private final String quoteAsset;
    @NotNull @Getter private final Set<String> permissions;
    @NotNull @Getter private final Double stepSize;

    public Symbol(@NotNull String symbol,
                  @NotNull Boolean isTradFi,
                  @NotNull Boolean isSpot,
                  @NotNull Integer pricePrecision,
                  @NotNull Integer quantityPrecision,
                  @NotNull MarketStatus marketStatus,
                  @NotNull String baseAsset,
                  @NotNull String quoteAsset,
                  @NotNull Boolean spotTradingAllowed,
                  @NotNull Double stepSize,
                  @NotNull Set<String> permissions
    ) {
        this.symbol = symbol;
        this.isTradFi = isTradFi;
        this.isSpot = isSpot;
        this.isAllowTrading = spotTradingAllowed;
        this.pricePrecision = pricePrecision;
        this.quantityPrecision = quantityPrecision;
        this.marketStatus = marketStatus;
        this.baseAsset = baseAsset;
        this.quoteAsset = quoteAsset;
        this.stepSize = stepSize;
        this.permissions = permissions;
    }

    public @NotNull String name() {
        return symbol;
    }

    public Double getStepSizeRaw(){
        return stepSize == null ? 0.0 : stepSize;
    }

    public String formatQuantity(@NotNull Double quantity) {
        Double optionalStepSize = getStepSize();
        BigDecimal stepSize = BigDecimal.valueOf(optionalStepSize);
        if (stepSize.signum() > 0) {
            BigDecimal quantityDecimal = BigDecimal.valueOf(quantity);
            BigDecimal steppedQuantity = quantityDecimal
                    .divide(stepSize, 0, RoundingMode.DOWN)
                    .multiply(stepSize)
                    .stripTrailingZeros();
            return steppedQuantity.toPlainString();
        }
        return formatQuantitySimple(quantity);
    }

    public String formatQuantitySimple(@NotNull Double quantity) {
        String s = "%." + getQuantityPrecision() + "f";
        return String.format(Locale.US, s, quantity);
    }

    public String formatQuoteOrderQty(@NotNull Double amount) {
        return BigDecimal.valueOf(amount)
                .setScale(getPricePrecision(), RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString();
    }


    @Override
    public String toString() {
        return name();
    }



}
