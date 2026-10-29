package de.hymods.orbisarena.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.hymods.orbisarena.game.Worlds;
import de.hymods.orbisarena.game.Zoom;

import javax.annotation.Nonnull;

/** {@code /zoom <strength>}: how far holding R zooms in Orbis Arena; {@code /zoom} alone tells the current one. */
public final class ZoomCommand extends AbstractPlayerCommand {
    private final Zoom zoom;
    private final RequiredArg<Double> strength = this.withRequiredArg("strength", "orbisarena.commands.zoom.strength.desc", ArgTypes.DOUBLE);

    public ZoomCommand(Zoom zoom) {
        super("zoom", "orbisarena.commands.zoom.desc");
        this.zoom = zoom;
        this.requireNoPermission();
        this.addUsageVariant(new Show(zoom));
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef, @Nonnull World world) {
        float step = this.zoom.choose(playerRef.getUuid(), this.strength.get(context).floatValue());
        if (Worlds.ours(world)) {
            this.zoom.bind(store, ref, playerRef.getUuid());
        }
        context.sendMessage(Message.raw("Zoom " + Zoom.label(step) + "x"));
    }

    private static final class Show extends AbstractPlayerCommand {
        private final Zoom zoom;

        Show(Zoom zoom) {
            super("orbisarena.commands.zoom.desc");
            this.zoom = zoom;
            this.requireNoPermission();
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef, @Nonnull World world) {
            StringBuilder steps = new StringBuilder();
            for (float step : Zoom.STEPS) {
                steps.append(steps.isEmpty() ? "" : ", ").append(Zoom.label(step));
            }
            context.sendMessage(Message.raw("Zoom " + Zoom.label(this.zoom.of(playerRef.getUuid())) + "x (" + steps + ")"));
        }
    }
}
