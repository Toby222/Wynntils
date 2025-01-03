/*
 * Copyright © Wynntils 2024.
 * This file is released under LGPLv3. See LICENSE for full license details.
 */
package com.wynntils.features.chat;

import com.wynntils.core.components.Handlers;
import com.wynntils.core.components.Managers;
import com.wynntils.core.components.Models;
import com.wynntils.core.consumers.features.Feature;
import com.wynntils.core.consumers.features.properties.RegisterKeyBind;
import com.wynntils.core.keybinds.KeyBind;
import com.wynntils.core.persisted.Persisted;
import com.wynntils.core.persisted.config.Config;
import com.wynntils.models.worlds.type.BombInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public class BombBellRelayFeature extends Feature {
    @RegisterKeyBind
    private final KeyBind relayPartyKeybind =
            new KeyBind("Relay Bomb to Party", GLFW.GLFW_KEY_UNKNOWN, true, () -> relayTo("p"));

    @RegisterKeyBind
    private final KeyBind relayGuildKeybind =
            new KeyBind("Relay Bomb to Guild", GLFW.GLFW_KEY_UNKNOWN, true, () -> relayTo("g"));

    @Persisted
    public final Config<Boolean> showTime = new Config<>(true);

    private String getAndFormatLastBomb() {
        BombInfo lastBomb = Models.Bomb.getLastBomb();
        if (lastBomb == null) return null;

        // This is not localized as it is sent to other players
        String bombMessage = lastBomb.bomb().getName() + " bomb on " + lastBomb.server();

        if (showTime.get()) {
            bombMessage += " with " + lastBomb.getRemainingString() + " remaining";
        }

        return bombMessage;
    }

    private void relayTo(String prefix) {
        String lastBomb = getAndFormatLastBomb();
        if (lastBomb == null) {
            Managers.Notification.queueMessage(Component.translatable("feature.wynntils.bombBellRelay.noKnownBombs")
                    .withStyle(ChatFormatting.DARK_RED));
            return;
        }

        Handlers.Command.sendCommandImmediately(prefix + " " + lastBomb);
    }
}
