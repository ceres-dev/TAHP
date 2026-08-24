package dev.cerez.tahp.fuding;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.connector.model.Symbol;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.BiFunction;

public class CheckFunding {

    public void run(String baseAsset, String quoteAsset, int balance) {
        BinanceConnector connector = new BinanceConnector(false);
        int succes = 0;
        int weakWaring = 0;
        int waring = 0;
        int fail = 0;
        int i = 0;
        // b=Base q=quote ejemplo=BTCUSDT
        Symbol fSymbol = connector.fGetAllSymbol().get(baseAsset+quoteAsset);
        Symbol sSymbol = connector.getAllSymbols().get(baseAsset+quoteAsset);
        BalancePreview balancePreview = new BalancePreview(balance);
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
                    double price = connector.sGetPrice(b+q);
                    if (convert.toMin() >= balancePreview.getBookingQuote()){
                        return ResultType.FAIL.toResult(q + " = C=" + convert.toMin() + " < P=" +  + balancePreview.getBookingQuote());
                    }else if (convert.fromMin() >= balancePreview.getBookingBase(price)) {
                        return ResultType.FAIL.toResult(b + " = C=" + convert.fromMin() + " < P=" + balancePreview.getBookingBase(price));
                    }
                    return ResultType.OK.toResult();
                }),
                new Test("Future Lot Size", (b, q) -> {
                    double qty = balancePreview.getLongBase(connector.fGetPrice(b+q));
                    double executable = Math.floor(qty / fSymbol.getStepSize()) * fSymbol.getStepSize();
                    double efficiency = executable / qty;
                    if (efficiency > 0.9999){
                        return ResultType.OK.toResult();
                    }else if (efficiency > 0.999) {
                        return ResultType.WEAK_WARNING.toResult("%.4f%% Efficiency".formatted(efficiency*100d));
                    }else if (efficiency > 0.99) {
                        return ResultType.WARNING.toResult("%.4f%% Efficiency".formatted(efficiency * 100d));
                    }else {
                        return ResultType.FAIL.toResult("%.4f%% Efficiency".formatted(efficiency * 100d));
                    }
                }),
                new Test("Future MinNotional", (b, q) -> {
                    double qty = balancePreview.getLongBase(connector.fGetPrice(b+q));
                    double realQty = Math.floor(qty / fSymbol.getStepSize()) * fSymbol.getStepSize();
                    double price = connector.sGetPrice(b+q);
                    double realNotional = realQty * price;
                    return realNotional >= fSymbol.getMinNotional() ?
                            ResultType.OK.toResult() :
                            ResultType.FAIL.toResult(fSymbol.getMinNotional() + " < " + balancePreview.getLongQuote());

                }),
                new Test("Spot Lot Size", (b, q) -> {
                    double qty = balancePreview.getSellFromBorrowBase(connector.sGetPrice(b+q));
                    double executable = Math.floor(qty / sSymbol.getStepSize()) * sSymbol.getStepSize();
                    double efficiency = executable / qty;
                    if (efficiency > 0.9999){
                        return ResultType.OK.toResult();
                    }else if (efficiency > 0.999) {
                        return ResultType.WEAK_WARNING.toResult("%.4f%% Efficiency".formatted(efficiency*100d));
                    }else if (efficiency > 0.99) {
                        return ResultType.WARNING.toResult("%.4f%% Efficiency".formatted(efficiency * 100d));
                    }else {
                        return ResultType.FAIL.toResult("%.4f%% Efficiency".formatted(efficiency * 100d));
                    }
                })
        )) {
            Log.info("[%d] Test: %s", ++i, t.name);
            Result result = t.function.apply(baseAsset, quoteAsset);
            switch (result.type) {
                case OK -> {
                    if (result.message == null){
                        Log.info("  <green>OK");
                    }else {
                        Log.info("  <green>OK: %s", result.message);
                    }
                    succes++;
                }
                case WEAK_WARNING -> {
                    if (result.message == null){
                        Log.info("  <green_yellow>weak warning");
                    }else {
                        Log.info("  <green_yellow>weak warning: %s", result.message);
                    }
                    weakWaring++;
                }
                case WARNING -> {
                    if (result.message == null){
                        Log.info("  <yellow>warning");
                    }else {
                        Log.info("  <yellow>warning: %s", result.message);
                    }
                    waring++;
                }
                case FAIL -> {
                    if (result.message == null){
                        Log.info("  <red>fail");
                    }else {
                        Log.info("  <red>fail: %s", result.message);
                    }
                    fail++;
                }
            }
        }
        Log.info("<green>OK: %d <green_yellow>WEAK: %d <yellow>WARING: %d <red>FAIL: %d", succes, weakWaring, waring, fail);
    }

    public enum ResultType {
        OK,
        WEAK_WARNING,
        WARNING,
        FAIL;

        @Contract(pure = true, value = " -> new")
        private @NotNull Result toResult(){
            return new Result(null, this);
        }

        @Contract(pure = true, value = "_ -> new")
        private @NotNull Result toResult(@NotNull String message){
            return new Result(message, this);
        }

    }

    private record Result(String message, ResultType type){};

    private record Test(String name, BiFunction<String, String, Result> function) {}
}
