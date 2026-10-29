package de.hymods.orbisarena.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.hymods.orbisarena.game.Camera;
import de.hymods.orbisarena.game.Games;

import javax.annotation.Nonnull;

/**
 * {@code /arena} joins the lobby (in the lobby: pick a faction again), {@code /arena leave} goes home. For
 * operators: {@code status}, {@code start} (the countdown ends in three seconds), {@code cam <distance> <pitch>}
 * (the view from above), {@code botmatch}.
 */
public final class ArenaCommand extends AbstractPlayerCommand {
    private final Games games;

    public ArenaCommand(Games games) {
        super("arena", "orbisarena.commands.arena.desc");
        this.games = games;
        this.requireNoPermission();
        this.addSubCommand(new Leave(games));
        this.addSubCommand(new Status(games));
        this.addSubCommand(new Start(games));
        this.addSubCommand(new Cam(games));
        this.addSubCommand(new BotMatch(games));
    }

    @Override
    protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                           @Nonnull PlayerRef playerRef, @Nonnull World world) {
        this.games.join(playerRef, ref, store, world);
    }

    private static final class Leave extends AbstractPlayerCommand {
        private final Games games;

        Leave(Games games) {
            super("leave", "orbisarena.commands.arena.leave.desc");
            this.games = games;
            this.requireNoPermission();
        }

        @Override
        protected void execute(@Nonnull CommandContext context, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                               @Nonnull PlayerRef playerRef, @Nonnull World world) {
            this.games.leave(playerRef, ref, store, world);
        }
    }

    private static final class Status extends CommandBase {
        private final Games games;

        Status(Games games) {
            super("status", "orbisarena.commands.arena.status.desc");
            this.games = games;
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            for (String line : this.games.status()) {
                context.sendMessage(Message.raw(line));
            }
        }
    }

    private static final class Start extends CommandBase {
        private final Games games;

        Start(Games games) {
            super("start", "orbisarena.commands.arena.start.desc");
            this.games = games;
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            this.games.startSoon();
        }
    }

    private static final class BotMatch extends CommandBase {
        private final Games games;

        BotMatch(Games games) {
            super("botmatch", "orbisarena.commands.arena.botmatch.desc");
            this.games = games;
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            context.sendMessage(Message.raw(this.games.botMatch(null)));
        }
    }

    private static final class Cam extends CommandBase {
        private final Games games;
        private final RequiredArg<Double> distance = this.withRequiredArg("distance", "orbisarena.commands.arena.cam.desc", ArgTypes.DOUBLE);
        private final RequiredArg<Double> pitch = this.withRequiredArg("pitch", "orbisarena.commands.arena.cam.desc", ArgTypes.DOUBLE);

        Cam(Games games) {
            super("cam", "orbisarena.commands.arena.cam.desc");
            this.games = games;
        }

        @Override
        protected void executeSync(@Nonnull CommandContext context) {
            Camera camera = new Camera(this.distance.get(context), this.pitch.get(context));
            this.games.camera(camera);
            context.sendMessage(Message.raw("camera: " + camera));
        }
    }
}
