/*
 * This file is part of VelocityScoreboardAPI, licensed under the Apache License 2.0.
 *
 *  Copyright (c) William278 <will27528@gmail.com>
 *  Copyright (c) NEZNAMY <n.e.z.n.a.m.y@azet.sk>
 *  Copyright (c) contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.velocitypowered.proxy.scoreboard.downstream;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.scoreboard.ObjectiveEvent;
import com.velocitypowered.api.event.scoreboard.ScoreboardEventSource;
import com.velocitypowered.api.event.scoreboard.TeamEntryEvent;
import com.velocitypowered.api.event.scoreboard.TeamEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scoreboard.*;
import com.velocitypowered.proxy.data.LoggerManager;
import com.velocitypowered.proxy.data.StringCollection;
import com.velocitypowered.proxy.protocol.packet.chat.ComponentHolder;
import com.velocitypowered.proxy.protocol.packet.scoreboard.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This is a downstream tracker that reflects on what the backend tried to display to the player.
 */
public class DownstreamScoreboard implements Scoreboard {

    /** Server to call events to */
    private final ScoreboardEventSource eventSource;

    /** Registered objectives on the backend */
    private final Map<String, DownstreamObjective> objectives = new ConcurrentHashMap<>();

    /** Registered teams on the backend */
    private final Map<String, DownstreamTeam> teams = new ConcurrentHashMap<>();

    /** Display slots assigned to objectives */
    private final Map<DisplaySlot, DownstreamObjective> displaySlots = new ConcurrentHashMap<>();

    /** Viewer this scoreboard view belongs to */
    @NotNull
    private final Player viewer;

    /**
     * Constructs new instance with given parameters.
     *
     * @param   eventSource
     *          Object that will be used to fire scoreboard events
     * @param   viewer
     *          Player who this view will belong to
     */
    public DownstreamScoreboard(@NotNull ScoreboardEventSource eventSource, @NotNull Player viewer) {
        this.eventSource = eventSource;
        this.viewer = viewer;
    }

    /**
     * Handles incoming objective packet coming from backend and updates tracked values.
     *
     * @param   packet
     *          Objective packet coming from backend
     * @return  {@code true} if packet is invalid and should be cancelled, {@code false} if not
     */
    public boolean handle(@NotNull ObjectivePacket packet) {
        switch (packet.getAction()) {
            case REGISTER -> {
                DownstreamObjective obj = new DownstreamObjective(
                        packet.getObjectiveName(),
                        packet.getTitle(),
                        packet.getHealthDisplay(),
                        packet.getNumberFormat()
                );
                if (objectives.putIfAbsent(packet.getObjectiveName(), obj) != null) {
                    LoggerManager.invalidDownstreamPacket(viewer, "This scoreboard already contains objective \"" + packet.getObjectiveName() + "\"");
                    return true;
                } else {
                    eventSource.fireEvent(new ObjectiveEvent.Register(viewer, this, obj));
                }
            }
            case UNREGISTER -> {
                DownstreamObjective removed = objectives.remove(packet.getObjectiveName());
                if (removed == null) {
                    LoggerManager.invalidDownstreamPacket(viewer, "This scoreboard does not contain objective \"" + packet.getObjectiveName() + "\", cannot unregister");
                    return true;
                }
                displaySlots.entrySet().removeIf(entry -> entry.getValue().getName().equals(packet.getObjectiveName()));
                eventSource.fireEvent(new ObjectiveEvent.Unregister(viewer, this, removed));
            }
            case UPDATE -> {
                DownstreamObjective objective = objectives.get(packet.getObjectiveName());
                if (objective == null) {
                    LoggerManager.invalidDownstreamPacket(viewer, "This scoreboard does not contain objective \"" + packet.getObjectiveName() + "\", cannot update");
                    return true;
                } else {
                    objective.update(packet);
                }
            }
        }
        return false;
    }

    /**
     * Handles incoming display objective packet coming from backend and updates tracked values.
     *
     * @param   packet
     *          Display objective packet coming from backend
     * @return  {@code true} if packet is invalid and should be cancelled, {@code false} if not
     */
    public boolean handle(@NotNull DisplayObjectivePacket packet) {
        DownstreamObjective objective = objectives.get(packet.getObjectiveName());
        if (objective == null) {
            LoggerManager.invalidDownstreamPacket(viewer, "Cannot set display slot of unknown objective \"" + packet.getObjectiveName() + "\" + to " + packet.getPosition());
            return true;
        } else {
            DownstreamObjective previous = displaySlots.put(packet.getPosition(), objective);
            if (previous != null) previous.setDisplaySlot(null);
            objective.setDisplaySlot(packet.getPosition());
            eventSource.fireEvent(new ObjectiveEvent.Display(viewer, this, objective, packet.getPosition()));
            return false;
        }
    }

