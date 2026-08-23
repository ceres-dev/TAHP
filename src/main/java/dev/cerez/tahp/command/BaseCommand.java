package dev.cerez.tahp.command;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

@RequiredArgsConstructor
@Getter
public abstract class BaseCommand {

    protected final String name;

    public abstract void execute(@NotNull List<String> args);
}
