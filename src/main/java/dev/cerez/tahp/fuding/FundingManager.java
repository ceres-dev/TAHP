package dev.cerez.tahp.fuding;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.command.InputBlocking;
import dev.cerez.tahp.connector.ActionOrden;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.triangular.utils.Switch;
import lombok.Builder;
import lombok.Getter;

import java.text.DecimalFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Getter
public class FundingManager implements Switch {

    private final FundingManagerConfig config;
    private final BinanceConnector connector = new BinanceConnector(false);
    private final InputBlocking inputBlocking = new InputBlocking();
    private final String baseAsset;
    private final String quoteAsset;
    private final String symbol;
    private Status status = Status.READY;

    public FundingManager(FundingManagerConfig config) {
        this.config = config;
        this.baseAsset = config.getBaseAsset();
        this.quoteAsset = config.getQuoteAsset();
        this.symbol = baseAsset + quoteAsset;
    }

    @Override
    public void start() {
        status = Status.WAITING;
        // Bloquea
        new BlockerForSpread(connector, symbol).waitingEntrySpred(config.getEntrySpread());
        status = Status.CHECK;
        if (check()) {
            Log.info("Checks <red>Fail");
            status = Status.READY;
            return;
        }
        Log.info("Checks <green>Ok");
        if (config.isLogsEndPoints()){
            Log.info("Logs de EndPoints Activado");
            connector.setLogEndpoint(true);
        }
        Log.info("Iniciando...");
        status = Status.STARTING;
        // Activar el margen Aislado
        if (!connector.mIsEnableInsolated(symbol)){
            Log.info("Habilitando margen aislado...");
            connector.mSetEnableInsolated(symbol, true);
            Log.info("<green>Habilitado margen aislado");
        }
        BalancePreview preview = new BalancePreview(config.getSizePosition(), config.getBooking());
        // Transferir fondos
        Log.info("Transfiriendo fondos...");
        connector.wTransfer(symbol, BinanceConnector.Transfer.SPOT_TO_FUTURE, quoteAsset, preview.getLongQuote());
        connector.wTransfer(symbol, BinanceConnector.Transfer.SPOT_TO_MARGIN, quoteAsset, preview.getBorrowQuote());
        connector.wTransfer(symbol, BinanceConnector.Transfer.MARGIN_TO_ISOLATED, quoteAsset, preview.getBorrowQuote());
        Log.info("<green>Fondos Transferidos");

        connector.fSetLeverage(symbol, 1);
        // Obtener precios
        CompletableFuture<Double> pF = CompletableFuture.supplyAsync(() -> connector.fGetPrice(symbol));
        CompletableFuture<Double> pS = CompletableFuture.supplyAsync(() -> connector.sGetPrice(symbol));

        double fPrice = pF.join();
        double sPrice = pS.join();
        // Lado Futuro
        CompletableFuture<Void> oF = CompletableFuture.runAsync(() -> {
            Log.info("Abriendo posición Long...");
            connector.fSendOrderToMkt(symbol, ActionOrden.BUY, preview.getLongBase(fPrice), "long");
            Log.info("<green>Posición Long abierta");
        });
        // Lado Margen
        CompletableFuture<Void> oM = CompletableFuture.runAsync(() -> {
            Log.info("Abriendo posición Short...");
            connector.mBorrow(symbol, baseAsset, preview.getBorrowBase(sPrice));
            connector.mSendOrderToMkt(symbol, ActionOrden.SELL, preview.getSellFromBorrowBase(sPrice), "short");
            Log.info("<green>Posición Short abierta");
        });
        oF.join();
        oM.join();
        status = Status.RUNNING;
    }

    @Override
    public void stop() {

    }

    public boolean check(){
        double sizePosition = config.getSizePosition();
        TestFunding.Result testsResults = new TestFunding().run(config);
        if (testsResults.fail() > 0 || testsResults.waring() > 0 || testsResults.weakWaring() > 0){
            if (!inputBlocking.inBoolean("Estas seguro de continual?")){
                Log.info("Abort");
                return true;
            }
        }
        Log.info("Símbolo configurado <green>%s<reset>. Comenzado...", baseAsset+ quoteAsset);

        Map<String, Double> balance = connector.getBalance();
        double usdt = balance.getOrDefault("USDT", 0.0);
        if (usdt < sizePosition + 0.1) {
            Log.error("Abort: Fondos insuficientes");
            return true;
        }else {
            Log.info("Total: %.2fUSDT | Usara: %.2fUSDT | Reserva: %.2fUSDT", usdt, sizePosition, usdt - sizePosition);
        }
        if (!connector.mIsAllowInsolated(symbol)){
            Log.error("Abort: Modo aislado no habilitado");
            return true;
        }
        BalancePreview preview = new BalancePreview(config.getSizePosition(), config.getBooking());
        double fPrice = connector.fGetPrice(symbol);
        double sPrice = connector.sGetPrice(symbol);
        DecimalFormat df = new DecimalFormat("000,000.00000");
        Log.info("""
                Balance previsto (NO SON LAS CANTIDADES EXACTAS)
                |         | BASE          | QUOTE         |
                | Long    | %s | %s |
                | Borrow  | %s | %s |
                | SELL    | %s | %s |
                | Booking | %s | %s |
                """.formatted(
                df.format(preview.getLongBase(fPrice)),           df.format(preview.getLongQuote()),
                df.format(preview.getBookingBase(sPrice)),        df.format(preview.getBookingQuote()),
                df.format(preview.getSellFromBorrowBase(sPrice)), df.format(preview.getSellFromBorrowQuote()),
                df.format(preview.getBookingBase(sPrice)),        df.format(preview.getBorrowQuote())
        ));
        Log.info("EntrySpred: %.3f%%", (fPrice / sPrice - 1)*100d);
        return false;
    }


    @Builder
    @Getter
    public static class FundingManagerConfig {
        private double sizePosition;
        private double booking;
        private String baseAsset;
        private String quoteAsset;
        private double entrySpread;
        private double exitSpread;
        private boolean logsEndPoints;
    }

    public enum Status{
        READY,
        WAITING,
        CHECK,
        STARTING,
        RUNNING,
        STOPING,
        STOPPED,
    }
}
