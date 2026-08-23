package dev.cerez.tahp.command.commands;

import dev.cerez.tahp.Main;
import dev.cerez.tahp.command.BaseCommand;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class StopCommand extends BaseCommand {

    public StopCommand() {
        super("stop");
    }

    @Override
    public void execute(@NotNull List<String> args) {
        Main.getInstance().exit();
    }
}
