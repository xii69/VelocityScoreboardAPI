package com.velocityscoreboardapi.api;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocityscoreboardapi.impl.VelocityScoreboard;
import org.jetbrains.annotations.NotNull;

public class ScoreboardManager {

    public static Scoreboard getNewScoreboard(int priority) {
        if (priority < 0) throw new IllegalArgumentException("Priority cannot be negative");
        if (priority == 0) throw new IllegalArgumentException("Priority 0 is reserved for downstream packets");
        return new VelocityScoreboard(priority);
    }

    public static void setScoreboard(@NotNull Player player, @NotNull Scoreboard scoreboard) {
        ((VelocityScoreboard)scoreboard).getPlayers().add((ConnectedPlayer) player); // TODO properly do this
    }
}
