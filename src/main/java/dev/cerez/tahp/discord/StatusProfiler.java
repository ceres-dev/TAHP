package dev.cerez.tahp.discord;

import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import org.jetbrains.annotations.NotNull;

public interface StatusProfiler {

    @NotNull StatusProfiler.PresenceProfile getPresenceProfile();

    record PresenceProfile(OnlineStatus onlineStatus, Activity activity) {}

}
