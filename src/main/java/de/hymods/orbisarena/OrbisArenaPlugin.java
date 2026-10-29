package de.hymods.orbisarena;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.hymods.orbisarena.command.ArenaCommand;
import de.hymods.orbisarena.command.ZoomCommand;
import de.hymods.orbisarena.game.Games;
import de.hymods.orbisarena.game.SlotSystem;
import de.hymods.orbisarena.game.Worlds;
import de.hymods.orbisarena.game.Zoom;
import de.hymods.orbisarena.sim.Catalog;
import de.hymods.orbisarena.stash.Stash;
import de.hymods.orbisarena.stash.StashSwap;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/** Orbis Arena: a lane battler minigame. {@code /arena} to play. */
public class OrbisArenaPlugin extends JavaPlugin {
    private Games games;

    public OrbisArenaPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        Catalog catalog = Catalog.load();
        Zoom zoom = new Zoom(this.getDataDirectory().resolve("zoom.json"));
        this.games = new Games(catalog, zoom);
        ComponentType<EntityStore, Stash> stash = this.getEntityStoreRegistry().registerComponent(Stash.class, "OrbisArenaStash", Stash.CODEC);
        Stash.type(stash);
        StashSwap swap = new StashSwap(stash);
        this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, swap::onArrive);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> {
            Ref<EntityStore> ref = event.getPlayerRef();
            if (!ref.isValid()) {
                return;
            }
            World world = ref.getStore().getExternalData().getWorld();
            PlayerRef player = ref.getStore().getComponent(ref, PlayerRef.getComponentType());
            if (player != null && Worlds.ours(world)) {
                world.execute(() -> this.games.ready(player, world));
            } else if (player != null) {
                world.execute(() -> {
                    if (ref.isValid()) {
                        this.games.readyElsewhere(player, ref, ref.getStore());
                    }
                });
            }
        });
        this.getEventRegistry().register(PlayerMouseButtonEvent.class, this.games::clicked);
        this.getEventRegistry().register(PlayerDisconnectEvent.class, event -> this.games.disconnected(event.getPlayerRef().getUuid()));
        this.getEntityStoreRegistry().registerSystem(new SlotSystem(this.games));
        this.getCommandRegistry().registerCommand(new ArenaCommand(this.games));
        this.getCommandRegistry().registerCommand(new ZoomCommand(zoom));
        this.getLogger().at(Level.INFO).log("Orbis Arena: %d factions", catalog.factions().size());
    }

    @Override
    protected void shutdown() {
        if (this.games != null) {
            this.games.shutdown();
        }
    }
}
