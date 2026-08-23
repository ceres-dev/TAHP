package dev.cerez.tahp.connector.model;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

public class Symbol {

    @NotNull private final String symbol;

    @NotNull @Getter private final Boolean isAllowTrading;
    @NotNull @Getter private final Integer pricePrecision;
    @NotNull @Getter private final Integer quotePrecision;
    @NotNull @Getter private final String baseAsset;
    @NotNull @Getter private final String quoteAsset;
    @NotNull @Getter private final Set<String> permissions;
    @NotNull @Getter private final Double stepSize; // La precision en la compra o venta de un activo

    public Symbol(@NotNull String symbol,
                  @NotNull Integer basePrecision,
                  @NotNull Integer quotePrecision,
                  @NotNull String baseAsset,
                  @NotNull String quoteAsset,
                  @NotNull Boolean spotTradingAllowed,
                  @NotNull Double stepSize,
                  @NotNull Set<String> permissions
    ) {
        this.symbol = symbol;
        this.isAllowTrading = spotTradingAllowed;
        this.pricePrecision = basePrecision;
        this.quotePrecision = quotePrecision;
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
        String s = "%." + getQuotePrecision() + "f";
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
