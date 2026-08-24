package dev.cerez.tahp.fuding;

import dev.cerez.tahp.connector.connectors.BinanceConnector;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Blocking;

import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

@RequiredArgsConstructor
public class BlockerForSpread {

    private final BinanceConnector binanceConnector;
    private final String symbol;

    @Blocking
    public void waitingEntrySpred(double target) {
        try {
            double currentSpread = -1;
            while (target <= currentSpread) {
                // TODO: Usar bid y ask
                Double sPrice = CompletableFuture.supplyAsync(() -> binanceConnector.sGetPrice(symbol)).get();
                Double fPrice = CompletableFuture.supplyAsync(() -> binanceConnector.fGetPrice(symbol)).get();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
                currentSpread = (fPrice / sPrice) - 1;
            }
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}
