package me.kezer0.networkCore.api;

import me.kezer0.networkCore.player.PlayerData;

import java.util.UUID;

/** Shared player profile/skills/quest access for backend plugins. */
public interface PlayerDataService {
    PlayerData get(UUID uuid);
    boolean isLoaded(UUID uuid);
}
