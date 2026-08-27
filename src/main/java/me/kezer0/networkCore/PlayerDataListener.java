package me.kezer0.networkCore;

import me.kezer0.networkCore.player.PlayerDataManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class PlayerDataListener implements Listener {
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PlayerDataManager.handleJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PlayerDataManager.handleQuit(event.getPlayer());
    }
}
