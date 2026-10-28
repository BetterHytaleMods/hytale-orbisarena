package de.hymods.orbisarena.stash;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.hymods.orbisarena.game.Worlds;

import java.util.logging.Level;

/**
 * Swaps a player's inventory whenever they cross between an Orbis Arena world (lobby or arena) and any other.
 * The game raises {@link AddPlayerToWorldEvent} for every arrival with the player's holder before they are in the
 * world: what is in it then is what the client gets.
 */
public final class StashSwap {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final ComponentType<EntityStore, Stash> stash;

    public StashSwap(ComponentType<EntityStore, Stash> stash) {
        this.stash = stash;
    }

    public void onArrive(AddPlayerToWorldEvent event) {
        Holder<EntityStore> holder = event.getHolder();
        if (holder.getComponent(Player.getComponentType()) == null) {
            return;
        }
        boolean arena = Worlds.ours(event.getWorld());
        Stash kept = holder.getComponent(this.stash);
        if ((kept != null && kept.inArena) == arena) {
            return;
        }
        if (kept == null) {
            kept = new Stash();
            holder.putComponent(this.stash, kept);
        }
        kept.swap(holder);
        PlayerRef ref = holder.getComponent(PlayerRef.getComponentType());
        LOGGER.at(Level.INFO).log("%s arrives in %s: %s inventory in hand", ref == null ? "?" : ref.getUsername(),
                event.getWorld().getName(), arena ? "the arena's" : "their own");
    }
}
