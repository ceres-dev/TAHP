package dev.cerez.tahp.triangular.utils;

import dev.cerez.tahp.utils.Telemetry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

@RequiredArgsConstructor
public class Loader{

    private final AtomicInteger counterUpdate = new AtomicInteger(0);

    @Getter
    private final String labelRaw = " %.0fu/s Computo: %.2fms Ping: %.2fms ";

    @SuppressWarnings("DuplicateBranchesInSwitch")
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
            counterLoader++;
        }
    }
}