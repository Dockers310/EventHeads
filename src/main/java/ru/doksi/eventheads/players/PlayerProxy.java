
package ru.doksi.eventheads.players;

// RU: Реализация PlayerLike для обычного Bukkit Player.
// EN: PlayerLike implementation backed by a Bukkit Player.
import org.bukkit.Location; import org.bukkit.entity.Player;
public record PlayerProxy(Player player) implements PlayerLike { @Override public Location location(){return player.getLocation();} }
