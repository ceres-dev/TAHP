package dev.cerez.tahp.fuding;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.connector.model.Symbol;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.BiFunction;

public class TestFunding {

    @SuppressWarnings("resource")
    public Result run(FundingManager.FundingManagerConfig config) {
        BinanceConnector connector = new BinanceConnector(true);
        connector.start();
        String baseAsset = config.getBaseAsset();
        String quotAsset = config.getQuoteAsset();
        BigDecimal balance = config.getSizePosition();
        int succes = 0;
        int weakWaring = 0;
        int waring = 0;
        int fail = 0;
        int i = 0;
        // b=Base q=quote ejemplo=BTCUSDT
        Symbol fSymbol = connector.fGetAllSymbol().get(baseAsset+quotAsset);
        Symbol sSymbol = connector.getAllSymbols().get(baseAsset+quotAsset);
        BalancePreview balancePreview = new BalancePreview(balance, config.getBooking());
        for (Test t : List.of(
                new Test("Conversion base -> quote", (b, q) -> connector.cPossibleConvert(b, q) ? ResultType.OK.toResult() : ResultType.FAIL.toResult()),
                new Test("Conversion base <- quote", (b, q) -> connector.cPossibleConvert(q, b) ? ResultType.OK.toResult() : ResultType.FAIL.toResult()),
                new Test("FundingRate", (b, q) -> {
                    double f = connector.fGetFundingRate().get(b + q).nextFundingRate();
                    if (f*100 > 1) {
                        return ResultType.FAIL.toResult((f*100) + "%");
                    } else if (f > 0){
                        return ResultType.WARNING.toResult((f*100) + "%");
                    }else if (f*100d > -0.05d) {
                        return ResultType.WEAK_WARNING.toResult((f*100) + "%");
                    }else {
                        return ResultType.OK.toResult();
                    }
                }),
                new Test("Min Conversion possible", (b, q) -> {
                    BinanceConnector.Convert convert = connector.cGetMinMaxConvert(q, b);
                    if (convert == null) {
                        return ResultType.FAIL.toResult("No exits");
                    }
                    BigDecimal price = connector.sGetPrice(b+q);
                    if (convert.toMin() >= balancePreview.getBookingQuote().doubleValue()){
                        return ResultType.FAIL.toResult(q + " = C=" + convert.toMin() + " < P=" + balancePreview.getBookingQuote());
                    }else if (convert.fromMin() >= balancePreview.getBookingBase(price).doubleValue()) {
                        return ResultType.FAIL.toResult(b + " = C=" + convert.fromMin() + " < P=" + balancePreview.getBookingBase(price));
                    }
                    return ResultType.OK.toResult();
                }),
                new Test("Future Lot Size", (b, q) -> {
                    BigDecimal qty = balancePreview.getLongBase(connector.fGetPrice(b+q));
                    BigDecimal executable = fSymbol.roundTickSize(qty);
                    double efficiency = executable.divide(qty, 12, RoundingMode.DOWN).doubleValue();
                    if (efficiency > 0.9999){
                        return ResultType.OK.toResult();
                    }else if (efficiency > 0.999) {
                        return ResultType.WEAK_WARNING.toResult("%.4f%% Efficiency".formatted(efficiency*100d));
                    }else {
                        return ResultType.WARNING.toResult("%.4f%% Efficiency".formatted(efficiency * 100d));
                    }
                }),
                new Test("Future MinNotional", (b, q) -> {
                    double price = connector.sGetPrice(b+q).doubleValue();
                    double qty = balancePreview.getLongBase(price).doubleValue();
                    double realQty = Math.floor(qty / fSymbol.getStepSize().doubleValue()) * fSymbol.getStepSize().doubleValue();
                    double realNotional = realQty * price;
                    return realNotional >= fSymbol.getMinNotional().doubleValue() ?
                            ResultType.OK.toResult() :
                            ResultType.FAIL.toResult(fSymbol.getMinNotional() + " < " + balancePreview.getLongQuote());

                }),
                new Test("Spot Lot Size", (b, q) -> {
                    BigDecimal qty = balancePreview.getSellFromBorrowBase(connector.sGetPrice(b+q));
                    BigDecimal executable = fSymbol.roundTickSize(qty);
                    double efficiency = executable.divide(qty, 12, RoundingMode.DOWN).doubleValue();
                    if (efficiency > 0.9999){
                        return ResultType.OK.toResult();
                    }else if (efficiency > 0.999) {
                        return ResultType.WEAK_WARNING.toResult("%.4f%% Efficiency".formatted(efficiency*100d));
                    }else {
                        return ResultType.WARNING.toResult("%.4f%% Efficiency".formatted(efficiency * 100d));
                    }
                }),
                new Test("Spot MinNotional", (b, q) -> {
                    double price = connector.sGetPrice(b+q).doubleValue();
                    double qty = balancePreview.getSellFromBorrowBase(price).doubleValue();
                    double realQty = Math.floor(qty / sSymbol.getStepSize().doubleValue()) * sSymbol.getStepSize().doubleValue();
                    double realNotional = realQty * price;
                    return realNotional >= sSymbol.getMinNotional().doubleValue() ?
                            ResultType.OK.toResult() :
                            ResultType.FAIL.toResult(sSymbol.getMinNotional() + " < " + balancePreview.getLongQuote());

                }),
                new Test("Profit Rate", (b, q) -> {
                    BinanceConnector.FundingRate fundingRate = connector.fGetFundingRate().get(b + q);
                    double interest = connector.mGetInterest(b) * fundingRate.interval();
                    double funding = Math.abs(fundingRate.nextFundingRate());
                    if (interest*10 < funding){
                        return ResultType.OK.toResult();
                    }else if (interest*5 < funding) {
                        return ResultType.WEAK_WARNING.toResult("%.2f%% Loss".formatted((1-(interest/funding))*100d));
                    }else if (interest*2 < funding) {
                        return ResultType.WARNING.toResult("%.2f%% Loss".formatted((1-(interest/funding))*100d));
                    }else {
                        return ResultType.FAIL.toResult("%.2f%% Loss".formatted((1-(interest/funding))*100d));
                    }
                })
        )) {
            Log.info("[%d] Test: %s", ++i, t.name);
            ResultTest resultTest = t.function.apply(baseAsset, quotAsset);
            switch (resultTest.type) {
                case OK -> {
                    if (resultTest.message == null){
                        Log.info("  <green>OK");
                    }else {
                        Log.info("  <green>OK: %s", resultTest.message);
                    }
                    succes++;
                }
                case WEAK_WARNING -> {
                    if (resultTest.message == null){
                        Log.info("  <green_yellow>weak warning");
                    }else {
                        Log.info("  <green_yellow>weak warning: %s", resultTest.message);
                    }
                    weakWaring++;
                }
                case WARNING -> {
                    if (resultTest.message == null){
                        Log.info("  <yellow>warning");
                    }else {
                        Log.info("  <yellow>warning: %s", resultTest.message);
                    }
                    waring++;
                }
                case FAIL -> {
                    if (resultTest.message == null){
                        Log.info("  <red>fail");
                    }else {
                        Log.info("  <red>fail: %s", resultTest.message);
                    }
                    fail++;
                }
            }
        }
        Log.info("<green>OK: %d <green_yellow>WEAK: %d <yellow>WARING: %d <red>FAIL: %d", succes, weakWaring, waring, fail);
        return new Result(succes, weakWaring, waring, fail);
    }

    public enum ResultType {
        OK,
        WEAK_WARNING,
        WARNING,
        FAIL;

        @Contract(pure = true, value = " -> new")
        private @NotNull TestFunding.ResultTest toResult(){
            return new ResultTest(null, this);
        }

        @Contract(pure = true, value = "_ -> new")
        private @NotNull TestFunding.ResultTest toResult(@NotNull String message){
            return new ResultTest(message, this);
        }

    }

    public record Result(int succes, int weakWaring, int waring, int fail){}

    private record ResultTest(String message, ResultType type){}

    private record Test(String name, BiFunction<String, String, ResultTest> function) {}
}
