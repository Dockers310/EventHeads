package ru.doksi.eventheads.listeners;

// RU: Слушатель изменений доступа игроков.
// EN: Listener responsible for EventHeads access/permission synchronization.
import ru.doksi.eventheads.EventHeadsPlugin;
import org.bukkit.event.*;import org.bukkit.event.player.*;
public final class AccessListener implements Listener{
 private final EventHeadsPlugin plugin;public AccessListener(EventHeadsPlugin p){plugin=p;}
 @EventHandler public void join(PlayerJoinEvent e){plugin.access().syncUsePermission(e.getPlayer());}
 @EventHandler public void quit(PlayerQuitEvent e){ }
}
