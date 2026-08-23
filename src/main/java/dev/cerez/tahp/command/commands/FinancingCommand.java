package dev.cerez.tahp.command.commands;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.command.BaseCommand;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public class FinancingCommand extends BaseCommand {
    public FinancingCommand() {
        super("financing");
    }

    private final static double SIZE_POSITION = 10;

    @SuppressWarnings("resource")
    @Override
    public void execute(@NotNull List<String> args) {
        BinanceConnector connector = new BinanceConnector(false);
        Map<String, Double> balance = connector.getBalance();
        double usdt = balance.getOrDefault("USDT", 0.0);
        if (usdt < 10){
            Log.warning("USDT is less than 10, you have %.3f", usdt);
            return;
        }

    }
}
