package dev.cerez.tahp.command.commands;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.command.BaseCommand;
import dev.cerez.tahp.fuding.FundingManager;
import dev.cerez.tahp.fuding.TestFunding;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class CheckFundingCommand extends BaseCommand {

    public CheckFundingCommand() {
        super("check");
    }

    @Override
    public void execute(@NotNull List<String> args) {
        if (args.isEmpty()) {
            Log.error("No arguments supplied");
            return;
        }

        if (args.size() < 2) {
            return;
        }
        FundingManager.FundingManagerConfig config = FundingManager.FundingManagerConfig.builder()
                .sizePosition(12)
                .booking(0.1d)
                .baseAsset("ONG")
                .quoteAsset("USDT")
                .entrySpread(0.1)
                .exitSpread(0.1)
                .logsEndPoints(true)
                .build();
        String baseAsset = args.getFirst();
        String quotAsset = args.get(1);
        Log.info("Checking funding for symbol: " + baseAsset + quotAsset);
        new TestFunding().run(config);
    }
}
