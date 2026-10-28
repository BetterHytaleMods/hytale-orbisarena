package de.hymods.orbisarena.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.hymods.orbisarena.sim.Faction;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.function.BiConsumer;

/** The four factions side by side; a click picks one and closes the page. */
public final class FactionPage extends InteractiveCustomUIPage<FactionPage.Event> {

    public static final class Event {
        static final BuilderCodec<Event> CODEC = BuilderCodec.builder(Event.class, Event::new)
                .append(new KeyedCodec<>("Faction", Codec.STRING), (event, s) -> event.faction = s, event -> event.faction)
                .add()
                .build();
        private String faction;
    }

    private final List<Faction> factions;
    private final BiConsumer<PlayerRef, String> picked;

    public FactionPage(PlayerRef player, List<Faction> factions, BiConsumer<PlayerRef, String> picked) {
        super(player, CustomPageLifetime.CanDismiss, Event.CODEC);
        this.factions = factions;
        this.picked = picked;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commands, @Nonnull UIEventBuilder events,
                      @Nonnull Store<EntityStore> store) {
        commands.append("OrbisArena/Factions.ui");
        for (int i = 0; i < this.factions.size() && i < 4; i++) {
            commands.set("#Name" + i + ".Text", this.factions.get(i).name());
            events.addEventBinding(CustomUIEventBindingType.Activating, "#Pick" + i,
                    new EventData().append("Faction", this.factions.get(i).id()), false);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Event event) {
        if (event.faction != null) {
            this.picked.accept(this.playerRef, event.faction);
            this.close();
        }
    }
}