    /**
     * Handles incoming score packet coming from backend and updates tracked values.
     *
     * @param   packet
     *          Score packet coming from backend
     * @return  {@code true} if packet is invalid and should be cancelled, {@code false} if not
     */
    public boolean handle(@NotNull ScorePacket packet) {
        if (packet.getAction() == ScorePacket.ScoreAction.SET) {
            return handleSet(packet.getObjectiveName(), packet.getScoreHolder(), packet.getValue(), null, null);
        } else {
            return handleReset(packet.getObjectiveName(), packet.getScoreHolder());
        }
    }

    /**
     * Handles incoming set score packet coming from backend and updates tracked values.
     *
     * @param   packet
     *          Set score packet coming from backend
     * @return  {@code true} if packet is invalid and should be cancelled, {@code false} if not
     */
    public boolean handle(@NotNull ScoreSetPacket packet) {
        return handleSet(packet.getObjectiveName(), packet.getScoreHolder(), packet.getValue(),
                packet.getDisplayName(), packet.getNumberFormat());
    }

    /**
     * Handles incoming reset score packet coming from backend and updates tracked values.
     *
     * @param   packet
     *          Reset score packet coming from backend
     * @return  {@code true} if packet is invalid and should be cancelled, {@code false} if not
     */
    public boolean handle(@NotNull ScoreResetPacket packet) {
        return handleReset(packet.getObjectiveName(), packet.getScoreHolder());
    }

    private boolean handleSet(@NotNull String objectiveName, @NotNull String holder, int value,
                              @Nullable ComponentHolder displayName, @Nullable NumberFormat numberFormat) {
        DownstreamObjective objective = objectives.get(objectiveName);
        if (objective == null) {
            LoggerManager.invalidDownstreamPacket(viewer, "Cannot set score \"" + holder + "\" for unknown objective \"" + objectiveName + "\"");
            return true;
        } else {
            objective.setScore(holder, value, displayName, numberFormat);
            return false;
        }
    }

    private boolean handleReset(@Nullable String objectiveName, @NotNull String holder) {
        if (objectiveName == null || objectiveName.isEmpty()) {
            for (DownstreamObjective objective : objectives.values()) {
                objective.removeScore(holder);
            }
        } else {
            DownstreamObjective objective = objectives.get(objectiveName);
            if (objective == null) {
                LoggerManager.invalidDownstreamPacket(viewer, "Cannot reset score \"" + holder + "\" for unknown objective \"" + objectiveName + "\"");
                return true;
            } else {
                objective.removeScore(holder);
            }
        }
        return false;
    }

