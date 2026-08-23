package dev.cerez.tahp;

import dev.cerez.tahp.connector.Connector;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.connector.connectors.GateConnector;
import dev.cerez.tahp.connector.connectors.KuCoinConnector;
import dev.cerez.tahp.engine.EngineManager;
import dev.cerez.tahp.engine.SearchTriangularEngine;
import dev.cerez.tahp.engine.engines.SearchTriangularEngineJava;
import dev.cerez.tahp.utils.ExecutorCycles;
import dev.cerez.tahp.utils.Loader;
import dev.cerez.tahp.utils.Telemetry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class Main {

    // No hacer no conectores en:
    // crypto.com
    // okx (Muy difícil)

    public static void main(String[] args) {
        Log.info("Starting...");
        long startTime = System.currentTimeMillis();
        int maxSymbols = 1000;
        ExecutorCycles.ExecutorCyclesConfig configExecutor = ExecutorCycles.ExecutorCyclesConfig.builder()
                .maxLag(20L)
                .minProfit(0.1d)
                .build();
        SearchTriangularEngine.EngineConfig engineConfig = SearchTriangularEngine.EngineConfig.builder()
                .maxSymbols(maxSymbols)
                .maxCycleLength(5)
                .minCycleLength(4)
                .build();
        Telemetry.TelemetryConfig telemetryConfig = Telemetry.TelemetryConfig.builder()
                .maxDelaysDeltaComputeNanoTime(500)
                .stepsAddDelayComputeNanoTime(10)
                .build();
        EngineManager.ManagerConfig managerConfig = EngineManager.ManagerConfig.builder()
                .maxSymbols(maxSymbols)
                .banAssets(Set.of("TRY"))
                .build();

        Connector connector =           new BinanceConnector(false);
        Telemetry telemetry =           new Telemetry(telemetryConfig);
        Loader loader =                 new Loader();
        ExecutorCycles executorCycles = new ExecutorCycles(configExecutor, connector);
        SearchTriangularEngine engine = new SearchTriangularEngineJava(engineConfig);

        connector.setTelemetry(telemetry);
        new EngineManager(connector, managerConfig, executorCycles::onOpportunities).setEngine(engine).setTelemetry(telemetry).start();
        Log.info("<green>Ready! %.2fs", (System.currentTimeMillis() - startTime)/1000d);
        loader.printLoader(telemetry);
    }
}