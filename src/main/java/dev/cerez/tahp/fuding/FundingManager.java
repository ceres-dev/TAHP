package dev.cerez.tahp.fuding;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.command.InputBlocking;
import dev.cerez.tahp.connector.ActionOrden;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.triangular.utils.Switch;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Getter
public class FundingManager implements Switch {

    private final FundingManagerConfig config;
    private final BinanceConnector connector = new BinanceConnector(true);
    private final InputBlocking inputBlocking = new InputBlocking();
    private final String baseAsset;
    private final String quoteAsset;
    private final String symbol;
    private Status status = Status.READY;
    private final UUID uuid = UUID.randomUUID();

    public FundingManager(@NotNull FundingManagerConfig config) {
        this.config = config;
        this.baseAsset = config.getBaseAsset();
        this.quoteAsset = config.getQuoteAsset();
        this.symbol = baseAsset + quoteAsset;
    }

    @Override
    public void start() {
        status = Status.WAITING;
        // Bloquea
        new BlockerForSpread(connector, symbol).waitingEntrySpred(config.getEntrySpread().doubleValue());
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
        connector.wTransfer(null, BinanceConnector.Transfer.SPOT_TO_FUTURE, quoteAsset, preview.getLongQuote());
        connector.wTransfer(null, BinanceConnector.Transfer.SPOT_TO_MARGIN, quoteAsset, preview.getBorrowQuote());
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
            connector.fSendOrderToMkt(symbol, ActionOrden.BUY, preview.getLongBase(fPrice), "long-" + uuid);
            Log.info("<green>Posición Long abierta");
        });
        // Lado Margen
        CompletableFuture<Void> oM = CompletableFuture.runAsync(() -> {
            Log.info("Abriendo posición Short...");
            connector.mBorrow(symbol, baseAsset, preview.getBorrowBase(sPrice).doubleValue());
            connector.mSendOrderToMkt(symbol, ActionOrden.SELL, preview.getSellFromBorrowBase(sPrice), "short-" + uuid, true);
            Log.info("<green>Posición Short abierta");
        });
        oF.join();
        oM.join();
        status = Status.RUNNING;
    }

    @Override
    public void stop() {
        BinanceConnector.Position position = connector.fGetPosition(symbol);
        BinanceConnector.Order order = connector.sGetOrder(symbol,"short-" + uuid);
        if (position == null) {
            Log.info("<red>La posición long no exite");
            return;
        }
        if (order == null) {
            Log.info("<red>La posición short no exite");
            return;
        }
        BigDecimal borrowed = connector.mGetBorrowed(symbol);
        CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
            connector.fSendOrderToMkt(symbol, ActionOrden.SELL, position.quantity(), "long_sell-" + uuid);
            BigDecimal balance = connector.fGetBalance();
            connector.wTransfer(null, BinanceConnector.Transfer.FUTURE_TO_SPOT, quoteAsset, balance);
        });
        CompletableFuture<Void> m = CompletableFuture.runAsync(() -> {
            connector.mSendOrderToMkt(symbol, ActionOrden.BUY, order.quoteAmount(), "short_buy-" + uuid, false);
        });
        f.join();
        m.join();
        BinanceConnector.BalanceInsolated balanceInsolated = connector.mGetBalance(symbol);
        if (balanceInsolated.base().free().compareTo(borrowed) >= 0) {
            connector.mRepay(symbol, baseAsset, borrowed);
        }else {
            BigDecimal delta = borrowed.subtract(balanceInsolated.base().free());
            // Se convierte
            String id = connector.cConvert(quoteAsset, baseAsset, delta, false);
            connector.cAccept(id);
            // Se transfiere lo convertido
            connector.wTransfer(null, BinanceConnector.Transfer.SPOT_TO_MARGIN, baseAsset, delta);
            connector.wTransfer(symbol, BinanceConnector.Transfer.MARGIN_TO_ISOLATED, baseAsset, delta);
            connector.mRepay(symbol, baseAsset, borrowed);
        }
    }

    public boolean check(){
        BigDecimal sizePosition = config.getSizePosition();
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
        if (usdt < sizePosition.add(new BigDecimal("0.1")).doubleValue()) {
            Log.error("Abort: Fondos insuficientes");
            return true;
        }else {
            Log.info("Total: %.2fUSDT | Usara: %.2fUSDT | Reserva: %.2fUSDT", usdt, sizePosition.doubleValue(), BigDecimal.valueOf(usdt).subtract(sizePosition).doubleValue());
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
        private BigDecimal sizePosition;
        private BigDecimal booking;
        private String baseAsset;
        private String quoteAsset;
        private BigDecimal entrySpread;
        private BigDecimal exitSpread;
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
