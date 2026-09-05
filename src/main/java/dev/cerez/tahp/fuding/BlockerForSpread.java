package dev.cerez.tahp.fuding;

import dev.cerez.tahp.connector.connectors.BinanceConnector;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Blocking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * @author chatGPT
 */

@RequiredArgsConstructor
public class BlockerForSpread {

    private final BinanceConnector connector;
    private final String symbol;

    @Getter
    private volatile BigDecimal currentSpreadEntry = BigDecimal.ZERO;
    @Getter
    private volatile BigDecimal currentSpreadExit = BigDecimal.ZERO;

    @Blocking
    public void waitEntrySpred(BigDecimal target) {
        startWebSockets();
        waitSpread(target, true);
    }

    @Blocking
    public void waitExitSpread(BigDecimal target) {
        startWebSockets();
        waitSpread(target, false);
    }

    private void startWebSockets() {
        connector.initWebSocket(connector.fGetWWS());
        connector.initWebSocket(connector.getWWS());
    }

    private void waitSpread(BigDecimal target, boolean entry) {
        final Thread currentThread = Thread.currentThread();

        final AtomicReference<BinanceConnector.BookTick> futuresTick = new AtomicReference<>();
        final AtomicReference<BinanceConnector.BookTick> spotTick = new AtomicReference<>();
        final AtomicBoolean reached = new AtomicBoolean(false);

        final Consumer<BinanceConnector.BookTick> futuresListener = tick -> {
            futuresTick.set(tick);
            checkSpread(
                    futuresTick.get(),
                    spotTick.get(),
                    target,
                    entry,
                    reached,
                    currentThread
            );
        };

        final Consumer<BinanceConnector.BookTick> spotListener = tick -> {
            spotTick.set(tick);
            checkSpread(
                    futuresTick.get(),
                    spotTick.get(),
                    target,
                    entry,
                    reached,
                    currentThread
            );
        };

        try {
            /*
             * Es importante crear ambas suscripciones antes de bloquear.
             */
            connector.wfCreateBookTicker(futuresListener,symbol);
            connector.wsCreateBookTicker(spotListener,symbol);

            /*
             * Puede que el spread ya cumpla el objetivo antes
             * de que park() se ejecute. LockSupport conserva el
             * "permiso" del unpark(), por lo que no se pierde.
             */
            while (!reached.get()) {
                LockSupport.park(this);
            }
        } finally {
            connector.wsRemoveBookTicker(symbol);
            connector.wfRemoveBookTicker(symbol);
            connector.stop();
        }
    }

    private void checkSpread(
            BinanceConnector.BookTick futures,
            BinanceConnector.BookTick spot,
            BigDecimal target,
            boolean entry,
            AtomicBoolean reached,
            Thread thread
    ) {
        if (futures == null || spot == null || reached.get()) {
            return;
        }

        BigDecimal spread;

        if (entry) {
            /*
             * Abrir:
             *
             * Spot  -> SELL -> BID
             * Future -> BUY -> ASK
             *
             * Spread = FutureAsk / SpotBid - 1
             */
            if (spot.bidPrice().signum() <= 0) {
                return;
            }

            spread = futures.askPrice()
                    .divide(spot.bidPrice(), 12, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE);

        } else {
            /*
             * Cerrar:
             *
             * Future -> SELL -> BID
             * Spot   -> BUY  -> ASK
             *
             * Spread = FutureBid / SpotAsk - 1
             */
            if (spot.askPrice().signum() <= 0) {
                return;
            }

            spread = futures.bidPrice()
                    .divide(spot.askPrice(), 12, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE);
        }
        if (entry) {
            setCurrentSpreadEntry(spread);
        }else {
            setCurrentSpreadExit(spread);
        }
        boolean condition = entry
                ? spread.compareTo(target) >= 0
                : spread.compareTo(target) <= 0;

        if (condition && reached.compareAndSet(false, true)) {
            LockSupport.unpark(thread);
        }
    }

    private synchronized void setCurrentSpreadEntry(BigDecimal currentSpreadEntry) {
        this.currentSpreadEntry = currentSpreadEntry;
    }

    private synchronized void setCurrentSpreadExit(BigDecimal currentSpreadExit) {
        this.currentSpreadExit = currentSpreadExit;
    }
}
