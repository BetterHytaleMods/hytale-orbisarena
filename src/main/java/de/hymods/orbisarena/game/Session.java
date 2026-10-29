package de.hymods.orbisarena.game;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.Color;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.gamemode.GameModeTypes;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import de.hymods.orbisarena.sim.Arena;
import de.hymods.orbisarena.sim.CardDef;
import com.hypixel.hytale.protocol.MouseButtonState;
import com.hypixel.hytale.protocol.MouseButtonType;
import com.hypixel.hytale.protocol.packets.interface_.HudComponent;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
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
 * One match in a world of its own: builds the arena, brings the players in (they fly over it in the game mode
 * {@code OrbisArena_Flight} while their faction's leader stands on their keep), runs the match 20 times a second
 * (the bot plays an empty side), shows it, takes the players' clicks, and sends everyone home when it is over.
 * Everything but {@link #open} runs on the arena world's thread.
 */
final class Session {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final AtomicInteger NEXT = new AtomicInteger(1);
    private static final double ARRIVAL_WAIT = 25;
    private static final double COUNTDOWN = 3.5;
    private static final double AFTERMATH = 7;
    static final String FLIGHT = "OrbisArena_Flight";
    /** How high above the floor players may fly. */
    private static final double CEILING = 40;
    private static final double EYE_HEIGHT = 1.6;
    /** How far a line of sight may reach to the floor. */
    private static final double MAX_AIM = 90;
    private static final String MARKER = "Dust_Sparkles_Fine";

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
        this.view.start(new boolean[]{false, false});
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

    /** Where a player starts: in the air behind their keep, looking down onto the field towards the enemy. */
    private static Transform arrival(int side) {
        double[] top = Place.keepTop(side);
        double back = -Arena.forward(side) * 5;
        return new Transform(top[0], Place.FLOOR + 12, top[2] + back, (float) Math.toRadians(-35),
                side == 0 ? (float) Math.PI : 0f, 0f);
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
        Transform at = arrival(seat.side);
        store.addComponent(ref, Teleport.getComponentType(),
                Teleport.createExact(at.getPosition(), new Rotation3f(0f, at.getRotation().y(), 0f), at.getRotation()));
        GameModeTypes.exit(ref, store);
        GameModeTypes.enter(ref, store, FLIGHT);
        this.games.zoom().bind(store, ref, seat.uuid);
        seat.hud = new ArenaHud(player, this.match.side(seat.side).faction());
        body.getHudManager().addCustomHud(player, seat.hud);
        this.view(seat, false);
        LOGGER.at(Level.INFO).log("%s arrived in %s on side %d", seat.name, this.name, seat.side);
    }

    /** Free flight with the game's camera and a crosshair, or the view from above with a cursor. */
    private void view(Seat seat, boolean above) {
        seat.above = above;
        Ref<EntityStore> ref = seat.player.getReference();
        Player body = ref == null || !ref.isValid() ? null : ref.getStore().getComponent(ref, Player.getComponentType());
        if (body != null) {
            if (above) {
                body.getHudManager().setVisibleHudComponents(seat.player, HudComponent.Chat, HudComponent.EventTitle,
                        HudComponent.Notifications);
            } else {
                body.getHudManager().setVisibleHudComponents(seat.player, HudComponent.Chat, HudComponent.EventTitle,
                        HudComponent.Notifications, HudComponent.Reticle);
            }
        }
        if (above) {
            this.games.camera().apply(seat.player, seat.side);
        } else {
            Camera.reset(seat.player);
        }
    }

    /** Key 5: switches between free flight and the view from above. */
    void toggleView(java.util.UUID uuid) {
        Seat seat = this.seat(uuid);
        if (seat != null && seat.arrived && !seat.gone) {
            this.view(seat, !seat.above);
        }
    }

    /** Sends the camera again, e.g. after an operator tuned it. */
    void refreshCamera() {
        for (Seat seat : this.seats) {
            if (seat != null && seat.arrived && !seat.gone && seat.above) {
                this.games.camera().apply(seat.player, seat.side);
            }
        }
    }

    /**
     * The tile a player aims at: from above, the block under the cursor; in flight, where the line of sight from
     * the eyes meets the floor (so it works from any height). Null if it misses the field.
     */
    private double[] aim(Seat seat, Vector3i block) {
        if (seat.above && block != null) {
            return new double[]{Place.tileX(block.x()) + 0.5, Place.tileY(block.z()) + 0.5};
        }
        Ref<EntityStore> ref = seat.player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != this.world.getEntityStore().getStore()) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
        if (transform == null || head == null) {
            return null;
        }
        Vector3d eye = transform.getPosition();
        double ey = eye.y + EYE_HEIGHT;
        double pitch = head.getRotation().x();
        double yaw = head.getRotation().y();
        double dx = -Math.sin(yaw) * Math.cos(pitch);
        double dy = Math.sin(pitch);
        double dz = -Math.cos(yaw) * Math.cos(pitch);
        if (dy > -0.02) {
            return null;
        }
        double t = (Place.FLOOR - ey) / dy;
        if (t > MAX_AIM) {
            return null;
        }
        double x = Place.tileX(eye.x + dx * t);
        double y = Place.tileY(eye.z + dz * t);
        return Arena.inside(x, y) ? new double[]{x, y} : null;
    }

    private Seat seat(java.util.UUID uuid) {
        for (Seat seat : this.seats) {
            if (seat != null && seat.uuid.equals(uuid)) {
                return seat;
            }
        }
        return null;
    }

    /** Whether this player still has a seat here to come back to. */
    boolean expects(java.util.UUID uuid) {
        Seat seat = this.seat(uuid);
        return seat != null && !seat.gone;
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
        double[] aim = this.aim(seat, event.getTargetBlock());
        if (aim == null) {
            return;
        }
        double x = aim[0];
        double y = aim[1];
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
        for (Seat seat : this.seats) {
            if (seat == null || !seat.arrived || seat.gone) {
                continue;
            }
            if (this.ticks % 5 == 0) {
                this.bounds(seat);
            }
            if (this.ticks % 4 == 0 && this.state == State.RUNNING && !seat.above) {
                this.marker(seat);
            }
        }
    }

    /** Keeps a flying player over the arena: back inside, gently, if they left the box. */
    private void bounds(Seat seat) {
        Ref<EntityStore> ref = seat.player.getReference();
        if (ref == null || !ref.isValid() || ref.getStore() != this.world.getEntityStore().getStore()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return;
        }
        Vector3d at = transform.getPosition();
        double x = Math.clamp(at.x, ArenaBuilder.minX() + 1, ArenaBuilder.maxX() - 1);
        double y = Math.clamp(at.y, Place.FLOOR - 0.5, Place.FLOOR + CEILING);
        double z = Math.clamp(at.z, ArenaBuilder.minZ() + 1, ArenaBuilder.maxZ() - 1);
        if (x != at.x || y != at.y || z != at.z) {
            HeadRotation head = store.getComponent(ref, HeadRotation.getComponentType());
            Rotation3f look = head != null ? head.getRotation() : transform.getRotation();
            store.addComponent(ref, Teleport.getComponentType(), Teleport.createExact(new Vector3d(x, y, z),
                    new Rotation3f(0f, look.y(), 0f), look));
        }
    }

    /**
     * In flight: a mark on the floor where the crosshair points, only for that player; green where the selected
     * card can go, red where it cannot (or there is not enough essence).
     */
    private void marker(Seat seat) {
        if (seat.slot < 0 || seat.slot > 3) {
            return;
        }
        double[] aim = this.aim(seat, null);
        Ref<EntityStore> ref = seat.player.getReference();
        if (aim == null || ref == null || !ref.isValid()) {
            return;
        }
        CardDef card = this.match.side(seat.side).hand().slot(seat.slot);
        boolean ok = card != null && this.match.canPlace(seat.side, card, aim[0], aim[1])
                && card.cost() <= this.match.side(seat.side).essence();
        Color color = ok ? new Color((byte) 80, (byte) 255, (byte) 120) : new Color((byte) 255, (byte) 70, (byte) 60);
        ParticleUtil.spawnParticleEffect(MARKER, new Vector3d(Place.x(aim[0]), Place.FLOOR + 0.1, Place.z(aim[1])),
                0f, 0f, 0f, 1f, color, List.of(ref), this.world.getEntityStore().getStore());
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
        leaveFlight(store, ref);
        Games.arrivedHome(ref, store);
        World origin = Universe.get().getWorld(seat.originWorld);
        if (origin == null || Worlds.ours(origin)) {
            origin = Universe.get().getDefaultWorld();
        }
        store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(origin, seat.origin));
    }

    /** Out of the arena's game mode and zoom again; also for players who come back after a crash. */
    static void leaveFlight(Store<EntityStore> store, Ref<EntityStore> ref) {
        if (FLIGHT.equals(GameModeTypes.getCurrentTypeId(ref, store))) {
            GameModeTypes.exit(ref, store);
        }
        Zoom.unbind(store, ref);
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
