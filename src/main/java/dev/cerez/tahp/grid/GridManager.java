package dev.cerez.tahp.grid;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.connector.connectors.exception.PostOnlyRejectException;
import dev.cerez.tahp.connector.exception.UnknownOrderException;
import dev.cerez.tahp.connector.model.SideOrder;
import dev.cerez.tahp.connector.model.StatusOrder;
import dev.cerez.tahp.connector.model.Symbol;
import dev.cerez.tahp.discord.StatusProfiler;
import dev.cerez.tahp.utils.Switch;
import dev.cerez.tahp.utils.Utils;
import lombok.Builder;
import lombok.Setter;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class GridManager implements Switch, StatusProfiler {

    private final BinanceConnector connector = new BinanceConnector();

    private final GridManagerConfig config;
    private final String symbol;

    private boolean isStarted = false;
    private volatile boolean onUpdate = false;
    @Nullable
    private BinanceConnector.FutureOrder lastOrderFilled = null;

    public GridManager(GridManagerConfig config) {
        this.config = config;
        this.symbol = config.baseAsset + config.quoteAsset;

        connector.start();
        connector.setLogEndpoint(config.logsEndPoints);
    }

    @Override
    public @NotNull StatusProfiler.PresenceProfile getPresenceProfile() {
        BigDecimal balance = connector.fGetBalanceTotal().get(config.quoteAsset);
        BigDecimal unPnl = connector.fGetUnPNL().get(config.quoteAsset);
        String label = "Bal: %.2f PNL: %.4f Sy: %s".formatted(balance, unPnl, symbol);
        return new PresenceProfile(
                OnlineStatus.ONLINE,
                Activity.of(Activity.ActivityType.PLAYING, label)
        );
    }

    @Override
    public void start() {
        isStarted = true;
        Log.info("Iniciando...");

        connector.fGetAllSymbols();
        connector.fSetLeverage(symbol, config.leverage);
        connector.initWebSocket(connector.uGetWWS());

        Log.info("Balance disponible %.4f %s", connector.fGetBalance().get(config.quoteAsset), config.quoteAsset);
        updateGrid();
        connector.uEventOrderTradeUpdate(payload -> {
            if (!onUpdate) {
                if (payload.get("o").get("x").asText().equals("FILLED")){
                    // Esperar que la caché de binance caduque
                    LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
                }
                updateGrid();
            }
        });
    }

    @Override
    public void stop() {
        isStarted = false;
        connector.fCloseUserData();
        connector.fCancelOrderAll(symbol);
    }

    public synchronized void updateGrid() {
        onUpdate = true;
        CompletableFuture<BinanceConnector.FuturePosition> positionFuture = CompletableFuture.supplyAsync(() -> connector.fGetPosition(symbol));
        CompletableFuture<BigDecimal> currentPriceFuture = CompletableFuture.supplyAsync(() -> connector.fGetPrice(symbol));
        CompletableFuture<List<BinanceConnector.FutureOrder>> ordersFuture = CompletableFuture.supplyAsync(() -> connector.fGetAllOrder(symbol));
        CompletableFuture<BigDecimal> balanceFuture = CompletableFuture.supplyAsync(() -> connector.fGetBalanceTotal().getOrDefault(config.quoteAsset, BigDecimal.ZERO));

        BinanceConnector.FuturePosition position = positionFuture.join();
        BigDecimal currentPrice = currentPriceFuture.join();
        List<BinanceConnector.FutureOrder> orders = ordersFuture.join();
        BigDecimal balance = balanceFuture.join();

        List<BinanceConnector.FutureOrder> ordersActive = orders.stream().filter(order -> StatusOrder.NEW.equals(order.statusOrder())).toList();;
        lastOrderFilled = orders.stream().filter(order -> StatusOrder.FILLED.equals(order.statusOrder())).max(Comparator.comparingLong(BinanceConnector.FutureOrder::dateFilled)).orElse(null);

        BigDecimal positionQuantity = position == null
                        ? BigDecimal.ZERO
                        : position.quantity();

        BigDecimal longPosition = positionQuantity.signum() > 0
                        ? positionQuantity
                        : BigDecimal.ZERO;

        BigDecimal shortPosition = positionQuantity.signum() < 0
                        ? positionQuantity.abs()
                        : BigDecimal.ZERO;


        BigDecimal availableBalance = balance.subtract(positionQuantity.multiply(currentPrice).divide(new BigDecimal(config.leverage), 12 , RoundingMode.HALF_EVEN));
        List<OrderPreview> desiredOrders = createDesiredOrders(currentPrice, availableBalance, longPosition, shortPosition);
        reconcileOrders(ordersActive, desiredOrders);
        onUpdate = false;
    }

    private @NotNull List<OrderPreview> createDesiredOrders(@NotNull BigDecimal currentPrice, @NotNull BigDecimal availableQuote, @NotNull BigDecimal longPosition, @NotNull BigDecimal shortPosition) {
        List<OrderPreview> result = new ArrayList<>();

        if (config.typeGrid == TypeGrid.LONG || config.typeGrid == TypeGrid.BOTH) {
            result.addAll(createReduceOrders(currentPrice, longPosition, SideOrder.SELL));
            result.addAll(createOpenOrders(currentPrice, availableQuote, SideOrder.BUY));
        }

        if (config.typeGrid == TypeGrid.SHORT || config.typeGrid == TypeGrid.BOTH) {
            result.addAll(createReduceOrders(currentPrice, shortPosition, SideOrder.BUY));
            result.addAll(createOpenOrders(currentPrice, availableQuote, SideOrder.SELL));
        }

        return result;
    }

    private @NotNull List<OrderPreview> createReduceOrders(@NotNull BigDecimal currentPrice, @NotNull BigDecimal positionQuantity, @NotNull SideOrder side) {
        List<OrderPreview> result = new ArrayList<>();
        if (positionQuantity.signum() <= 0) {
            return result;
        }
        int amountOrders = positionQuantity.divide(config.sizePerOrderBaseAsset, 0, RoundingMode.DOWN).intValue();
        if (amountOrders <= 0) {
            return result;
        }
        boolean isLong = side == SideOrder.BUY;
        int direction = isLong ? -1 : 1;
        Symbol symbols = connector.fGetAllSymbols().get(symbol);
        for (int i = 1; i <= amountOrders; i++) {
            BigDecimal price = gridPrice(
                    currentPrice,
                    config.stepSize,
                    direction * i + (isLong ? 1 : 0)
            ).add(isLong ? BigDecimal.ZERO : symbols.getPriceStepSize());
            if (lastOrderFilled != null && lastOrderFilled.price().compareTo(price) == 0) {
                amountOrders++;
                continue;
            }
            result.add(new OrderPreview(price, side, config.sizePerOrderBaseAsset, true));
        }

        return result;
    }

    private @NotNull List<OrderPreview> createOpenOrders(@NotNull BigDecimal currentPrice, @NotNull BigDecimal availableQuote, @NotNull SideOrder side) {
        List<OrderPreview> result = new ArrayList<>();
        if (availableQuote.signum() <= 0) {
            return result;
        }
        BigDecimal leverage = BigDecimal.valueOf(config.leverage);
        boolean isLong = side == SideOrder.BUY;
        int direction = isLong ? -1 : 1;
        BigDecimal usedMargin = BigDecimal.ZERO;
        Symbol symbols = connector.fGetAllSymbols().get(symbol);
        for (int i = 1; ; i++) {
            BigDecimal price = gridPrice(
                    currentPrice,
                    config.stepSize,
                    direction * i + (side == SideOrder.BUY ? 1 : 0)
            ).add(isLong ? BigDecimal.ZERO : symbols.getPriceStepSize());
            if (lastOrderFilled != null && lastOrderFilled.price().compareTo(price) == 0 && lastOrderFilled.sideOrder() == side) continue;
            BigDecimal notional = config.sizePerOrderBaseAsset.multiply(price);
            BigDecimal orderMargin = notional.divide(leverage, 12, RoundingMode.CEILING);
            BigDecimal newUsedMargin = usedMargin.add(orderMargin);

            if (newUsedMargin.compareTo(availableQuote) > 0) {
                break;
            }
            usedMargin = newUsedMargin;

            result.add(new OrderPreview(price, side, config.sizePerOrderBaseAsset, false));
        }

        return result;
    }

    private void reconcileOrders(@NotNull List<BinanceConnector.FutureOrder> currentOrders, @NotNull List<OrderPreview> desiredOrders) {
        Set<String> keptOrders = new HashSet<>();
        List<BinanceConnector.FutureOrder> ordersToCancel = new ArrayList<>();
        List<OrderPreview> ordersToCreate = new ArrayList<>(desiredOrders);

        for (OrderPreview desired : desiredOrders) {
            BinanceConnector.FutureOrder matched = null;
            for (BinanceConnector.FutureOrder current : currentOrders) {
                if (keptOrders.contains(current.nameOrder())) {
                    continue;
                }
                if (sameOrder(desired, current)) {
                    matched = current;
                    break;
                }
            }

            if (matched != null) {
                keptOrders.add(matched.nameOrder());
                ordersToCreate.remove(desired);
            }
        }

        for (BinanceConnector.FutureOrder current : currentOrders)
            if (!keptOrders.contains(current.nameOrder())) {
                ordersToCancel.add(current);
            }
        for (BinanceConnector.FutureOrder order : ordersToCancel) {
            try {
                connector.fCancelOrder(symbol, order.nameOrder());
                Log.info("Orden Cancelada: %s", order.nameOrder());
            } catch (UnknownOrderException e) {
                Log.info("La orden ya no existe: %s", order.nameOrder());
            }
        }

        boolean retry = false;
        for (OrderPreview order : ordersToCreate) {
            String clientOrderId = Utils.uuidToBase36(UUID.randomUUID());
            try {
                connector.fSendOrderToLimit(symbol,
                        order.sideOrder(),
                        order.amountBaseAsset(),
                        clientOrderId,
                        order.price(),
                        order.reduceOnly()
                );
            } catch (PostOnlyRejectException e) {
                retry = true;
            }

            Log.info(
                    "Orden enviada %s<reset> @ %.4f qty: %.4f %s reduceOnly=%s",
                    order.sideOrder() == SideOrder.BUY ? "<green>BUY" : "<red>SELL",
                    order.price(), order.amountBaseAsset(), clientOrderId, order.reduceOnly()
            );
        }
        if (retry) updateGrid();
    }

    private boolean sameOrder(@NotNull OrderPreview preview, @NotNull BinanceConnector.FutureOrder order) {
        return preview.sideOrder() == order.sideOrder() &&
                preview.amountBaseAsset().compareTo(order.amountBaseAsset()) == 0 &&
                preview.price().compareTo(order.price()) == 0 &&
                preview.reduceOnly() == order.reduceOnly();
    }


    private static @NotNull BigDecimal gridPrice(@NotNull BigDecimal currentPrice, @NotNull BigDecimal stepSize,int level) {
        BigDecimal base = currentPrice.divide(stepSize, 0, RoundingMode.FLOOR).multiply(stepSize);
        return base.add(stepSize.multiply( BigDecimal.valueOf(level)));
    }

    private record OrderPreview(
            BigDecimal price,
            SideOrder sideOrder,
            BigDecimal amountBaseAsset,
            boolean reduceOnly
    ) {}

    @Builder
    @Setter
    public static class GridManagerConfig {
        private String baseAsset;
        private String quoteAsset;
        private BigDecimal stepSize;
        private BigDecimal sizePerOrderBaseAsset;
        private TypeGrid typeGrid;
        private int leverage;
        private boolean logsEndPoints;
//        private int amountStepSizePrice;
    }

    public enum TypeGrid {
        LONG,
        SHORT,
        BOTH
    }
}