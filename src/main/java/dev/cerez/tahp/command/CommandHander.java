package dev.cerez.tahp.command;

import dev.cerez.tahp.Log;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class CommandHander {

    private final HashMap<String, BaseCommand> commands = new HashMap<>();

    public void init(String[] args) {
        if (args.length == 0) {
            Log.warning("No hay commandos");
            return;
        }
        BaseCommand command = commands.get(args[0]);
        if (command == null) {
            Log.warning("No such command: " + args[0]);
            return;
        }
        if (args.length > 1) {
            command.execute(Arrays.stream(Arrays.copyOfRange(args, 1, args.length)).toList());
        }else {
            command.execute(List.of());
        }
    }

    public void registerCommand(BaseCommand @NotNull ... command) {
        for (BaseCommand c : command) commands.put(c.getName(), c);
    }
}
