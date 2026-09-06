package dev.cerez.tahp;

import dev.cerez.tahp.command.CommandHander;
import dev.cerez.tahp.command.commands.*;
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

    public static final boolean IS_TESTNET = false;

    public static void main(String[] args) {
        commandHandler.registerCommand(
                new ExitCommand(),
                new TriangularCommand(),
                new FundingCommand(),
                new CheckFundingCommand(),
                new GridCommand()
        );
        commandHandler.init();

//        BinanceConnector connector = new BinanceConnector();
//        connector.setLogEndpoint(true);
//        connector.start();
//        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(1));
//        Log.info("Binance connector started");
//        Loader loader = new Loader();
//        while (true) {
//            double d = connector.mGetMaxBorrowable("ONG" + "USDT", "ONG");
//            loader.nextAndPrint();
//            if (d != -1) Log.info(d +"");
//        }
//        BigDecimal balanceUSDC = connector.sGetBalance().get("USDC");
//        connector.cConvert("USDC", "USDT", balanceUSDC, true);

        // TODO: code -1021 reenviar la solicitud
        // TODO: Testear las ordenes en futuros
    }


    private static class Loader{
        private int step;

        public void nextAndPrint(){
            String loader = switch ((step++) % 4) {
                case 0 -> "|";
                case 1 -> "/";
                case 2 -> "-";
                case 3 -> "\\";
                default -> "?";
            };
            System.out.print(loader + "\r");
        }
    }

    public static void exit(){
        System.exit(0);
    }
}