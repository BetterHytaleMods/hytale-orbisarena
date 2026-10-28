package de.hymods.orbisarena.game;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.protocol.MouseButtonType;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import de.hymods.orbisarena.sim.Bot;
import de.hymods.orbisarena.sim.Catalog;
import de.hymods.orbisarena.sim.Faction;
import de.hymods.orbisarena.sim.Match;
import de.hymods.orbisarena.sim.MatchEvent;
import de.hymods.orbisarena.sim.Rules;
import de.hymods.orbisarena.ui.ArenaHud;
import de.hymods.orbisarena.view.View;
import de.hymods.orbisarena.world.ArenaBuilder;
import de.hymods.orbisarena.world.Blocks;
import de.hymods.orbisarena.world.Place;
import de.hymods.orbisarena.world.Zone;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * One match in a world of its own: builds the arena, brings the players onto their keeps, runs the match 20
 * times a second (the bot plays an empty side), shows it, takes the players' clicks, and sends everyone home
 * when it is over. Everything but {@link #open} runs on the arena world's thread.
 */
final class Session {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final AtomicInteger NEXT = new AtomicInteger(1);
    private static final double ARRIVAL_WAIT = 25;
    private static final double COUNTDOWN = 3.5;
    private static final double AFTERMATH = 7;
    private static final String HOLD = "OrbisArena_Hold";

    enum State {
        OPENING, ARRIVING, COUNTDOWN, RUNNING, ENDED, CLOSED
    }

    private final Games games;
    final String name;
    private final Seat[] seats = new Seat[2];
    private final Zone zone;
    private final Match match;
    /** The bot of each side nobody plays. */
    private final Bot[] bots = new Bot[2];
    private Runnable release = () -> {
    };
    private World world;
    private View view;
    private State state = State.OPENING;
    private ScheduledFuture<?> ticker;
    private double clock;
    private double stateSince;
    private int lastCount = -1;
    private int ticks;
    private int forfeitWinner = -2;

    /** Players take side 0 and then side 1; {@code botFactions} fills the sides nobody takes. */
    Session(Games games, Catalog catalog, Rules rules, List<Seat> players, Faction[] botFactions, Zone zone, long seed) {
        this.games = games;
        this.name = Worlds.PREFIX + NEXT.getAndIncrement() + "_" + Long.toHexString(seed & 0xffff);
        this.zone = zone;
        for (int i = 0; i < players.size() && i < 2; i++) {
            this.seats[i] = players.get(i);
            this.seats[i].side = i;
        }
        Faction[] factions = new Faction[2];
        for (int s = 0; s < 2; s++) {
            factions[s] = this.seats[s] != null ? catalog.faction(this.seats[s].faction) : botFactions[s];
        }
        this.match = new Match(catalog, rules, factions[0], factions[1], seed);
        for (int s = 0; s < 2; s++) {
            if (this.seats[s] == null) {
                this.bots[s] = new Bot(this.match, s, seed + 1 + s);
            }
        }
    }

    Seat[] seats() {
        return this.seats;
    }

    State state() {
        return this.state;
    }

    World world() {
        return this.world;
    }

    String describe() {
        String a = this.seats[0] != null ? this.seats[0].name : "bot";
        String b = this.seats[1] != null ? this.seats[1].name : "bot";
        return "%s: %s (%s) vs %s (%s), %s, %s, %.0f s, crowns %d:%d".formatted(this.name, a,
                this.match.side(0).faction().name(), b, this.match.side(1).faction().name(), this.zone.title, this.state,
                this.match.time(), this.match.side(0).crowns(), this.match.side(1).crowns())
                + (this.view == null ? "" : "; puppets: " + this.view.census() + "; sim: " + this.match.units().size() + " units");
    }

    /** Makes the arena's world next to the lobby, builds it, and fetches the players. */
    void open(World lobby) {
        InstancesPlugin.get().spawnInstance(Worlds.TEMPLATE, this.name, lobby, new Transform(0.5, 80, 0.5), config -> {
                })
                .thenCompose(world -> Blocks.load(world, ArenaBuilder.minX(), ArenaBuilder.minZ(), ArenaBuilder.maxX(),
                        ArenaBuilder.maxZ()).thenApply(loaded -> world))
                .thenAccept(world -> world.execute(() -> this.build(world)))
                .exceptionally(error -> {
                    LOGGER.at(Level.SEVERE).withCause(error).log("Cannot open %s", this.name);
                    this.games.closed(this, true);
                    return null;
                });
    }

    private void build(World world) {
        this.world = world;
        ArenaBuilder builder = new ArenaBuilder(new Blocks(world), this.zone);
        builder.field(this.match.towers());
        // Ticking land even where no player stands (a match of two bots; puppets need ticking land).
        this.release = Blocks.hold(world, ArenaBuilder.minX(), ArenaBuilder.minZ(), ArenaBuilder.maxX(), ArenaBuilder.maxZ());
        this.view = new View(world, this.match, builder);
        this.view.start(new boolean[]{this.seats[0] != null, this.seats[1] != null});
        this.enter(State.ARRIVING);
        for (Seat seat : this.seats) {
            if (seat != null) {
                this.games.sendInto(seat, world, arrival(seat.side));
            }
        }
        this.ticker = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                world.execute(this::tick);
            } catch (RuntimeException stopped) {
                this.stopTicking();
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
        LOGGER.at(Level.INFO).log("Opened %s", this.describe());
    }

    private static Transform arrival(int side) {
        double[] top = Place.keepTop(side);
        return new Transform(top[0], top[1], top[2], 0f, side == 0 ? (float) Math.PI : 0f, 0f);
    }

    private void enter(State state) {
        this.state = state;
        this.stateSince = this.clock;
    }

    private double inState() {
        return this.clock - this.stateSince;
    }

    /** A player stands in the arena: camera, HUD, onto the keep. */
    void arrived(PlayerRef player) {
        Seat seat = this.seat(player.getUuid());
        if (seat == null || seat.gone || this.world == null) {
            return;
        }
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        Player body = store.getComponent(ref, Player.getComponentType());
        if (body == null) {
            return;
        }
        seat.arrived = true;
        store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(arrival(seat.side)));
        body.getHudManager().setVisibleHudComponents(player, HudComponent.Chat, HudComponent.EventTitle,
                HudComponent.Notifications);
        seat.hud = new ArenaHud(player, this.match.side(seat.side).faction());
        body.getHudManager().addCustomHud(player, seat.hud);
        this.games.camera().apply(player, seat.side);
        this.hold(seat);
        LOGGER.at(Level.INFO).log("%s arrived in %s on side %d", seat.name, this.name, seat.side);
    }

    /** Sends the camera again, e.g. after an operator tuned it. */
    void refreshCamera() {
        for (Seat seat : this.seats) {
            if (seat != null && seat.arrived && !seat.gone) {
                this.games.camera().apply(seat.player, seat.side);
            }
        }
    }

    private Seat seat(java.util.UUID uuid) {
        for (Seat seat : this.seats) {
            if (seat != null && seat.uuid.equals(uuid)) {
                return seat;
            }
        }
        return null;
    }

    void select(java.util.UUID uuid, int slot) {
        Seat seat = this.seat(uuid);
        if (seat != null) {
            seat.slot = slot;
        }
    }

    void click(PlayerMouseButtonEvent event, PlayerRef player) {
        Seat seat = this.seat(player.getUuid());
        if (seat == null || this.state != State.RUNNING || event.getMouseButton() == null
                || event.getMouseButton().state != MouseButtonState.Released) {
            return;
        }
        Vector3i block = event.getTargetBlock();
        if (block == null) {
            return;
        }
        double x = Place.tileX(block.x()) + 0.5;
        double y = Place.tileY(block.z()) + 0.5;
        MouseButtonType button = event.getMouseButton().mouseButtonType;
        if (button == MouseButtonType.Left) {
            if (seat.slot < 0 || seat.slot > 3) {
                return;
            }
            Match.Play play = this.match.play(seat.side, seat.slot, x, y);
            if (play != Match.Play.OK) {
                this.flash(seat, play == Match.Play.NO_ESSENCE ? "Not enough essence" : "Not there");
            }
        } else if (button == MouseButtonType.Right) {
            Match.Play play = this.match.ability(seat.side, x, y);
            if (play == Match.Play.BAD_PLACE) {
                this.flash(seat, "Not there");
            }
        }
    }

    private void flash(Seat seat, String text) {
        EventTitleUtil.showEventTitleToPlayer(seat.player, Message.raw(""), Message.raw(text), false, null, 0.8f, 0.05f, 0.2f);
    }

    private void tick() {
        if (this.state == State.CLOSED) {
            return;
        }
        this.clock += Match.DT;
        this.ticks++;
        switch (this.state) {
            case ARRIVING -> {
                boolean all = true;
                for (Seat seat : this.seats) {
                    if (seat != null && !seat.arrived && !seat.gone) {
                        all = false;
                    }
                }
                if (this.inState() > 1.0 && (all || this.inState() > ARRIVAL_WAIT)) {
                    this.enter(State.COUNTDOWN);
                }
            }
            case COUNTDOWN -> {
                int count = (int) Math.ceil(COUNTDOWN - this.inState() - 0.5);
                if (count != this.lastCount) {
                    this.lastCount = count;
                    this.titleAll(count > 0 ? Integer.toString(count) : "Fight!", "", count <= 0);
                }
                if (this.inState() >= COUNTDOWN) {
                    this.enter(State.RUNNING);
                }
            }
            case RUNNING -> {
                for (Bot bot : this.bots) {
                    if (bot != null) {
                        bot.tick();
                    }
                }
                this.match.tick();
                List<MatchEvent> events = this.match.drainEvents();
                this.view.apply(events);
                for (MatchEvent event : events) {
                    switch (event) {
                        case MatchEvent.DoubleEssence e -> this.titleAll("x2", "Essence", false);
                        case MatchEvent.PhaseChanged e when e.phase() == Match.Phase.OVERTIME ->
                                this.titleAll("Overtime", "Sudden death", true);
                        case MatchEvent.MatchEnded e -> this.ended(e.winner());
                        default -> {
                        }
                    }
                }
            }
            case ENDED -> {
                if (this.inState() >= AFTERMATH) {
                    this.close();
                    return;
                }
            }
            default -> {
            }
        }
        if (this.view != null && (this.state == State.RUNNING || this.state == State.ENDED)) {
            this.view.sync();
        }
        if (this.ticks % 3 == 0) {
            for (Seat seat : this.seats) {
                if (seat != null && seat.hud != null && !seat.gone && this.state != State.ARRIVING) {
                    seat.hud.update(this.match, seat.side, seat.slot);
                }
            }
        }
        if (this.ticks % 5 == 0 && this.state != State.ENDED) {
            for (Seat seat : this.seats) {
                if (seat != null && seat.arrived && !seat.gone) {
                    this.hold(seat);
                }
            }
        }
    }

    /** Keeps a player on their keep: no walking, and back on top should they have got off it. */
    private void hold(Seat seat) {
        Ref<EntityStore> ref = seat.player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != this.world.getEntityStore().getStore()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(HOLD);
        EffectControllerComponent effects = effect == null ? null : store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (effects != null) {
            effects.addEffect(ref, effect, store);
        }
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        double[] top = Place.keepTop(seat.side);
        if (transform != null) {
            Vector3d at = transform.getPosition();
            if (Math.abs(at.x - top[0]) > 1.2 || Math.abs(at.z - top[2]) > 1.2 || at.y < top[1] - 1) {
                store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(arrival(seat.side)));
            }
        }
    }

    private void titleAll(String primary, String secondary, boolean major) {
        for (Seat seat : this.seats) {
            if (seat != null && seat.arrived && !seat.gone) {
                EventTitleUtil.showEventTitleToPlayer(seat.player, Message.raw(primary), Message.raw(secondary), major,
                        null, 1.6f, 0.15f, 0.4f);
            }
        }
    }

    private void ended(int winner) {
        if (this.state == State.ENDED || this.state == State.CLOSED) {
            return;
        }
        this.enter(State.ENDED);
        String score = this.match.side(0).crowns() + " : " + this.match.side(1).crowns();
        for (Seat seat : this.seats) {
            if (seat != null && seat.arrived && !seat.gone) {
                String title = winner == -1 ? "Draw" : winner == seat.side ? "Victory" : "Defeat";
                EventTitleUtil.showEventTitleToPlayer(seat.player, Message.raw(title), Message.raw(score), true, null,
                        (float) AFTERMATH - 1, 0.3f, 0.6f);
            }
        }
        LOGGER.at(Level.INFO).log("%s ended: winner %d, crowns %s", this.name, winner, score);
    }

    /** A player left (by command or disconnect): the other side wins. On any thread. */
    void left(java.util.UUID uuid, boolean sendHome) {
        Runnable leave = () -> {
            Seat seat = this.seat(uuid);
            if (seat == null || seat.gone) {
                return;
            }
            if (sendHome && seat.arrived) {
                this.home(seat);
            }
            seat.gone = true;
            if (this.state.ordinal() < State.ENDED.ordinal()) {
                this.forfeitWinner = 1 - seat.side;
                boolean anyone = false;
                for (Seat other : this.seats) {
                    anyone |= other != null && !other.gone;
                }
                if (anyone) {
                    this.ended(this.forfeitWinner);
                } else {
                    this.close();
                }
            }
        };
        if (this.world != null) {
            try {
                this.world.execute(leave);
                return;
            } catch (RuntimeException stopping) {
                // the world is gone: nothing to tidy up there
            }
        }
        Seat seat = this.seat(uuid);
        if (seat != null) {
            seat.gone = true;
        }
    }

    /** Sends one player home: camera and HUD back, out of the arena. */
    private void home(Seat seat) {
        PlayerRef player = seat.player;
        Ref<EntityStore> ref = player.getReference();
        Camera.reset(player);
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        Player body = store.getComponent(ref, Player.getComponentType());
        if (body != null) {
            body.getHudManager().removeCustomHud(player, ArenaHud.KEY);
            body.getHudManager().resetVisibleHudComponents(player);
        }
        EffectControllerComponent effects = store.getComponent(ref, EffectControllerComponent.getComponentType());
        int hold = EntityEffect.getAssetMap().getIndex(HOLD);
        if (effects != null && hold != Integer.MIN_VALUE) {
            effects.removeEffect(ref, hold, store);
        }
        World origin = Universe.get().getWorld(seat.originWorld);
        if (origin == null || Worlds.ours(origin)) {
            origin = Universe.get().getDefaultWorld();
        }
        store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(origin, seat.origin));
    }

    /** Everyone home, puppets away, the world removed. */
    void close() {
        if (this.state == State.CLOSED) {
            return;
        }
        this.state = State.CLOSED;
        for (Seat seat : this.seats) {
            if (seat != null && !seat.gone) {
                if (seat.arrived) {
                    this.home(seat);
                }
                seat.gone = true;
            }
        }
        if (this.view != null) {
            this.view.clear();
        }
        this.release.run();
        this.stopTicking();
        this.games.closed(this, false);
        World world = this.world;
        if (world != null) {
            CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute(() -> {
                if (Universe.get().getWorld(world.getName()) != null) {
                    Universe.get().removeWorld(world.getName());
                }
            });
        }
        LOGGER.at(Level.INFO).log("Closed %s", this.name);
    }

    private void stopTicking() {
        if (this.ticker != null) {
            this.ticker.cancel(false);
        }
    }
}
