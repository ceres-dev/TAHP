package dev.cerez.tahp.command.commands;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.command.BaseCommand;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import org.jetbrains.annotations.NotNull;

import java.util.Comparator;
import java.util.List;

public class searchBestCommand extends BaseCommand {
    public searchBestCommand() {
        super("searchBest");
    }

    @SuppressWarnings("resource")
    @Override
    public void execute(@NotNull List<String> args) {
        BinanceConnector connector = new BinanceConnector(false);
        StringBuilder stringBuilder = new StringBuilder();
        for (BinanceConnector.FundingRate fundingRate : connector.fGetFundingRate().values().stream().sorted(Comparator.comparingDouble(BinanceConnector.FundingRate::reate24hAbs)).toList()){
            stringBuilder.append("""
                    %s
                        fundingRate 24H: %.4f
                        Max: %.4f
                        Min: %.4f
                        next: %.4f
                        interval: %dh
                    """.formatted(fundingRate.symbol(), fundingRate.rate24h()*100, fundingRate.max()*100, fundingRate.min()*100, fundingRate.nextFundingRate()*100, fundingRate.interval()));
        }
        Log.info(stringBuilder.toString());
    }
}
