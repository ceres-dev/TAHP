package dev.cerez.tahp;

import dev.cerez.tahp.command.CommandHander;
import dev.cerez.tahp.command.commands.CheckFundingCommand;
import dev.cerez.tahp.command.commands.ExitCommand;
import dev.cerez.tahp.command.commands.FundingCommand;
import dev.cerez.tahp.command.commands.TriangularCommand;
import dev.cerez.tahp.discord.DiscordConnector;
import lombok.Getter;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class Main {

    // No hacer no conectores en:
    // crypto.com
    // okx (Muy difícil)

    @Getter
    private static final Main instance = new Main();
    private static final CommandHander commandHandler = new CommandHander();

    public static final Executor executor = Executors.newFixedThreadPool(8);

    public static void main(String[] args) {
        commandHandler.registerCommand(
                new ExitCommand(),
                new TriangularCommand(),
                new FundingCommand(),
                new CheckFundingCommand()
        );
        commandHandler.init();
//        var discordConnector = new DiscordConnector();
//        discordConnector.start();
//        BinanceConnector connector = new BinanceConnector(true);
//        connector.setLogEndpoint(true);
//        connector.start();
//        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
//        Map<String, BigDecimal> b0 = connector.sGetBalance();
//        connector.sGetAllSymbols();
//        System.out.printf("USDT: %.8f USDC %.8f BTC %.8f", b0.getOrDefault("USDT", new BigDecimal("-1.00")), b0.getOrDefault("USDC", new BigDecimal("-1.00")), b0.getOrDefault("BTC", new BigDecimal("-1.00")));
//        connector.sSendOrderToMkt("BTCUSDT", ActionOrden.BUY, new BigDecimal("7624.88544404"),"12345", false);
//        Map<String, BigDecimal> b1 = connector.sGetBalance();
//        System.out.printf("USDT: %.8f USDC %.8f BTC %.8f", b1.getOrDefault("USDT", new BigDecimal("-1.00")), b1.getOrDefault("USDC", new BigDecimal("-1.00")), b1.getOrDefault("BTC", new BigDecimal("-1.00")));
        // TODO: code -1021 reenviar la solicitud
        // TODO: Testear las ordenes en futuros
    }

    public static void exit(){
        System.exit(0);
    }
}