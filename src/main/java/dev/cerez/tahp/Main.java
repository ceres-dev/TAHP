package dev.cerez.tahp;

import dev.cerez.tahp.command.CommandHander;
import dev.cerez.tahp.command.commands.CheckFundingCommand;
import dev.cerez.tahp.command.commands.StopCommand;
import dev.cerez.tahp.command.commands.TriangularCommand;
import dev.cerez.tahp.command.commands.searchBestCommand;
import lombok.Getter;

public class Main {

    // No hacer no conectores en:
    // crypto.com
    // okx (Muy difícil)

    @Getter
    private static final Main instance = new Main();
    private static final CommandHander commandHandler = new CommandHander();

    public static void main(String[] args) {
        commandHandler.registerCommand(
                new StopCommand(),
                new TriangularCommand(),
                new searchBestCommand(),
                new CheckFundingCommand()
        );
        commandHandler.init(args);
    }

    public void exit(){
        System.exit(0);
    }
}