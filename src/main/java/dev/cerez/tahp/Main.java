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
        commandHandler.init(args);
    }

    public static void exit(){
        System.exit(0);
    }
}