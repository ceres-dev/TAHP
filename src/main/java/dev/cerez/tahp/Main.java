package dev.cerez.tahp;

import dev.cerez.tahp.command.CommandHander;
import dev.cerez.tahp.command.commands.*;
import dev.cerez.tahp.discord.DiscordConnector;
import lombok.Getter;
import lombok.Setter;

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

    @Getter
    public DiscordConnector discordConnector = new DiscordConnector();

    public static void main(String[] args) {
        commandHandler.registerCommand(
                new ExitCommand(),
                new TriangularCommand(),
                new FundingCommand(),
                new CheckFundingCommand(),
                new GridCommand()
        );
        try {
            commandHandler.init();
        } catch (Exception e) {
            Main.getInstance().getDiscordConnector().sendMessage("Error Critico: " + e.getMessage());
            e.printStackTrace();
        }

        // TODO: code -1021 reenviar la solicitud
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