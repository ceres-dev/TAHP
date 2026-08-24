package dev.cerez.tahp.command.commands;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.command.BaseCommand;
import dev.cerez.tahp.command.InputBlocking;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.fuding.BalancePreview;
import dev.cerez.tahp.fuding.TestFunding;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public class FinancingCommand extends BaseCommand {
    public FinancingCommand() {
        super("financing");
    }

    private final static double SIZE_POSITION = 12;

    @SuppressWarnings(value = "resource")
    @Override
    public void execute(@NotNull List<String> args) {
        if (args.isEmpty()) {
            Log.error("No arguments supplied");
            return;
        }
        if (args.size() < 2) {
            return;
        }
        String baseAsset = args.getFirst();
        String quotAsset = args.get(1);
    }
}
