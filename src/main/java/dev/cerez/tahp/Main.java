package dev.cerez.tahp;

import dev.cerez.tahp.connector.ApiException;
import dev.cerez.tahp.connector.Connector;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.connector.model.OrderResult;
import dev.cerez.tahp.engine.SearchTriangularEngine;
import dev.cerez.tahp.engine.engines.SearchTriangularEngineJava;
import dev.cerez.tahp.model.Action;
import dev.cerez.tahp.connector.model.Symbol;
import dev.cerez.tahp.model.TriangularArbitrageOpportunity;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class Main {

    public static void main(String[] args) {
        Runner.RunnerConfig config = new Runner.RunnerConfig.RunnerConfigBuilder()
//                .useDelay(true)
//                .delay(100L)
                .maxLag(20L)
                .minProfit(0.1d)
                .build();

        FactoryThreadWebSocket factory = new FactoryThreadWebSocket();
        ExecutorService streamExecutor = Executors.newThreadPerTaskExecutor(factory);
        Connector connector = new BinanceConnector(EngineManager.IS_TESTNET, streamExecutor);

        Runner runner = new Runner(config, connector);
        Loader loader = new Loader();

        new EngineManager(connector, (onOpportunities) -> {
            runner.onOpportunities(onOpportunities);
            loader.addCounter();
        }).setEngine(new SearchTriangularEngineJava()).start();
        loader.printLoader();
    }

    @RequiredArgsConstructor
    public static class Loader{
        private final AtomicInteger counterUpdate = new AtomicInteger(0);

        public void addCounter(){
            counterUpdate.incrementAndGet();
        }

        @Getter
        private final String labelRaw = " %du/s";

        public void printLoader(){
            int counterLoader = 0;
            while(!Thread.interrupted()){

                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
                String label = labelRaw.formatted(counterUpdate.get()*4);

                if (counterUpdate.get() == 0){
                    System.out.print("\rPause...\r");
                    continue;
                }

                switch(counterLoader%25) {
                    case 0  -> System.out.print("\r⠁ " + label + "\r");
                    case 1  -> System.out.print("\r⠂ " + label + "\r");
                    case 2  -> System.out.print("\r⠄ " + label + "\r");
                    case 3  -> System.out.print("\r⡀ " + label + "\r");
                    case 4  -> System.out.print("\r⢀ " + label + "\r");
                    case 5  -> System.out.print("\r⠠ " + label + "\r");
                    case 6  -> System.out.print("\r⠐ " + label + "\r");
                    case 7  -> System.out.print("\r⠈ " + label + "\r");
                    case 9  -> System.out.print("\r⠁ " + label + "\r");
                    case 10 -> System.out.print("\r⠃ " + label + "\r");
                    case 11 -> System.out.print("\r⠇ " + label + "\r");
                    case 12 -> System.out.print("\r⡇ " + label + "\r");
                    case 13 -> System.out.print("\r⣇ " + label + "\r");
                    case 14 -> System.out.print("\r⣧ " + label + "\r");
                    case 15 -> System.out.print("\r⣷ " + label + "\r");
                    case 16 -> System.out.print("\r⣿ " + label + "\r");
                    case 17 -> System.out.print("\r⣾ " + label + "\r");
                    case 18 -> System.out.print("\r⣼ " + label + "\r");
                    case 19 -> System.out.print("\r⣸ " + label + "\r");
                    case 20 -> System.out.print("\r⢸ " + label + "\r");
                    case 21 -> System.out.print("\r⠸ " + label + "\r");
                    case 22 -> System.out.print("\r⠸ " + label + "\r");
                    case 23 -> System.out.print("\r⠘ " + label + "\r");
                    case 24 -> System.out.print("\r⠈ " + label + "\r");
                }
                counterUpdate.set(0);
                counterLoader++;
            }
        }
    }


    public static class Runner {

        private volatile TriangularArbitrageOpportunity currentOpportunity;
        private volatile CompletableFuture<Object> runLoop = CompletableFuture.completedFuture(new Object());
        private volatile long nanoTimeStartCycles = 0;
        private double pnl;

        private final @NotNull DecimalFormat decimalFormat = new DecimalFormat("0.00#######");
        private final @NotNull Set<TriangularArbitrageOpportunity> opportunityWindows = new HashSet<>();
        private final @NotNull Executor executor = Executors.newFixedThreadPool(4);
        private final @NotNull HashMap<String, Symbol> symbolsByName;
        private final @NotNull Connector connector;
        private final @NotNull RunnerConfig config;

        public Runner(@NotNull RunnerConfig config,  @NotNull Connector connector) {
            HashMap<String, Symbol> symbolsByName = new HashMap<>();
            for (Symbol symbolConfigurable : connector.getExchangeInfo().symbols().values()) {
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

            long opportunityNanoTime = onOpportunities.nanoTime();
            if (currentOpportunity != null && !current.contains(currentOpportunity)) {
                long currentNanoTime = System.nanoTime();
                Log.info("<red>Cierre del bucle (Termino Ventana). Duración: %.2fms (Lag: %.2fms, Ticks: %d)",
                        (currentNanoTime - nanoTimeStartCycles) / 1_000_000d,
                        (currentNanoTime - opportunityNanoTime) / 1_000_000d,
                        currentOpportunity.getLifeTime().getTicks()
                );
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
                    if (edge.getFromAsset().hashPrimitive == SearchTriangularEngine.PREFERRED_START_ASSET) {
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
                    rotatedAssets.add(rotatedEdges.getFirst().getFromAsset().asset);
                    for (SearchTriangularEngine.ArbitrageEdge edge : rotatedEdges) {
                        rotatedAssets.add(edge.getToAsset().asset);
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
                    try {
                        while (currentOpportunity != null) {

                            if (currentOpportunity == null) {
                                return new Object();
                            }
                            List<SearchTriangularEngine.ArbitrageEdge> edges = new ArrayList<>(currentOpportunity.getEdges());

                            double balance = SearchTriangularEngine.DEFAULT_START_AMOUNT;
                            for (SearchTriangularEngine.ArbitrageEdge edge : edges) {
                                Log.info("Ejecutando: %s %s", edge.getSymbol(), (edge.getAction().equals(Action.SELL) ? "<red>SELL" : "<green>BUY") + "<reset>");
                                Symbol symbol = symbolsByName.get(edge.getSymbol());
                                if (symbol == null) {
                                    Log.warning("Símbolo no soportado en este entorno: " + edge.getSymbol());
                                    continue;
                                }

                                try {
                                    OrderResult orderResult = connector.placeMarketOrder(
                                            symbol,
                                            edge.getAction(),
                                            balance,
                                            edge.getAction() == Action.SELL
                                    );
                                    Log.info(balance + " @ " + edge.getFromAsset().asset + " -> " + orderResult.receivedQty() + " @ " + edge.getToAsset().asset);

                                    balance = orderResult.receivedQty();
                                    if (edge.getToAsset().hashPrimitive == SearchTriangularEngine.PREFERRED_START_ASSET) {
                                        pnl += balance-SearchTriangularEngine.DEFAULT_START_AMOUNT;
                                        String balanceString = (balance > SearchTriangularEngine.DEFAULT_START_AMOUNT ?
                                                "<green>Ganado: " + " +" +decimalFormat.format(balance-SearchTriangularEngine.DEFAULT_START_AMOUNT) :
                                                "<red>Perdido: " + " " + decimalFormat.format(balance-SearchTriangularEngine.DEFAULT_START_AMOUNT)) +
                                                " USDT<reset>";
                                        String pnlString = (pnl > 0 ?
                                                "<green>PNL: " + " +" +decimalFormat.format(pnl) :
                                                "<red>PNL: " + " " + decimalFormat.format(pnl)) +
                                                " USDT<reset>";
                                        Log.info(balanceString + " " + pnlString);
                                    }
                                    if (balance < SearchTriangularEngine.DEFAULT_START_AMOUNT){
                                        currentOpportunity = null;
                                    }

                                    if (edge.getToAsset().asset.equals("BNB")) {
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
                        (edge.getAction().equals(Action.BUY) ? "<green>BUY" : "<red>SELL") + "<reset>",
                        edge.getFromAsset().asset + "/" + edge.getToAsset().asset,
                        edge.getSymbol(),
                        edge.getReferencePrice(),
                        edge.getRate(),
                        edge.getWeight()
                );
            }
        }

        @Builder
        @Getter
        public static class RunnerConfig{
            private long delay;
            private boolean useDelay;
            private double minProfit;
            private double maxLag = -1;
        }
    }

    private static class FactoryThreadWebSocket implements ThreadFactory {

        private int i = 0;

        @Override
        public Thread newThread(@NotNull Runnable r){
            Thread t = new Thread(r);
            t.setName("stream-webSocket-" + i);
            i++;
            return t;
        }
    }
}