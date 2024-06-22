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

package com.velocitypowered.proxy.scoreboard;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scoreboard.DisplaySlot;
import com.velocitypowered.api.scoreboard.Objective;
import com.velocitypowered.api.scoreboard.Scoreboard;
import com.velocitypowered.api.scoreboard.Team;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.data.DataHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class VelocityScoreboard implements Scoreboard {

    public static final ProtocolVersion MAXIMUM_SUPPORTED_VERSION = ProtocolVersion.MINECRAFT_1_21;

    /** Priority of this scoreboard */
    private final int priority;
    private final ProxyServer server;
    private final Object plugin;

    private final Collection<ConnectedPlayer> players = Sets.newConcurrentHashSet();
    private final Map<String, VelocityObjective> objectives = Maps.newConcurrentMap();
    private final Map<String, VelocityTeam> teams = Maps.newConcurrentMap();
    private final Map<DisplaySlot, VelocityObjective> displaySlots = Maps.newConcurrentMap();

    public VelocityScoreboard(int priority, @NotNull ProxyServer server, @NotNull Object plugin) {
        this.priority = priority;
        this.server = server;
        this.plugin = plugin;
    }

    @NotNull
    public ProxyServer getProxyServer() {
        return server;
    }

    public int getPriority() {
        return priority;
    }

    public Collection<ConnectedPlayer> getPlayers() {
        return players;
    }

    @Override
    @NotNull
    public Team.Builder teamBuilder(@NotNull String name) {
        return new VelocityTeam.Builder(name);
    }

    @Override
    @NotNull
    public Team.PropertyBuilder teamPropertyBuilder() {
        return new VelocityTeam.PropertyBuilder();
    }

    @Override
    @NotNull
    public Objective.Builder objectiveBuilder(@NotNull String name) {
        return new VelocityObjective.Builder(name);
    }

    @Override
    public void addPlayer(@NotNull Player player) {
        if (player.getProtocolVersion().greaterThan(MAXIMUM_SUPPORTED_VERSION)) return;
        DataHolder.getScoreboardManager(player).registerScoreboard(this);
        players.add((ConnectedPlayer) player);
        List<ConnectedPlayer> list = Collections.singletonList((ConnectedPlayer) player);
        for (VelocityTeam team : teams.values()) {
            team.sendRegister(list);
        }
        for (VelocityObjective objective : objectives.values()) {
            objective.sendRegister(list);
            for (VelocityScore score : objective.getScores()) {
                score.sendUpdate(list);
            }
        }
    }

    @Override
    public void removePlayer(@NotNull Player player) {
        if (player.getProtocolVersion().greaterThan(MAXIMUM_SUPPORTED_VERSION)) return;
        DataHolder.getScoreboardManager(player).unregisterScoreboard(this);
        players.remove((ConnectedPlayer) player);
        List<ConnectedPlayer> list = Collections.singletonList((ConnectedPlayer) player);
        for (VelocityTeam team : teams.values()) {
            team.sendUnregister(list);
        }
        for (VelocityObjective objective : objectives.values()) {
            objective.sendUnregister(list);
        }
    }

    @Override
    @NotNull
    public Objective registerObjective(@NotNull Objective.Builder builder) {
        final VelocityObjective objective = ((VelocityObjective.Builder)builder).build(this);
        if (objectives.containsKey(objective.getName())) throw new IllegalStateException("Objective with this name already exists");
        objectives.put(objective.getName(), objective);
        objective.sendRegister(players);
        return objective;
    }

    @Override
    @Nullable
    public Objective getObjective(@NotNull String name) {
        return objectives.get(name);
    }

    @Override
    public void unregisterObjective(@NotNull String objectiveName) throws IllegalStateException {
        if (!objectives.containsKey(objectiveName)) throw new IllegalStateException("This scoreboard does not contain an objective named " + objectiveName);
        objectives.remove(objectiveName).sendUnregister(players);
        displaySlots.entrySet().removeIf(entry -> entry.getValue().getName().equals(objectiveName));
    }

    @NotNull
    @Override
    public Team registerTeam(@NotNull Team.Builder builder) {
        VelocityTeam team = ((VelocityTeam.Builder)builder).build(this);
        if (teams.containsKey(team.getName())) throw new IllegalStateException("Team with this name already exists");
        teams.put(team.getName(), team);
        team.sendRegister(players);
        return team;
    }

    @Override
    @Nullable
    public Team getTeam(@NotNull String teamName) {
        return teams.get(teamName);
    }

    @Override
    public void unregisterTeam(@NotNull String teamName) {
        if (!teams.containsKey(teamName)) throw new IllegalStateException("This scoreboard does not contain a team named " + teamName);
        teams.remove(teamName).sendUnregister(players);
    }

    @Override
    @NotNull
    public Object holder() {
        return plugin;
    }

    public void setDisplaySlot(@NotNull DisplaySlot displaySlot, @NotNull VelocityObjective objective) {
        VelocityObjective previous = displaySlots.put(displaySlot, objective);
        if (previous != null) previous.clearDisplaySlot();
    }

    public Collection<VelocityTeam> getAllTeams() {
        return teams.values();
    }
}
