
package ru.doksi.eventheads.players;

// RU: Маленькая абстракция игрока для внутренних сервисов.
// EN: Small player abstraction used by internal services.
import org.bukkit.Location; import org.bukkit.entity.Player;
public interface PlayerLike { Player player(); Location location(); }
