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
    @NotNull @Getter private final Double stepSize; // La precision en la compra o venta dela base
    @NotNull @Getter private final Double minNotional; // El minimo para hacer una compra o venta en quote

    public Symbol(@NotNull String symbol,
                  @NotNull Integer basePrecision,
                  @NotNull Integer quotePrecision,
                  @NotNull String baseAsset,
                  @NotNull String quoteAsset,
                  @NotNull Boolean spotTradingAllowed,
                  @NotNull Double stepSize,
                  @NotNull Double minNotional
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
        return stepSize == null ? 0.0 : stepSize;
    }

    public double roundBase(double amountBase) {
        return new BigDecimal(amountBase).setScale(basePrecision, RoundingMode.DOWN).doubleValue();
    }

    public double roundQuote(double amountQuote) {
        return new BigDecimal(amountQuote).setScale(quotePrecision, RoundingMode.DOWN).doubleValue();
    }

    public double roundTickSize(double value) {
        BigDecimal bigDecimal = new BigDecimal(stepSize);
        return new BigDecimal(value)
                .divide(bigDecimal, 0, RoundingMode.DOWN)
                .multiply(bigDecimal)
                .doubleValue();
    }



    @Override
    public String toString() {
        return name();
    }



}
