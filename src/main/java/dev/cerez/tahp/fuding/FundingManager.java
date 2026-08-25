package dev.cerez.tahp.fuding;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.command.InputUser;
import dev.cerez.tahp.connector.ActionOrden;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.io.IOdata;
import dev.cerez.tahp.triangular.utils.Switch;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Getter
public class FundingManager implements Switch {

    private final FundingManagerConfig config;
    private final BinanceConnector connector = new BinanceConnector(true);
    private final InputUser inputUser = new InputUser();
    private final String baseAsset;
    private final String quoteAsset;
    private final String symbol;
    private Status status = Status.READY;
    private final UUID uuid;

    private boolean isStarted = false;

    public FundingManager(@NotNull FundingManagerConfig config) {
        PersistenData data = IOdata.loadPersistenDataFundingManager(new PersistenData(this));
        if (data.isActive) {
            Log.warning("El programa no termino el proceso de cierre adecuadamente. La estrategia esta corriendo");
            this.config = data.config;
            this.baseAsset = data.config.getBaseAsset();
            this.quoteAsset = data.config.getQuoteAsset();
            this.symbol = baseAsset + quoteAsset;
            this.uuid = data.uuid;
            this.status = data.status;
        }else {
            this.config = config;
            this.baseAsset = config.getBaseAsset();
            this.quoteAsset = config.getQuoteAsset();
            this.symbol = baseAsset + quoteAsset;
            this.uuid = UUID.randomUUID();
        }
    }

    @Override
    public void start() {
        if (isStarted){
            return;
        } else isStarted = true;
        status = Status.CHECK;
        if (checkPreStart()) {
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
        CompletableFuture<BigDecimal> pF = CompletableFuture.supplyAsync(() -> connector.fGetPrice(symbol));
        CompletableFuture<BigDecimal> pS = CompletableFuture.supplyAsync(() -> connector.sGetPrice(symbol));

        BigDecimal fPrice = pF.join();
        BigDecimal sPrice = pS.join();
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
        IOdata.savePersistenDataFundingManager(new PersistenData(this));
        status = Status.RUNNING;
    }

    @Override
    public void stop() {
        if (!isStarted) {
            return;
        }else isStarted = false;
        BinanceConnector.Position position = connector.fGetPosition(symbol);
        BinanceConnector.Order order = connector.sGetOrder(symbol,"short-" + uuid);
        if (position == null) {
            Log.error("La posición long no exite");
            return;
        }
        if (order == null) {
            Log.error("La posición short no exite");
            return;
        }
        BigDecimal borrowed = connector.mGetBorrowed(symbol);
        Log.info("Deuda: %s", borrowed);
        CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
            Log.info("Cerrando Long...", borrowed);
            connector.fSendOrderToMkt(symbol, ActionOrden.SELL, position.quantity(), "long_sell-" + uuid);
            BigDecimal balance = connector.fGetBalance();
            Log.info("Transfiriendo %s de USDⓈ-M Futures a Spot", balance);
            connector.wTransfer(null, BinanceConnector.Transfer.FUTURE_TO_SPOT, quoteAsset, balance);
        });
        CompletableFuture<Void> m = CompletableFuture.runAsync(() -> {
            Log.info("Cerrando Short...", borrowed);
            connector.mSendOrderToMkt(symbol, ActionOrden.BUY, order.quoteAmount(), "short_buy-" + uuid, false);
            Log.info("<green>Compra realizada de %s", baseAsset);
        });
        f.join();
        m.join();
        BinanceConnector.BalanceInsolated balanceInsolated = connector.mGetBalance(symbol);
        if (balanceInsolated.base().free().compareTo(borrowed) >= 0) {
            Log.info("Pagando el préstamo", baseAsset);
            connector.mRepay(symbol, baseAsset, borrowed);
            BigDecimal delta = balanceInsolated.base().free().subtract(borrowed);
            Log.info("<green>Préstamo pagado", baseAsset);
            Log.info("Transfiriendo %s %s de Margen Aislado a Spot", delta, baseAsset);
            connector.wTransfer(symbol, BinanceConnector.Transfer.ISOLATED_TO_MARGIN, baseAsset, delta);
            connector.wTransfer(null, BinanceConnector.Transfer.MARGIN_TO_SPOT, baseAsset, delta);
            Log.info("Convirtiendo de %s a %s", baseAsset, quoteAsset);
            String id = connector.cConvert(baseAsset, quoteAsset, delta, true);
            connector.cAccept(id);
        }else {
            BigDecimal delta = borrowed.subtract(balanceInsolated.base().free());
            // Se convierte
            Log.info("Pagando el préstamo", baseAsset);
            String id = connector.cConvert(quoteAsset, baseAsset, delta, false);
            connector.cAccept(id);
            Log.info("Transfiriendo %s %s de Margen Aislado a Spot", delta, baseAsset);
            // Se transfiere lo convertido
            connector.wTransfer(null, BinanceConnector.Transfer.SPOT_TO_MARGIN, baseAsset, delta);
            connector.wTransfer(symbol, BinanceConnector.Transfer.MARGIN_TO_ISOLATED, baseAsset, delta);
            Log.info("Pagando el préstamo", baseAsset);
            connector.mRepay(symbol, baseAsset, borrowed);
            Log.info("<green>Préstamo pagado", baseAsset);
        }
        Log.info("<green>Long cerrado", borrowed);
        Log.info("<green>Short Cerrado", baseAsset, quoteAsset);
        Log.info("Fin del programa", baseAsset, quoteAsset);
    }

    public boolean checkPreStart(){
        BigDecimal sizePosition = config.getSizePosition();
        TestFunding.Result testsResults = new TestFunding().run(config);
        if (testsResults.fail() > 0 || testsResults.waring() > 0 || testsResults.weakWaring() > 0){
            if (!inputUser.inBoolean("Estas seguro de continual?")){
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
        BigDecimal fPrice = connector.fGetPrice(symbol);
        BigDecimal sPrice = connector.sGetPrice(symbol);
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
        Log.info("EntrySpred: %.3f%%", (fPrice.divide(sPrice, 12, RoundingMode.DOWN).subtract(BigDecimal.ONE)).multiply(new BigDecimal("100")));
        return false;
    }


    @Builder
    @Getter
    @Data
    public static class FundingManagerConfig {
        private BigDecimal sizePosition;
        private BigDecimal booking;
        private String baseAsset;
        private String quoteAsset;
        private boolean logsEndPoints;
    }

    public static class PersistenData {
        private final FundingManagerConfig config;
        private final Status status;
        private final boolean isActive;
        private final UUID uuid;

        public PersistenData(FundingManager manager) {
            this.config = manager.config;
            this.status = manager.status;
            this.isActive = manager.isStarted;
            this.uuid = manager.uuid;
        }
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
