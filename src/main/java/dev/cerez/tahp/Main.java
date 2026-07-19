package dev.cerez.tahp;

import dev.cerez.tahp.connector.Connector;
import dev.cerez.tahp.connector.connectors.GateConnector;
import dev.cerez.tahp.engine.EngineManager;
import dev.cerez.tahp.engine.SearchTriangularEngine;
import dev.cerez.tahp.engine.engines.SearchTriangularEngineJava;
import dev.cerez.tahp.utils.ExecutorCycles;
import dev.cerez.tahp.utils.Telemetry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class Main {

    // No hacer no connectores de:
    // crypto.com
    // okx (Muy difícil)

    public static void main(String[] args) {
        Log.info("Starting...");
        long startTime = System.currentTimeMillis();
        int maxSymbols = 1500;
        ExecutorCycles.ExecutorCyclesConfig configExecutor = ExecutorCycles.ExecutorCyclesConfig.builder()
                .maxLag(20L)
                .minProfit(0.1d)
                .build();
        SearchTriangularEngine.EngineConfig engineConfig = SearchTriangularEngine.EngineConfig.builder()
                .maxSymbols(maxSymbols)
                .build();
        Telemetry.TelemetryConfig telemetryConfig = Telemetry.TelemetryConfig.builder()
                .maxDelaysDeltaComputeNanoTime(500)
                .stepsAddDelayComputeNanoTime(10)
                .build();
        EngineManager.ManagerConfig managerConfig = EngineManager.ManagerConfig.builder()
                .maxSymbols(maxSymbols)
                .banAssets(Set.of("TRY"))
                .build();

        Connector connector =           new GateConnector(false);
        Telemetry telemetry =           new Telemetry(telemetryConfig);
        Loader loader =                 new Loader();
        ExecutorCycles executorCycles = new ExecutorCycles(configExecutor, connector);
        SearchTriangularEngine engine = new SearchTriangularEngineJava(engineConfig);

        connector.setTelemetry(telemetry);
        new EngineManager(connector, managerConfig, (onOpportunities) -> {
            executorCycles.onOpportunities(onOpportunities);
            loader.addCounter();
        }).setEngine(engine).setTelemetry(telemetry).start();
        Log.info("<green>Ready! %.2fs", (System.currentTimeMillis() - startTime)/1000d);
        Log.info("Total de ciclos encontrados (Cuenta los duplicados): %d", engine.getTotalCycle());
        loader.printLoader(telemetry);
    }

    @RequiredArgsConstructor
    public static class Loader{

        private final AtomicInteger counterUpdate = new AtomicInteger(0);

        public void addCounter(){
            counterUpdate.incrementAndGet();
        }

        @Getter
        private final String labelRaw = " %.0fu/s  %.2fms Computo %.2fms Ping";

        public void printLoader(Telemetry telemetry){
            int counterLoader = 0;
            while(!Thread.interrupted()){

                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
                Telemetry.TelemetrySnapshot snapshot = telemetry.getSnapshot();
                String label = labelRaw.formatted(
                        snapshot.getUpdateInOneSecond()*4d,
                        snapshot.getDeltaDelayComputeNanoTime() / 1_000_000d,
                        snapshot.getDelayPingPongMs()
                );

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
}