package dev.cerez.tahp.command;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@Getter
public abstract class BaseCommand {

    protected final String name;
    @Nullable
    protected final String alias;

    public BaseCommand(@NotNull String name) {
        this.name = name;
        this.alias = null;
    }

    public BaseCommand(@NotNull String name, @NotNull String alias) {
        this.name = name;
        this.alias = alias;
    }

    public abstract void execute(@NotNull List<String> args);
}
