package dev.cerez.tahp.discord;

import dev.cerez.tahp.Main;
import dev.cerez.tahp.io.IOdata;
import dev.cerez.tahp.triangular.utils.Switch;
import lombok.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

@RequiredArgsConstructor
public class DiscordConnector implements Switch {

    private final @NotNull String token;
    private final @NotNull JDA jda;

    private boolean isStarted = false;
    @Getter @Setter
    private StatusProfiler statusProfiler = null;

    public DiscordConnector() {
        DiscordConfig config = IOdata.loadConfigDiscordBot();
        this.token = config.token;
        this.jda = JDABuilder.createDefault(token).build();
    }

    @SneakyThrows
    @Override
    public void start() {
        jda.awaitReady();
        isStarted = true;
        Main.executor.execute(() -> {
            while (isStarted && statusProfiler != null) {
                StatusProfiler.PresenceProfile presenceProfiler = statusProfiler.getPresenceProfile();
                jda.getPresence().setStatus(presenceProfiler.onlineStatus());
                jda.getPresence().setActivity(presenceProfiler.activity());
                LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(2));
            }
        });
    }

    @Override
    public void stop() {
        jda.shutdown();
        isStarted = false;
    }

    @Builder
    public static class DiscordConfig{
        private String token;
        private String userMaster;
    }
}
