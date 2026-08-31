package dev.cerez.tahp.discord;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import org.jetbrains.annotations.NotNull;

@RequiredArgsConstructor
public class DiscordConnector {

    private final @NotNull String token;
    private final @NotNull JDA jda;

    public DiscordConnector(@NotNull String token) {
        this.token = token;
        this.jda = JDABuilder.createDefault(token).build();
    }

    @Builder
    public class DiscordConfig{
        private String token;
        private String userMaster;
    }
}
