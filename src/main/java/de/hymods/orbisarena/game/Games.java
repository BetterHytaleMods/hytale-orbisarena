package de.hymods.orbisarena.game;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
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
import de.hymods.orbisarena.sim.Catalog;
import de.hymods.orbisarena.sim.Faction;
import de.hymods.orbisarena.sim.Rules;
import de.hymods.orbisarena.stash.Stash;
import de.hymods.orbisarena.ui.ArenaHud;
import de.hymods.orbisarena.ui.FactionPage;
import de.hymods.orbisarena.ui.LobbyHud;
import de.hymods.orbisarena.world.ArenaBuilder;
import de.hymods.orbisarena.world.Blocks;
import de.hymods.orbisarena.world.Place;
import de.hymods.orbisarena.world.Zone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Everyone in Orbis Arena: {@code /arena} brings a player into the lobby (their inventory stays outside, see
 * {@code Stash}), where they pick a faction. The countdown starts with the first player (30 s) and drops to 10 s
 * once two are there; then players are paired in the order they came, and whoever is left over plays the bot.
 * Each pair gets a {@link Session} in a world of its own, in a random zone.
 */
public final class Games {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    static final long WAIT_MILLIS = 30_000;
    static final long WAIT_PAIRED_MILLIS = 10_000;
    /** The hotbar slot whose key (5) switches between flying and the view from above. */
    static final int VIEW_KEY = 4;
    private static final Transform LOBBY_SPAWN = new Transform(0.5, Place.FLOOR + 1, 0.5);

    private final Catalog catalog;
    private final Rules rules = Rules.standard();
    private final Random random = new Random();
    /** In the lobby or on the way there, in the order they came. */
    private final Map<UUID, Seat> waiting = new LinkedHashMap<>();
    private final Map<UUID, Session> playing = new ConcurrentHashMap<>();
    private final List<Session> sessions = new ArrayList<>();
    private final Map<UUID, LobbyHud> lobbyHuds = new ConcurrentHashMap<>();
    private CompletableFuture<World> lobby;
    private long startsAt;
    private ScheduledFuture<?> lobbyClock;
    private volatile Camera camera = Camera.STANDARD;

    private final Zoom zoom;

    public Games(Catalog catalog, Zoom zoom) {
        this.catalog = catalog;
        this.zoom = zoom;
    }

    public Zoom zoom() {
        return this.zoom;
    }

    public Camera camera() {
        return this.camera;
    }

    public void camera(Camera camera) {
        this.camera = camera;
        synchronized (this) {
            for (Session session : this.sessions) {
                World world = session.world();
                if (world != null) {
                    world.execute(session::refreshCamera);
                }
            }
        }
    }

    // ---- joining and leaving ----

    /** {@code /arena}: into the lobby, or the faction page again if already there. On the player's world thread. */
    public void join(PlayerRef player, Ref<EntityStore> ref, Store<EntityStore> store, World world) {
        UUID uuid = player.getUuid();
        if (this.playing.containsKey(uuid)) {
            player.sendMessage(Message.raw("You are in a match."));
            return;
        }
        if (Worlds.lobby(world)) {
            this.openPage(player, ref, store);
            return;
        }
        if (Worlds.ours(world)) {
            return;
        }
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        Seat seat = new Seat(player, world.getName(), transform == null ? new Transform(0, 100, 0) : new Transform(transform.getTransform()));
        Stash stash = Stash.type() == null ? null : store.getComponent(ref, Stash.type());
        if (stash == null && Stash.type() != null) {
            stash = new Stash();
            store.putComponent(ref, Stash.type(), stash);
        }
        if (stash != null) {
            Transform at = seat.origin;
            stash.origin(world.getName(), new double[]{at.getPosition().x, at.getPosition().y, at.getPosition().z,
                    at.getRotation().y()});
        }
        synchronized (this) {
            this.waiting.put(uuid, seat);
        }
        this.lobby(world).thenAccept(lobby -> world.execute(() -> {
            Ref<EntityStore> now = player.getReference();
            if (now == null || !now.isValid()) {
                return;
            }
            InstancesPlugin.teleportPlayerToLoadingInstance(now, now.getStore(), CompletableFuture.completedFuture(lobby),
                    null, LOBBY_SPAWN);
        }));
    }

