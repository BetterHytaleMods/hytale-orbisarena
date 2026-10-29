package de.hymods.orbisarena.game;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Holding R zooms in the arena, the same way FlexiisGoggles does: a root interaction per strength
 * ({@code Server/Item/RootInteractions/OrbisArena/Root_OrbisArena_Zoom_<n>x.json}) on the player's own
 * {@code Ability3} input. {@code /zoom <n>} picks the strength; it is remembered per player in {@code zoom.json}.
 */
public final class Zoom {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    public static final float[] STEPS = {1.5f, 2f, 3f, 4f, 5f, 6f, 8f};
    public static final float DEFAULT = 3f;
    private static final String PREFIX = "Root_OrbisArena_Zoom";
    private static final InteractionType INPUT = InteractionType.Ability3;

    private final Path file;
    private final Map<UUID, Float> chosen = new ConcurrentHashMap<>();

    public Zoom(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                Map<String, Float> read = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Float>>() {
                        }.getType());
                if (read != null) {
                    read.forEach((uuid, value) -> this.chosen.put(UUID.fromString(uuid), snap(value)));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Cannot read %s", file);
        }
    }

    public static float snap(float value) {
        float best = STEPS[0];
        for (float step : STEPS) {
            if (Math.abs(step - value) < Math.abs(best - value)) {
                best = step;
            }
        }
        return best;
    }

    public static String label(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    static String root(float value) {
        return PREFIX + "_" + label(value).replace('.', '_') + "x";
    }

    public float of(UUID player) {
        return this.chosen.getOrDefault(player, DEFAULT);
    }

    /** Remembers a player's strength; returns the step it became. */
    public float choose(UUID player, float value) {
        float step = snap(value);
        this.chosen.put(player, step);
        Map<String, Float> out = new HashMap<>();
        this.chosen.forEach((uuid, v) -> out.put(uuid.toString(), v));
        try {
            Files.createDirectories(this.file.getParent());
            Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            Files.writeString(temp, new Gson().toJson(out), StandardCharsets.UTF_8);
            Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.at(Level.WARNING).withCause(e).log("Cannot write %s", this.file);
        }
        return step;
    }

    /** Puts the player's zoom on R, unless something not ours is bound there. On the player's world thread. */
    public void bind(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref, UUID player) {
        Interactions existing = accessor.getComponent(ref, Interactions.getComponentType());
        Interactions interactions = existing != null ? existing : new Interactions();
        String current = interactions.getInteractionId(INPUT);
        if (current != null && !current.startsWith(PREFIX)) {
            return;
        }
        interactions.setInteractionId(INPUT, root(this.of(player)));
        if (existing == null) {
            accessor.putComponent(ref, Interactions.getComponentType(), interactions);
        }
    }

    /** Takes our zoom off R again; anything else bound there stays. */
    public static void unbind(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) {
        Interactions interactions = accessor.getComponent(ref, Interactions.getComponentType());
        String current = interactions == null ? null : interactions.getInteractionId(INPUT);
        if (current == null || !current.startsWith(PREFIX)) {
            return;
        }
        interactions.removeInteractionId(INPUT);
        if (interactions.isEmpty() && !interactions.isOverrideAll()) {
            accessor.tryRemoveComponent(ref, Interactions.getComponentType());
        }
    }
}
