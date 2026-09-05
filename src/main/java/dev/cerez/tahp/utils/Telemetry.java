package dev.cerez.tahp.utils;

import dev.cerez.tahp.connector.BaseConnector;
import dev.cerez.tahp.triangular.utils.TriangularArbitrageOpportunity;
import lombok.*;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@Data
@Getter(AccessLevel.NONE)
@Setter(AccessLevel.NONE)
public class Telemetry {

    private final TelemetryConfig config;
    private final Executor executor = Executors.newSingleThreadExecutor();

    @NotNull
    private LinkedList<Long> deltaDelayComputeNanoTimeList = new LinkedList<>();
    private LinkedList<TriangularArbitrageOpportunity> opportunities = new LinkedList<>();
    private LinkedList<TelemetryRequestConnector> requestConnectors = new LinkedList<>();
    private long totalUpdateCounter = 0;
    private int updateCounterPrev = 0;
    private int updateCounterCurrentInFrameTime = 0;

    @Setter
    private long currentDeltaDelayPingPongNanoTime = -1;

    public Telemetry(TelemetryConfig telemetryConfig) {
        this.config = telemetryConfig;
        executor.execute(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(config.timeFrameCounterUpdate));
                updateCounterPrev = updateCounterCurrentInFrameTime;
                updateCounterCurrentInFrameTime = 0;
            }
        });
    }

    public volatile boolean inSnapshot = false;

    public TelemetrySnapshot getSnapshot() {
        inSnapshot = true;
        var snapshot = new TelemetrySnapshot(
                getDeltaDelayComputeNanoTimeList(),
                getCounterInTimeFrame(),
                totalUpdateCounter,
                getDelayDeltaPingPongMs(),
                requestConnectors
        );
        inSnapshot = false;
        return snapshot;
    }

    private int stepAddDeltaDelayComputeNanoTime = 0;

    public synchronized void addDeltaDelayComputeNanoTime(long deltaNanoTime) {
        if (inSnapshot) {
            return;
        }
        if (0 != (stepAddDeltaDelayComputeNanoTime++ % config.stepsAddDelayComputeNanoTime)) {
            return;
        }
        if (deltaDelayComputeNanoTimeList.size() > config.maxDelaysDeltaComputeNanoTime){
            deltaDelayComputeNanoTimeList.removeLast();
        }
        deltaDelayComputeNanoTimeList.addFirst(deltaNanoTime);
    }

    public synchronized void incrementUpdateCounter(){
        if (inSnapshot) {
            return;
        }
        totalUpdateCounter++;
        updateCounterCurrentInFrameTime++;
    }

    public void addOpportunities(Collection<TriangularArbitrageOpportunity> onOpportunities) {
        if (config.mode != Mode.FULL){
            return;
        }
        if (inSnapshot) {
            return;
        }
        opportunities.addAll(onOpportunities);
    }

    public void addRequestConnector(BaseConnector.Method method, String request) {
        if (config.mode != Mode.FULL){
            return;
        }
        if (inSnapshot) {
            return;
        }
        requestConnectors.add(new TelemetryRequestConnector(method, request));
    }

    //////////////////
    //////////////////

    @Contract(pure = true)
    private double getDeltaDelayComputeNanoTimeList() {
        if (deltaDelayComputeNanoTimeList.isEmpty()){
            return 0;
        }
        return deltaDelayComputeNanoTimeList.stream().mapToLong(Long::longValue).average().orElseThrow();
    }
    
    @Contract(pure = true)
    private double getCounterInTimeFrame(){
        return (double) (updateCounterPrev * 1000) / (double) (config.timeFrameCounterUpdate);
    }

    @Contract(pure = true)
    private float getDelayDeltaPingPongMs() {
        return currentDeltaDelayPingPongNanoTime / 1_000_000f;
    }

    @Data
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static class TelemetrySnapshot{
        private final double deltaDelayComputeNanoTime;
        private final double updateInOneSecond;
        private final long totalCountUpdates;
        private final float delayPingPongMs;
        private final List<TelemetryRequestConnector> requestConnector;
    }

    @Data
    @Builder
    public static class TelemetryConfig{
        private int maxDelaysDeltaComputeNanoTime;
        private int stepsAddDelayComputeNanoTime;
        @Builder.Default private int timeFrameCounterUpdate = 200;
        @Builder.Default private Mode mode = Mode.FULL;
    }

    public record TelemetryRequestConnector(BaseConnector.Method method, String request) {}

    public enum Mode{
        FULL,
        MINIMAL
    }
}
