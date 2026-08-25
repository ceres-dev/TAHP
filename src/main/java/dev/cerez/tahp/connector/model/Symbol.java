package dev.cerez.tahp.connector.model;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class Symbol {

    @NotNull private final String symbol;

    @NotNull @Getter private final Boolean isAllowTrading;
    @NotNull @Getter private final Integer basePrecision;
    @NotNull @Getter private final Integer quotePrecision;
    @NotNull @Getter private final String baseAsset;
    @NotNull @Getter private final String quoteAsset;
    @NotNull @Getter private final BigDecimal stepSize; // La precision en la compra o venta dela base
    @NotNull @Getter private final BigDecimal minNotional; // El minimo para hacer una compra o venta en quote

    public Symbol(@NotNull String symbol,
                  @NotNull Integer basePrecision,
                  @NotNull Integer quotePrecision,
                  @NotNull String baseAsset,
                  @NotNull String quoteAsset,
                  @NotNull Boolean spotTradingAllowed,
                  @NotNull BigDecimal stepSize,
                  @NotNull BigDecimal minNotional
    ) {
        this.symbol = symbol;
        this.isAllowTrading = spotTradingAllowed;
        this.basePrecision = basePrecision;
        this.quotePrecision = quotePrecision;
        this.baseAsset = baseAsset;
        this.quoteAsset = quoteAsset;
        this.stepSize = stepSize;
        this.minNotional = minNotional;
    }

    public @NotNull String name() {
        return symbol;
    }

    public Double getStepSizeRaw(){
        return stepSize.doubleValue();
    }

    public double roundBase(double amountBase) {
        return BigDecimal.valueOf(amountBase)
                .setScale(basePrecision, RoundingMode.DOWN)
                .doubleValue();
    }

    public double roundQuote(double amountQuote) {
        return BigDecimal.valueOf(amountQuote)
                .setScale(quotePrecision, RoundingMode.DOWN)
                .doubleValue();
    }

    public double roundTickSize(double value) {
        return new BigDecimal(value)
                .divide(stepSize, 0, RoundingMode.DOWN)
                .multiply(stepSize)
                .doubleValue();
    }

    public BigDecimal roundBase(BigDecimal amountBase) {
        return amountBase
                .setScale(basePrecision, RoundingMode.DOWN);
    }

    public BigDecimal roundQuote(BigDecimal amountQuote) {
        return amountQuote
                .setScale(quotePrecision, RoundingMode.DOWN);
    }

    public BigDecimal roundTickSize(BigDecimal value) {
        return value
                .divide(stepSize, 0, RoundingMode.DOWN)
                .multiply(stepSize);
    }

    @Override
    public String toString() {
        return name();
    }

}
