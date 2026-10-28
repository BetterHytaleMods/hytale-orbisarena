package de.hymods.orbisarena.game;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.InventorySetActiveSlotEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;

/** Keys 1–4 (the hotbar's active slot) pick a card in hand. */
public final class SlotSystem extends EntityEventSystem<EntityStore, InventorySetActiveSlotEvent> {
    private static final int HOTBAR = -1;
    private final Games games;

    public SlotSystem(Games games) {
        super(InventorySetActiveSlotEvent.class);
        this.games = games;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> buffer, @Nonnull InventorySetActiveSlotEvent event) {
        if (event.getInventorySectionId() != HOTBAR || !Worlds.ours(store.getExternalData().getWorld())) {
            return;
        }
        PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
        if (player != null) {
            this.games.selected(player.getUuid(), event.getNewSlot());
        }
    }
}
