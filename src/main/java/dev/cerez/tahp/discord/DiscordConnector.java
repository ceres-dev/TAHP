package dev.cerez.tahp.discord;

import dev.cerez.tahp.Main;
import dev.cerez.tahp.io.IOdata;
import dev.cerez.tahp.utils.Switch;
import lombok.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.User;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@RequiredArgsConstructor
public class DiscordConnector implements Switch {

    private final @NotNull String token;
    private final @NotNull DiscordConfig config;
    private final @NotNull JDA jda;

    private boolean isStarted = false;
    @Getter @Setter
    private StatusProfiler statusProfiler = null;

    public DiscordConnector() {
        DiscordConfig config = IOdata.loadOrSaveConfig(DiscordConfig.builder().build());
        this.token = config.token;
        this.config = config;
        this.jda = JDABuilder.createDefault(token).build();
    }

    @SneakyThrows
    @Override
    public void start() {
        jda.awaitReady();
        isStarted = true;
        Main.executor.execute(() -> {
            while (isStarted) {
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
                if (statusProfiler == null) continue;
                StatusProfiler.PresenceProfile presenceProfiler = statusProfiler.getPresenceProfile();
                jda.getPresence().setStatus(presenceProfiler.onlineStatus());
                jda.getPresence().setActivity(presenceProfiler.activity());

            }
        });
    }

    @Override
    public void stop() {
        jda.shutdown();
        isStarted = false;
    }

    public void sendMessage(String message) {
        User user = jda.retrieveUserById(config.userMaster).complete();
        user.openPrivateChannel().queue(channel -> {
            channel.sendMessage(message).queue();
        });
    }

    @Builder
    public static class DiscordConfig{
        @Builder.Default private String token = "";
        @Builder.Default private String userMaster = "";
    }
}
