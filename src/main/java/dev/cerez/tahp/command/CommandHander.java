package dev.cerez.tahp.command;

import dev.cerez.tahp.Log;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

public class CommandHander {

    private final HashMap<String, BaseCommand> commands = new HashMap<>();
    private final HashMap<String, String> aliases = new HashMap<>();
    private final InputUser input = new InputUser();

    @Contract(pure = true)
    public void dispatch(String @NotNull ... args) {
        if (args.length == 0) {
            Log.warning("No hay commandos");
            return;
        }

        BaseCommand command = commands.getOrDefault(args[0], commands.get(aliases.get(args[0])));
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

    public boolean running = false;

    @Blocking
    public void init() {
        running = true;
        while (running) {
            String input = this.input.in("");
            dispatch(input.split(" "));
        }
    }

    public void stop(){
        running = false;
    }

    public void registerCommand(BaseCommand @NotNull ... command) {
        for (BaseCommand c : command) {
            commands.put(c.getName(), c);
            aliases.put(c.getAlias(), c.getName());
        }
    }
}