    /**
     * Handles incoming team packet coming from backend and updates tracked values.
     *
     * @param   packet
     *          Team packet coming from backend
     * @return  {@code true} if packet is invalid and should be cancelled, {@code false} if not
     */
    public boolean handle(@NotNull TeamPacket packet) {
        StringCollection entries = packet.getEntries();
        switch (packet.getAction()) {
            case REGISTER -> {
                DownstreamTeam team = new DownstreamTeam(packet.getName(), packet.getProperties(), entries);
                if (teams.putIfAbsent(packet.getName(), team) != null) {
                    LoggerManager.invalidDownstreamPacket(viewer, "This scoreboard already contains team \"" + packet.getName() + "\"");
                    return true;
                } else {
                    eventSource.fireEvent(new TeamEvent.Register(viewer, this, team));
                }
            }
            case UNREGISTER -> {
                DownstreamTeam removed = teams.remove(packet.getName());
                if (removed == null) {
                    LoggerManager.invalidDownstreamPacket(viewer, "This scoreboard does not contain team \"" + packet.getName() + "\", cannot unregister");
                    return true;
                }
                eventSource.fireEvent(new TeamEvent.Unregister(viewer, this, removed));
            }
            case UPDATE -> {
                DownstreamTeam team = teams.get(packet.getName());
                if (team == null) {
                    LoggerManager.invalidDownstreamPacket(viewer, "This scoreboard does not contain team \"" + packet.getName() + "\", cannot update");
                    return true;
                } else {
                    team.setProperties(packet.getProperties());
                }
            }
            case ADD_PLAYER -> {
                DownstreamTeam team = teams.get(packet.getName());
                if (team == null) {
                    LoggerManager.invalidDownstreamPacket(viewer, "This scoreboard does not contain team \"" + packet.getName() + "\", cannot add entries");
                    return true;
                } else {
                    for (DownstreamTeam allTeams : teams.values()) {
                        allTeams.removeEntriesIfPresent(entries);
                    }
                    team.addEntries(entries);
                    if (entries.getEntry() != null) {
                        eventSource.fireEvent(new TeamEntryEvent.Add(viewer, this, team, entries.getEntry()));
                    } else {
                        for (String entry : entries.getEntries()) {
                            eventSource.fireEvent(new TeamEntryEvent.Add(viewer, this, team, entry));
                        }
                    }
                }
            }
            case REMOVE_PLAYER -> {
                DownstreamTeam team = teams.get(packet.getName());
                if (team == null) {
                    LoggerManager.invalidDownstreamPacket(viewer, "This scoreboard does not contain team \"" + packet.getName() + "\", cannot remove entries");
                    return true;
                } else {
                    team.removeEntries(viewer, entries);
                    if (entries.getEntry() != null) {
                        eventSource.fireEvent(new TeamEntryEvent.Remove(viewer, this, team, entries.getEntry()));
                    } else {
                        for (String entry : entries.getEntries()) {
                            eventSource.fireEvent(new TeamEntryEvent.Remove(viewer, this, team, entry));
                        }
                    }
                }
            }
        }
        return false;
    }

    @Override
    @Nullable
    public DownstreamObjective getObjective(@NotNull DisplaySlot displaySlot) {
        return displaySlots.get(displaySlot);
    }

    @Override
    @Nullable
    public DownstreamObjective getObjective(@NotNull String objectiveName) {
        return objectives.get(objectiveName);
    }

    @Override
    @NotNull
    public Collection<? extends Objective> getObjectives() {
        return Collections.unmodifiableCollection(objectives.values());
    }

    @Override
    @Nullable
    public DownstreamTeam getTeam(@NotNull String teamName) {
        return teams.get(teamName);
    }

    @Override
    @NotNull
    public Collection<? extends Team> getTeams() {
        return Collections.unmodifiableCollection(teams.values());
    }

    @NotNull
    public Collection<DownstreamTeam> getDownstreamTeams() {
        return teams.values();
    }

    /**
     * Clears this scoreboard on server switch when JoinGame packet is received.
     */
    public void clear() {
        objectives.clear();
        teams.clear();
    }

    public void dump() {
        System.out.println("--- DownstreamScoreboard of player " + viewer.getUsername() + " ---");
        System.out.println("Teams (" + teams.size() + "):");
        for (DownstreamTeam team : teams.values()) {
            team.dump();
        }
        System.out.println("Objectives (" + objectives.size() + "):");
        for (DownstreamObjective objective : objectives.values()) {
            objective.dump();
        }
    }

    public void upload(CommandSource sender) throws Exception {
        ArrayList<String> content = new ArrayList<>();
        content.add("--- DownstreamScoreboard of player " + viewer.getUsername() + " ---");
        content.add("Teams (" + teams.size() + "):");
        teams.values().forEach(team -> content.addAll(team.getDump()));
        content.add("Objectives (" + objectives.size() + "):");
        objectives.values().forEach(objective -> content.addAll(objective.getDump()));

        StringBuilder contentBuilder = new StringBuilder();
        content.forEach(line -> contentBuilder.append(line).append("\n"));

        URL url = new URL("https://api.pastes.dev/post");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "text/log; charset=UTF-8");

        try (OutputStream os = connection.getOutputStream()) {
            os.write(contentBuilder.toString().getBytes("UTF-8"));
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        String inputLine;
        StringBuilder response = new StringBuilder();

        while ((inputLine = in.readLine()) != null) response.append(inputLine);
        in.close();

        String responseString = response.toString();
        String id = responseString.substring(responseString.indexOf("\"key\":\"") + 7, responseString.indexOf("\"", responseString.indexOf("\"key\":\"") + 7));

        TextComponent message = Component.text("Click here to open the result.");
        message = message.clickEvent(ClickEvent.clickEvent(ClickEvent.Action.OPEN_URL,"https://pastes.dev/" + id));
        sender.sendMessage(message);
    }
}
