package dev.cerez.tahp;

import dev.cerez.tahp.command.CommandHander;
import dev.cerez.tahp.command.commands.CheckFundingCommand;
import dev.cerez.tahp.command.commands.FundingCommand;
import dev.cerez.tahp.command.commands.ExitCommand;
import dev.cerez.tahp.command.commands.TriangularCommand;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.fuding.BlockerForSpread;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class Main {

    // No hacer no conectores en:
    // crypto.com
    // okx (Muy difícil)

    @Getter
    private static final Main instance = new Main();
    private static final CommandHander commandHandler = new CommandHander();

    private static final Executor executor = Executors.newFixedThreadPool(8);

    public static void main(String[] args) {
        commandHandler.registerCommand(
                new ExitCommand(),
                new TriangularCommand(),
                new FundingCommand(),
                new CheckFundingCommand()
        );
//        commandHandler.init(args);
        BinanceConnector connector = new BinanceConnector(false);
        connector.setLogEndpoint(true);
        connector.start();
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
        connector.wTransfer("XRPUSDT", BinanceConnector.Transfer.SPOT_TO_MARGIN, "USDT", new BigDecimal("0.6"));
        connector.wTransfer("XRPUSDT", BinanceConnector.Transfer.MARGIN_TO_ISOLATED, "USDT", new BigDecimal("0.6"));
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        connector.wTransfer("XRPUSDT", BinanceConnector.Transfer.ISOLATED_TO_MARGIN, "USDT", new BigDecimal("0.6"));
        connector.wTransfer("XRPUSDT", BinanceConnector.Transfer.MARGIN_TO_SPOT, "USDT", new BigDecimal("0.6"));
    }// TODO: code -1021 reenviar la solicitud

    public static void exit(){
        System.exit(0);
    }
}