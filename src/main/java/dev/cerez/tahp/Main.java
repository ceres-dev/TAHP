package dev.cerez.tahp;

import dev.cerez.tahp.command.CommandHander;
import dev.cerez.tahp.command.commands.CheckFundingCommand;
import dev.cerez.tahp.command.commands.ExitCommand;
import dev.cerez.tahp.command.commands.FundingCommand;
import dev.cerez.tahp.command.commands.TriangularCommand;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.connector.model.ActionOrden;
import dev.cerez.tahp.discord.DiscordConnector;
import dev.cerez.tahp.utils.Utils;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class Main {

    // No hacer no conectores en:
    // crypto.com
    // okx (Muy difícil)

    @Getter
    private static final Main instance = new Main();
    private static final CommandHander commandHandler = new CommandHander();

    public static final Executor executor = Executors.newFixedThreadPool(8);

    public static final boolean IS_TESTNET = false;

    public static void main(String[] args) {
        commandHandler.registerCommand(
                new ExitCommand(),
                new TriangularCommand(),
                new FundingCommand(),
                new CheckFundingCommand()
        );
//        commandHandler.init();
//        BinanceConnector connector = new BinanceConnector();
//        connector.setLogEndpoint(true);
//        connector.start();
//        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
//        BigDecimal balanceUSDC = connector.sGetBalance().get("USDC");
//        connector.cConvert("USDC", "USDT", balanceUSDC, true);

        // TODO: code -1021 reenviar la solicitud
        // TODO: Testear las ordenes en futuros
    }

    public static void exit(){
        System.exit(0);
    }
}