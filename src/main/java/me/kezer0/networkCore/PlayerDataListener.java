package me.kezer0.networkCore;

import me.kezer0.networkCore.profile.PlayerProfileManager;
import me.kezer0.networkCore.session.SessionManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerDataListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PlayerProfileManager.getInstance().handleJoin(event.getPlayer());
        SessionManager.getInstance().handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        SessionManager.getInstance().handleQuit(event.getPlayer(), "quit");
        PlayerProfileManager.getInstance().unload(event.getPlayer().getUniqueId());
    }
}
