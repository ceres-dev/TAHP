package dev.cerez.tahp.grid;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.connector.model.SideOrder;
import dev.cerez.tahp.connector.model.StatusOrder;
import dev.cerez.tahp.discord.StatusProfiler;
import dev.cerez.tahp.utils.Switch;
import dev.cerez.tahp.utils.Utils;
import lombok.Builder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class GridManager implements Switch, StatusProfiler {

    private final BinanceConnector connector = new BinanceConnector();

    private final GridManagerConfig config;
    private final String symbol;

    private boolean isStarted = false;

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

        connector.fGetAllSymbols();
        connector.fSetLeverage(symbol, config.leverage);

        // Limpieza inicial.
        connector.fCancelOrderAll(symbol);

        Log.info("Iniciando...");
        Log.info("Balance disponible %.4f %s", connector.fGetBalance().get(config.quoteAsset), config.quoteAsset);

        while (isStarted) {
            updateGrid();
            LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(20));
        }
    }

    @Override
    public void stop() {
        isStarted = false;
    }

    public void updateGrid() {
        BinanceConnector.FuturePosition position = connector.fGetPosition(symbol);
        BigDecimal currentPrice = connector.fGetPrice(symbol);
        List<BinanceConnector.FutureOrder> orders = connector.fGetAllOrder(symbol).stream().filter(order -> StatusOrder.NEW.equals(order.statusOrder())).toList();;

        BigDecimal positionQuantity = position == null
                        ? BigDecimal.ZERO
                        : position.quantity();

        BigDecimal longPosition = positionQuantity.signum() > 0
                        ? positionQuantity
                        : BigDecimal.ZERO;

        BigDecimal shortPosition = positionQuantity.signum() < 0
                        ? positionQuantity.abs()
                        : BigDecimal.ZERO;

        BigDecimal availableQuote = connector.fGetBalanceTotal().getOrDefault(config.quoteAsset, BigDecimal.ZERO)
                        .subtract(positionQuantity.multiply(currentPrice).divide(new BigDecimal(config.leverage), 12 , RoundingMode.HALF_EVEN));
        List<OrderPreview> desiredOrders = createDesiredOrders(currentPrice, availableQuote, longPosition, shortPosition);
        reconcileOrders(orders, desiredOrders);
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
        int direction = side == SideOrder.SELL ? 1 : -1;
        for (int i = 1; i <= amountOrders; i++) {
            BigDecimal price = gridPrice(currentPrice, config.stepSize,direction * i + (side == SideOrder.BUY ? 1 : 0));
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
        int direction = side == SideOrder.BUY ? -1 : 1;
        BigDecimal usedMargin = BigDecimal.ZERO;

        for (int i = 1; ; i++) {
            BigDecimal price = gridPrice(currentPrice, config.stepSize, direction * i + (side == SideOrder.BUY ? 1 : 0));
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

    /**
     * Sincroniza las órdenes locales deseadas con Binance.
     * Si una orden:
     * - desapareció
     * - cambió precio
     * - cambió cantidad
     * - cambió side
     * - cambió reduceOnly
     * se considera inválida y se reemplaza.
     */
    private void reconcileOrders(@NotNull List<BinanceConnector.FutureOrder> currentOrders, @NotNull List<OrderPreview> desiredOrders) {
        Set<String> keptOrders = new HashSet<>();
        List<BinanceConnector.FutureOrder> ordersToCancel = new ArrayList<>();
        List<OrderPreview> ordersToCreate = new ArrayList<>(desiredOrders);

        /*
         * Buscar una orden existente para cada orden deseada.
         */
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
            connector.fCancelOrder(symbol, order.nameOrder());
            Log.info("Orden Cancelada: %s", order.nameOrder());
        }

        for (OrderPreview order : ordersToCreate) {
            boolean alreadyExists = false;
            for (BinanceConnector.FutureOrder current : currentOrders) {
                if (sameOrder(order, current)) {
                    alreadyExists = true;
                    break;
                }
            }

            if (alreadyExists) {
                continue;
            }
            String clientOrderId = Utils.uuidToBase36(UUID.randomUUID());
            connector.fSendOrderToLimit(symbol, order.sideOrder(), order.amountBaseAsset(), clientOrderId, order.price(), order.reduceOnly());

            Log.info(
                    "Orden enviada %s<reset> @ %.4f qty: %.4f %s reduceOnly=%s",
                    order.sideOrder() == SideOrder.BUY ? "<green>BUY" : "<red>SELL",
                    order.price(), order.amountBaseAsset(), clientOrderId, order.reduceOnly()
            );
        }
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
    public static class GridManagerConfig {
        private final String baseAsset;
        private final String quoteAsset;
        private final BigDecimal stepSize;
        private final BigDecimal sizePerOrderBaseAsset;
        private final TypeGrid typeGrid;
        private final int leverage;
        private final boolean logsEndPoints;
    }

    public enum TypeGrid {
        LONG,
        SHORT,
        BOTH
    }
}