    /** The lobby world, made and built on first use. */
    private synchronized CompletableFuture<World> lobby(World from) {
        World existing = Universe.get().getWorld(Worlds.LOBBY);
        if (existing != null && this.lobby != null && this.lobby.isDone() && !this.lobby.isCompletedExceptionally()) {
            return this.lobby;
        }
        if (this.lobby == null || this.lobby.isCompletedExceptionally() || (this.lobby.isDone() && existing == null)) {
            this.lobby = InstancesPlugin.get().spawnInstance(Worlds.TEMPLATE, Worlds.LOBBY, from, new Transform(0.5, 80, 0.5),
                            config -> {
                            })
                    .thenCompose(world -> Blocks.load(world, -16, -16, 16, 16).thenApply(loaded -> world))
                    .thenCompose(world -> {
                        CompletableFuture<World> built = new CompletableFuture<>();
                        world.execute(() -> {
                            new ArenaBuilder(new Blocks(world), Zone.EMERALD_GROVE).lobby();
                            built.complete(world);
                        });
                        return built;
                    });
            this.lobby.exceptionally(error -> {
                LOGGER.at(Level.SEVERE).withCause(error).log("Cannot open the lobby");
                return null;
            });
        }
        return this.lobby;
    }

    /** {@code /arena leave}: out of the lobby or a match, back to where they came from. */
    public void leave(PlayerRef player, Ref<EntityStore> ref, Store<EntityStore> store, World world) {
        UUID uuid = player.getUuid();
        Session session = this.playing.get(uuid);
        if (session != null) {
            session.left(uuid, true);
            return;
        }
        Seat seat;
        synchronized (this) {
            seat = this.waiting.remove(uuid);
        }
        if (seat != null && Worlds.lobby(world)) {
            this.sendHome(seat, ref, store);
        }
    }

    public void disconnected(UUID uuid) {
        synchronized (this) {
            this.waiting.remove(uuid);
        }
        this.lobbyHuds.remove(uuid);
        Session session = this.playing.get(uuid);
        if (session != null) {
            session.left(uuid, false);
        }
    }

    /** A player is ready in one of our worlds. On that world's thread. */
    public void ready(PlayerRef player, World world) {
        if (Worlds.lobby(world)) {
            this.inLobby(player);
            return;
        }
        Session session = this.playing.get(player.getUuid());
        if (session != null && session.world() == world && session.expects(player.getUuid())) {
            session.arrived(player);
            return;
        }
        // In an arena they no longer play in (back after a lost connection): home.
        Ref<EntityStore> ref = player.getReference();
        if (ref != null && ref.isValid()) {
            rescue(player, ref, ref.getStore());
        }
    }

