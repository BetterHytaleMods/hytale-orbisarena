package de.hymods.orbisarena.stash;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A player's own inventory while they are in Orbis Arena, saved with the player: they go into the lobby with
 * empty hands and get everything back when they come out, also after a crash or a lost connection. What does not
 * fit back (a smaller backpack) stays here and comes along next time; nothing is ever dropped.
 */
public final class Stash implements Component<EntityStore> {
    private static final Codec<Map<String, ItemContainer>> SECTIONS_CODEC = new MapCodec<>(ItemContainer.CODEC, HashMap::new);

    public static final BuilderCodec<Stash> CODEC = BuilderCodec.builder(Stash.class, Stash::new)
            .<Boolean>append(new KeyedCodec<>("InArena", Codec.BOOLEAN), (s, v) -> s.inArena = v, s -> s.inArena).add()
            .<Map<String, ItemContainer>>append(new KeyedCodec<>("Kept", SECTIONS_CODEC), (s, v) -> s.kept = new HashMap<>(v), s -> s.kept).add()
            .<Map<String, ItemContainer>>append(new KeyedCodec<>("Leftover", SECTIONS_CODEC), (s, v) -> s.leftover = new HashMap<>(v), s -> s.leftover).add()
            .build();

    /** The game's inventory sections, by the names it saves them under. */
    private static final Map<String, Supplier<ComponentType<EntityStore, ? extends InventoryComponent>>> SECTIONS = Map.of(
            "Hotbar", InventoryComponent.Hotbar::getComponentType,
            "Storage", InventoryComponent.Storage::getComponentType,
            "Armor", InventoryComponent.Armor::getComponentType,
            "Utility", InventoryComponent.Utility::getComponentType,
            "Backpack", InventoryComponent.Backpack::getComponentType,
            "Tool", InventoryComponent.Tool::getComponentType,
            "AbilitySlots", InventoryComponent.AbilitySlots::getComponentType,
            "RuneBag", InventoryComponent.RuneBag::getComponentType);

    /** Whether the arena's (empty) inventory is the one in their hands. */
    boolean inArena;
    Map<String, ItemContainer> kept = new HashMap<>();
    Map<String, ItemContainer> leftover = new HashMap<>();

    public Stash() {
    }

    @Override
    public Component<EntityStore> clone() {
        Stash copy = new Stash();
        copy.inArena = this.inArena;
        copy.kept = new HashMap<>(this.kept);
        copy.leftover = new HashMap<>(this.leftover);
        return copy;
    }

    /** Puts the inventory in their hands away and takes out the other one. On the holder, before they enter a world. */
    void swap(Holder<EntityStore> holder) {
        Map<String, ItemContainer> carried = new HashMap<>();
        Map<String, ItemContainer> left = new HashMap<>();
        for (Map.Entry<String, Supplier<ComponentType<EntityStore, ? extends InventoryComponent>>> section : SECTIONS.entrySet()) {
            InventoryComponent component = holder.getComponent(section.getValue().get());
            if (component == null) {
                continue;
            }
            ItemContainer live = component.getInventory();
            short capacity = live.getCapacity();
            if (capacity > 0) {
                carried.put(section.getKey(), ItemContainer.copy(live, new SimpleItemContainer(capacity), null));
                live.clear();
            }
            List<ItemStack> over = new ArrayList<>();
            ItemContainer back = this.kept.get(section.getKey());
            if (back != null) {
                back.forEach((slot, stack) -> {
                    if (ItemStack.isEmpty(stack)) {
                        return;
                    }
                    if (slot < live.getCapacity()) {
                        live.setItemStackForSlot(slot, stack);
                    } else {
                        over.add(stack);
                    }
                });
            }
            ItemContainer before = this.leftover.get(section.getKey());
            if (before != null) {
                before.forEach((slot, stack) -> {
                    if (ItemStack.isEmpty(stack)) {
                        return;
                    }
                    ItemStack rest = capacity > 0 ? live.addItemStack(stack).getRemainder() : stack;
                    if (!ItemStack.isEmpty(rest)) {
                        over.add(rest);
                    }
                });
            }
            if (!over.isEmpty()) {
                SimpleItemContainer rest = new SimpleItemContainer((short) over.size());
                for (short i = 0; i < over.size(); i++) {
                    rest.setItemStackForSlot(i, over.get(i));
                }
                left.put(section.getKey(), rest);
            }
        }
        this.kept = carried;
        this.leftover = left;
        this.inArena = !this.inArena;
    }
}
