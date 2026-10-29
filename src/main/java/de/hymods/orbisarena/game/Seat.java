package de.hymods.orbisarena.game;

import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import de.hymods.orbisarena.ui.ArenaHud;

import java.util.UUID;

/** A player in Orbis Arena: where they came from, the faction they picked, their seat in a match. */
final class Seat {
    final UUID uuid;
    final String name;
    final PlayerRef player;
    /** The world and spot they typed {@code /arena} in; they go back there. */
    final String originWorld;
    final Transform origin;
    volatile String faction;
    volatile boolean inLobby;
    int side;
    /** Active hotbar slot; 0–3 picks a card in hand. */
    int slot;
    boolean arrived;
    /** In the view from above (key 5) rather than flying with the game's camera. */
    boolean above;
    boolean gone;
    ArenaHud hud;

    Seat(PlayerRef player, String originWorld, Transform origin) {
        this.uuid = player.getUuid();
        this.name = player.getUsername();
        this.player = player;
        this.originWorld = originWorld;
        this.origin = origin;
    }
}