    private void inLobby(PlayerRef player) {
        Seat seat;
        synchronized (this) {
            seat = this.waiting.get(player.getUuid());
        }
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        if (seat == null) {
            // Back after a lost connection, but no longer waiting: home.
            rescue(player, ref, store);
            return;
        }
        seat.inLobby = true;
        Player body = store.getComponent(ref, Player.getComponentType());
        if (body != null) {
            LobbyHud hud = new LobbyHud(player);
            this.lobbyHuds.put(player.getUuid(), hud);
            body.getHudManager().addCustomHud(player, hud);
        }
        this.openPage(player, ref, store);
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (this.startsAt == 0) {
                this.startsAt = now + WAIT_MILLIS;
            }
            if (this.inLobbyCount() >= 2) {
                this.startsAt = Math.min(this.startsAt, now + WAIT_PAIRED_MILLIS);
            }
            if (this.lobbyClock == null) {
                this.lobbyClock = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(this::lobbyTick, 250, 250, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void openPage(PlayerRef player, Ref<EntityStore> ref, Store<EntityStore> store) {
        Player body = store.getComponent(ref, Player.getComponentType());
        if (body != null) {
            body.getPageManager().openCustomPage(ref, store, new FactionPage(player, this.catalog.factions(), this::picked));
        }
    }

    private void picked(PlayerRef player, String faction) {
        Seat seat;
        synchronized (this) {
            seat = this.waiting.get(player.getUuid());
        }
        if (seat != null && this.catalog.faction(faction) != null) {
            seat.faction = faction;
        }
    }

    private int inLobbyCount() {
        int n = 0;
        for (Seat seat : this.waiting.values()) {
            if (seat.inLobby) {
                n++;
            }
        }
        return n;
    }

    /** {@code /arena start}: the countdown ends in three seconds. */
    public synchronized void startSoon() {
        if (this.startsAt != 0) {
            this.startsAt = Math.min(this.startsAt, System.currentTimeMillis() + 3000);
        }
    }

    /** Four times a second: the lobby's HUDs, and the matches once the countdown is over. */
    private void lobbyTick() {
        World lobby = Universe.get().getWorld(Worlds.LOBBY);
        if (lobby == null) {
            return;
        }
        try {
            lobby.execute(() -> this.lobbyStep(lobby));
        } catch (RuntimeException stopping) {
            // the lobby is going away
        }
    }

    private void lobbyStep(World lobby) {
        List<Seat> here = new ArrayList<>();
        long left;
        synchronized (this) {
            for (Seat seat : this.waiting.values()) {
                if (seat.inLobby) {
                    here.add(seat);
                }
            }
            if (here.isEmpty()) {
                this.startsAt = 0;
                return;
            }
            left = Math.max(0, this.startsAt - System.currentTimeMillis());
        }
        StringBuilder names = new StringBuilder();
        for (Seat seat : here) {
            names.append(names.isEmpty() ? "" : "  ·  ").append(seat.name);
        }
        long seconds = (left + 999) / 1000;
        String time = "%d:%02d".formatted(seconds / 60, seconds % 60);
        for (Seat seat : here) {
            LobbyHud hud = this.lobbyHuds.get(seat.uuid);
            if (hud != null) {
                Faction faction = seat.faction == null ? null : this.catalog.faction(seat.faction);
                hud.update(time, faction == null ? "" : faction.name(), names.toString());
            }
        }
        if (left == 0) {
            this.launch(lobby, here);
        }
    }

    /** Pairs everyone in the lobby in the order they came; the last one alone plays the bot. */
    private void launch(World lobby, List<Seat> here) {
        synchronized (this) {
            for (Seat seat : here) {
                this.waiting.remove(seat.uuid);
            }
            this.startsAt = 0;
        }
        List<Faction> factions = this.catalog.factions();
        for (int i = 0; i < here.size(); i += 2) {
            List<Seat> players = new ArrayList<>(here.subList(i, Math.min(here.size(), i + 2)));
            for (Seat seat : players) {
                if (seat.faction == null) {
                    seat.faction = factions.get(this.random.nextInt(factions.size())).id();
                }
                seat.inLobby = false;
                Ref<EntityStore> ref = seat.player.getReference();
                Player body = ref == null || !ref.isValid() ? null : ref.getStore().getComponent(ref, Player.getComponentType());
                if (body != null) {
                    body.getHudManager().removeCustomHud(seat.player, LobbyHud.KEY);
                }
                this.lobbyHuds.remove(seat.uuid);
            }
            Faction[] bots = new Faction[2];
            if (players.size() == 1) {
                List<Faction> others = new ArrayList<>(factions);
                others.removeIf(f -> f.id().equals(players.getFirst().faction));
                bots[1] = others.get(this.random.nextInt(others.size()));
            }
            Zone zone = Zone.values()[this.random.nextInt(Zone.values().length)];
            Session session = new Session(this, this.catalog, this.rules, players, bots, zone, this.random.nextLong());
            synchronized (this) {
                this.sessions.add(session);
                for (Seat seat : players) {
                    this.playing.put(seat.uuid, session);
                }
            }
            session.open(lobby);
        }
    }

    /** From the lobby into an arena, onto a keep. On the lobby's thread. */
    void sendInto(Seat seat, World arena, Transform at) {
        World lobby = Universe.get().getWorld(Worlds.LOBBY);
        Runnable go = () -> {
            Ref<EntityStore> ref = seat.player.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            InstancesPlugin.teleportPlayerToLoadingInstance(ref, ref.getStore(), CompletableFuture.completedFuture(arena), null, at);
        };
        if (lobby != null) {
            lobby.execute(go);
        }
    }

    /** Back to where they typed {@code /arena}. On the world thread the player is in. */
    private void sendHome(Seat seat, Ref<EntityStore> ref, Store<EntityStore> store) {
        arrivedHome(ref, store);
        Player body = store.getComponent(ref, Player.getComponentType());
        if (body != null) {
            body.getHudManager().removeCustomHud(seat.player, LobbyHud.KEY);
        }
        this.lobbyHuds.remove(seat.uuid);
        World origin = Universe.get().getWorld(seat.originWorld);
        if (origin == null || Worlds.ours(origin)) {
            origin = Universe.get().getDefaultWorld();
        }
        store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(origin, seat.origin));
    }

    /** A session is over (or could not start): its players are free again. */
    void closed(Session session, boolean failed) {
        synchronized (this) {
            this.sessions.remove(session);
            for (Seat seat : session.seats()) {
                if (seat != null) {
                    this.playing.remove(seat.uuid, session);
                    if (failed) {
                        seat.player.sendMessage(Message.raw("The arena could not be opened."));
                    }
                }
            }
        }
    }

    /** Two bots in an arena of their own, for operators to watch or to check that everything works. */
    public String botMatch(String zoneId) {
        List<Faction> factions = this.catalog.factions();
        Faction[] bots = {factions.get(this.random.nextInt(factions.size())), factions.get(this.random.nextInt(factions.size()))};
        Zone zone = zoneId == null ? Zone.values()[this.random.nextInt(Zone.values().length)] : Zone.byId(zoneId);
        if (zone == null) {
            return "unknown zone " + zoneId;
        }
        Session session = new Session(this, this.catalog, this.rules, List.of(), bots, zone, this.random.nextLong());
        synchronized (this) {
            this.sessions.add(session);
        }
        session.open(Universe.get().getDefaultWorld());
        return "opening " + session.name + ": " + bots[0].name() + " vs " + bots[1].name() + " in " + zone.title;
    }

    // ---- input ----

    public void clicked(PlayerMouseButtonEvent event) {
        Ref<EntityStore> ref = event.getPlayerRef();
        PlayerRef player = ref.isValid() ? ref.getStore().getComponent(ref, PlayerRef.getComponentType()) : null;
        Session session = player == null ? null : this.playing.get(player.getUuid());
        if (session != null) {
            session.click(event, player);
        }
    }

    /** Keys 1–4 pick a card; key 5 switches the view. */
    public void selected(UUID uuid, int slot) {
        Session session = this.playing.get(uuid);
        if (session == null) {
            return;
        }
        if (slot == VIEW_KEY) {
            session.toggleView(uuid);
        } else {
            session.select(uuid, slot);
        }
    }

    /**
     * A player is ready in a world not ours: out of the arena's game mode and zoom, should a crash or a lost
     * connection have kept them in it, and back to where they typed {@code /arena} if they never got there.
     */
    public void readyElsewhere(PlayerRef player, Ref<EntityStore> ref, Store<EntityStore> store) {
        Session.leaveFlight(store, ref);
        boolean busy;
        synchronized (this) {
            busy = this.waiting.containsKey(player.getUuid()) || this.playing.containsKey(player.getUuid());
        }
        Stash stash = Stash.type() == null ? null : store.getComponent(ref, Stash.type());
        if (!busy && stash != null && stash.originWorld() != null) {
            rescue(player, ref, store);
        }
    }

    /** Sends a player stranded in Orbis Arena (or kept from going home) to where they typed {@code /arena}. */
    static void rescue(PlayerRef player, Ref<EntityStore> ref, Store<EntityStore> store) {
        Session.leaveFlight(store, ref);
        Camera.reset(player);
        Player body = store.getComponent(ref, Player.getComponentType());
        if (body != null) {
            body.getHudManager().removeCustomHud(player, LobbyHud.KEY);
            body.getHudManager().removeCustomHud(player, ArenaHud.KEY);
            body.getHudManager().resetVisibleHudComponents(player);
        }
        Stash stash = Stash.type() == null ? null : store.getComponent(ref, Stash.type());
        World origin = stash == null || stash.originWorld() == null ? null : Universe.get().getWorld(stash.originWorld());
        double[] at = stash == null ? null : stash.origin();
        if (stash != null) {
            stash.origin(null, null);
        }
        if (origin == null || Worlds.ours(origin) || at == null || at.length < 4) {
            World here = store.getExternalData().getWorld();
            if (Worlds.ours(here)) {
                World home = Universe.get().getDefaultWorld();
                Transform[] spawns = home.getWorldConfig().getSpawnProvider().getSpawnPoints();
                Transform spawn = spawns != null && spawns.length > 0 ? spawns[0] : new Transform(0, 100, 0);
                store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(home, spawn));
            }
            return;
        }
        store.addComponent(ref, Teleport.getComponentType(),
                Teleport.createForPlayer(origin, new Transform(at[0], at[1], at[2], 0f, (float) at[3], 0f)));
    }

    /** The origin is reached: no rescue needed any more. */
    static void arrivedHome(Ref<EntityStore> ref, Store<EntityStore> store) {
        Stash stash = Stash.type() == null ? null : store.getComponent(ref, Stash.type());
        if (stash != null) {
            stash.origin(null, null);
        }
    }

    // ---- operators ----

    public synchronized List<String> status() {
        List<String> lines = new ArrayList<>();
        StringBuilder lobby = new StringBuilder("lobby: ");
        for (Seat seat : this.waiting.values()) {
            lobby.append(seat.name).append(seat.inLobby ? "" : " (on the way)").append(seat.faction == null ? "" : " [" + seat.faction + "]").append(", ");
        }
        if (this.startsAt != 0) {
            lobby.append("starts in ").append(Math.max(0, (this.startsAt - System.currentTimeMillis()) / 1000)).append(" s");
        }
        lines.add(lobby.toString());
        for (Session session : this.sessions) {
            lines.add(session.describe());
        }
        lines.add("camera: " + this.camera);
        return lines;
    }

    /** Server going down: everyone out. */
    public void shutdown() {
        List<Session> open;
        synchronized (this) {
            open = new ArrayList<>(this.sessions);
            if (this.lobbyClock != null) {
                this.lobbyClock.cancel(false);
            }
        }
        for (Session session : open) {
            World world = session.world();
            if (world != null) {
                try {
                    world.execute(session::close);
                } catch (RuntimeException stopping) {
                    // already gone
                }
            }
        }
    }
}
