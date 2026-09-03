package dev.cerez.tahp.triangular;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.connector.exception.ApiException;
import dev.cerez.tahp.connector.Connector;
import dev.cerez.tahp.connector.model.ActionOrden;
import dev.cerez.tahp.connector.model.Symbol;
import dev.cerez.tahp.triangular.engine.SearchTriangularEngine;
import dev.cerez.tahp.triangular.utils.TriangularArbitrageOpportunity;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class ExecutorCycles {

    private volatile TriangularArbitrageOpportunity currentOpportunity;
    private volatile CompletableFuture<Object> runLoop = CompletableFuture.completedFuture(new Object());
    private volatile long nanoTimeStartCycles = 0;
    private double pnl;

    private final @NotNull DecimalFormat decimalFormat = new DecimalFormat("0.00#######");
    private final @NotNull Set<TriangularArbitrageOpportunity> opportunityWindows = new HashSet<>();
    private final @NotNull Executor executor = Executors.newFixedThreadPool(4);
    private final @NotNull HashMap<String, Symbol> symbolsByName;
    private final @NotNull Connector connector;
    private final @NotNull ExecutorCycles.ExecutorCyclesConfig config;

    public ExecutorCycles(@NotNull ExecutorCycles.ExecutorCyclesConfig config, @NotNull Connector connector) {
        HashMap<String, Symbol> symbolsByName = new HashMap<>();
        for (Symbol symbolConfigurable : connector.sGetAllSymbols().values()) {
            symbolsByName.put(symbolConfigurable.name(), symbolConfigurable);
        }
        this.connector = connector;
        this.config = config;
        this.symbolsByName = symbolsByName;
    }

    public synchronized void onOpportunities(SearchTriangularEngine.@NotNull OnOpportunities onOpportunities) {
        HashSet<TriangularArbitrageOpportunity> current = new HashSet<>(onOpportunities.opportunities());

        boolean changed = current.size() != opportunityWindows.size() || !current.containsAll(opportunityWindows);
        if (!changed) {
            return;
        }else {
            opportunityWindows.clear();
            opportunityWindows.addAll(current);
        }

        long opportunityNanoTime = onOpportunities.AbsoluteDelayComputeNanoTime();
        if (currentOpportunity != null && !current.contains(currentOpportunity)) {
            long currentNanoTime = System.nanoTime();
            Log.info("<red>Cierre del bucle (Termino Ventana). Duración: %.2fms (Lag: %.2fms, Ticks: %d)",
                    (currentNanoTime - nanoTimeStartCycles) / 1_000_000d,
                    (currentNanoTime - opportunityNanoTime) / 1_000_000d,
                    currentOpportunity.getLifeTime().getTicks()
            );
            currentOpportunity.getLifeTime().setCloseNanoTime(currentNanoTime);
            currentOpportunity.getLifeTime().setOpenNanoTime(nanoTimeStartCycles);
            this.currentOpportunity = null;
            return;
        }

        TriangularArbitrageOpportunity newOpportunity = null;
        for (TriangularArbitrageOpportunity opportunity : onOpportunities.opportunities()) {
            if (newOpportunity == null || opportunity.getProfitPercent() > newOpportunity.getProfitPercent()) {
                newOpportunity = opportunity;
            }
        }

        if (newOpportunity != null) {
            if (currentOpportunity != null) {
                if (newOpportunity.equals(currentOpportunity)) {
                    return;
                }

                if (this.currentOpportunity == newOpportunity &&
                        newOpportunity.getProfitPercent() < this.currentOpportunity.getProfitPercent()
                ) {
                    long currentNanoTime = System.nanoTime();
                    Log.info("<red>Cierre del bucle (El Profit cayo). Duración: %.2fms (Lag: %.2fms)",
                            (currentNanoTime - nanoTimeStartCycles) / 1_000_000d,
                            (currentNanoTime - opportunityNanoTime) / 1_000_000d

                    );
                    currentOpportunity.getLifeTime().setCloseNanoTime(currentNanoTime);
                    currentOpportunity.getLifeTime().setOpenNanoTime(nanoTimeStartCycles);
                    this.currentOpportunity = null;
                    return;
                }

                if (newOpportunity.getProfitPercent() < config.getMinProfit() ||
                        newOpportunity.getProfitPercent() < this.currentOpportunity.getProfitPercent()){
                    return;
                }
            }
            SearchTriangularEngine.ArbitrageEdge USDT = null;
            for (SearchTriangularEngine.ArbitrageEdge edge : newOpportunity.getEdges()) {
                if (config.getPreferredStartAsset().equals(edge.getFromAsset().getName())) {
                    USDT = edge;
                    break;
                }
            }
            int index = newOpportunity.getEdges().indexOf(USDT);
            List<SearchTriangularEngine.ArbitrageEdge> rotatedEdges = new ArrayList<>(newOpportunity.getEdges());
            if (index != -1) {
                Collections.rotate(rotatedEdges, -index);
            }
            List<String> rotatedAssets = new ArrayList<>(rotatedEdges.size() + 1);
            if (!rotatedEdges.isEmpty()) {
                rotatedAssets.add(rotatedEdges.getFirst().getFromAsset().getName());
                for (SearchTriangularEngine.ArbitrageEdge edge : rotatedEdges) {
                    rotatedAssets.add(edge.getToAsset().getName());
                }
            }

            if (config.getMaxLag() != -1 &&
                    !config.isUseDelay() &&
                    config.getMaxLag() < (((System.nanoTime() - opportunityNanoTime) / 1_000_000d) - config.getDelay())
            ) {
                return;
            }
            if (currentOpportunity != null) {
                long currentNanoTime = System.nanoTime();
                Log.info("<yellow>Cambio de bucle. Duración: %.2fms (Lag: %.2fms)",
                        (currentNanoTime - this.nanoTimeStartCycles) / 1_000_000d,
                        (currentNanoTime - opportunityNanoTime) / 1_000_000d
                );
            }
            printCycles(newOpportunity, opportunityNanoTime);

            this.nanoTimeStartCycles = System.nanoTime();
            this.currentOpportunity = newOpportunity.copyAndSetAssetsCycle(rotatedAssets, rotatedEdges);

            if (config.isUseDelay()){
                CompletableFuture.runAsync(() -> {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(config.getDelay()));
                    tryRunLoop();
                }, executor);
            }else {
                tryRunLoop();
            }
        }
    }

    private synchronized void tryRunLoop() {
        if (runLoop.isDone() && currentOpportunity != null) {
            runLoop = CompletableFuture.supplyAsync(() -> {
                if (config.isTest) {
                    return new Object();
                }
                try {
                    while (currentOpportunity != null) {

                        if (currentOpportunity == null) {
                            return new Object();
                        }
                        List<SearchTriangularEngine.ArbitrageEdge> edges = new ArrayList<>(currentOpportunity.getEdges());
                        double initialBalance = config.getDefaultStartAmount();
                        double balance = initialBalance;
                        for (SearchTriangularEngine.ArbitrageEdge edge : edges) {
                            Log.info("Ejecutando: %s %s", edge.getSymbol(), (edge.getActionOrden().equals(ActionOrden.SELL) ? "<red>SELL" : "<green>BUY") + "<reset>");
                            Symbol symbol = symbolsByName.get(edge.getSymbol());
                            if (symbol == null) {
                                Log.warning("Símbolo no soportado en este entorno: " + edge.getSymbol());
                                continue;
                            }

                            try {
//                                OrderResult orderResult = connector.sSendOrderToMkt(
//                                        symbol.toString(),
//                                        edge.getActionOrden(),
//                                        BigDecimal.valueOf(balance),
//                                        null,
//                                        edge.getActionOrden() != ActionOrden.SELL
//                                );
//                                Log.info(balance + " @ " + edge.getFromAsset().getName() + " -> " + orderResult.receivedQty() + " @ " + edge.getToAsset().getName());
//
//                                balance = orderResult.receivedQty();
//                                if (edge.getSymbol().equals(config.getPreferredStartAsset())) {
//                                    pnl += balance- config.getDefaultStartAmount();
//                                    String balanceString = (balance > initialBalance ?
//                                            "<green>Ganado: " + " +" +decimalFormat.format(balance-initialBalance) :
//                                            "<red>Perdido: " + " " + decimalFormat.format(balance-initialBalance)) +
//                                            " USDT<reset>";
//                                    String pnlString = (pnl > 0 ?
//                                            "<green>PNL: " + " +" +decimalFormat.format(pnl) :
//                                            "<red>PNL: " + " " + decimalFormat.format(pnl)) +
//                                            " USDT<reset>";
//                                    Log.info(balanceString + " " + pnlString);
//                                }
                                if (balance < initialBalance){
                                    currentOpportunity = null;
                                }

                                if (edge.getToAsset().getName().equals("BNB")) {
                                    balance = Math.max(0.0006, balance - 0.0006);
                                }
                            }catch (ApiException e) {
                                currentOpportunity = null;
                                Log.exception("Cancelando bucle", e);
                                break;
                            }
                        }
                    }
                }catch (Exception e){
                    Log.exception("Error al ejecutar el bucle", e);
                }
                return new Object();
            }, executor);
        }
    }

    public void printCycles(@NotNull TriangularArbitrageOpportunity opportunity, long opportunityNanoTime) {
        long nano = System.nanoTime();
        Log.info(
                "  %s | retorno %.6f | profit %.4f%% | peso %.8f | Lag: %.2fms (%dms+)",
                String.join(" -> ", opportunity.getAssetsCycle()),
                opportunity.getRateProduct(),
                opportunity.getProfitPercent(),
                opportunity.getTotalWeight(),
                (nano - opportunityNanoTime) / 1_000_000d,
                config.isUseDelay() ? config.getDelay() : 0
        );
        for (SearchTriangularEngine.ArbitrageEdge edge : opportunity.getEdges()) {
            Log.info(
                    "    %s %s via %s @ %.10f -> rate %.10f ",
                    (edge.getActionOrden().equals(ActionOrden.BUY) ? "<green>BUY" : "<red>SELL") + "<reset>",
                    edge.getFromAsset().getName() + "/" + edge.getToAsset().getName(),
                    edge.getSymbol(),
                    edge.getReferencePrice(),
                    edge.getRate(),
                    edge.getWeight()
            );
        }
    }

    @Builder
    @Getter
    public static class ExecutorCyclesConfig {
        public long delay;
        public boolean useDelay;
        public double minProfit;
        @Builder.Default public boolean isTest = true;
        @Builder.Default public double maxLag = -1;
        @Builder.Default public String preferredStartAsset = "USDT";
        @Builder.Default public double defaultStartAmount = 10d;
    }
}
