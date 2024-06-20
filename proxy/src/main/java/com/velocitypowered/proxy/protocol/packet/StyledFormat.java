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

package com.velocitypowered.proxy.protocol.packet;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.scoreboard.NumberFormat;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.format.Style;
import org.jetbrains.annotations.NotNull;

public class StyledFormat implements NumberFormat {

    @NotNull
    private final Style style;

    public StyledFormat(@NotNull Style style) {
        this.style = style;
    }

    @Override
    public void write(@NotNull ByteBuf buf, @NotNull ProtocolVersion protocolVersion) {
        ProtocolUtils.writeVarInt(buf, 0); // write BLANK before this gets implemented

        //ProtocolUtils.writeVarInt(buf, 1);
        //writeComponentStyle((ComponentStyle) format.getValue(), buf, protocolVersion); //TODO
    }
}
