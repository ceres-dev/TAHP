package dev.cerez.tahp.command.commands;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.command.BaseCommand;
import dev.cerez.tahp.command.InputUser;
import dev.cerez.tahp.discord.DiscordConnector;
import dev.cerez.tahp.fuding.BlockerForSpread;
import dev.cerez.tahp.fuding.FundingManager;
import dev.cerez.tahp.fuding.TestFunding;
import dev.cerez.tahp.grid.GridManager;
import dev.cerez.tahp.io.IOdata;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;

@ToString
public class GridCommand extends BaseCommand {
    public GridCommand() {
        super("grid", "g");
    }

    @Override
    public void execute(@NotNull List<String> args) {
        DiscordConnector discordConnector = new DiscordConnector();

        GridManager.GridManagerConfig config = GridManager.GridManagerConfig.builder()
                .baseAsset("SPY")
                .quoteAsset("USDT")
                .stepSize(new BigDecimal("1"))
                .sizePerOrderBaseAsset(new BigDecimal("0.01"))
                .leverage(5)
                .logsEndPoints(false)
                .typeGrid(GridManager.TypeGrid.LONG)
                .build();
        GridManager manager = new GridManager(config);
        discordConnector.setStatusProfiler(manager);
        discordConnector.start();
        manager.start();
    }
}
