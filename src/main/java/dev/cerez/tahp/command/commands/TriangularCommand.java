package dev.cerez.tahp.command.commands;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.command.BaseCommand;
import dev.cerez.tahp.connector.Connector;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.triangular.engine.EngineManager;
import dev.cerez.tahp.triangular.engine.SearchTriangularEngine;
import dev.cerez.tahp.triangular.engine.engines.SearchTriangularEngineJava;
import dev.cerez.tahp.triangular.utils.ExecutorCycles;
import dev.cerez.tahp.triangular.utils.Loader;
import dev.cerez.tahp.triangular.utils.Telemetry;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

public class TriangularCommand extends BaseCommand {
    public TriangularCommand() {
        super("triangular");
    }

    @Override
    public void execute(@NotNull List<String> args) {
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
