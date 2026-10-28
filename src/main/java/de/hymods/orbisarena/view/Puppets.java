package de.hymods.orbisarena.view;

import com.hypixel.hytale.component.NonSerialized;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.AnimationSlot;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.AnimationUtils;
import com.hypixel.hytale.server.core.entity.Frozen;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.HealthRegenState;
import com.hypixel.hytale.server.core.modules.entity.component.Invulnerable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import org.joml.Vector3d;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/**
 * NPCs of the game as puppets: frozen (their own AI never runs), invulnerable, never saved; the match moves
 * them, animates them and keeps their health bar. On the arena world's thread, outside the store's tick.
 */
final class Puppets {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    static final String EMPTY_ROLE = "Empty_Role";
    private static final String[] TEAM = {"OrbisArena_Team_Blue", "OrbisArena_Team_Red"};
    private static final Set<String> MISSING = new HashSet<>();

    private final Store<EntityStore> store;

    Puppets(Store<EntityStore> store) {
        this.store = store;
    }

    /**
     * A puppet of a model, made from a role of the game (it equips that role's weapon), tinted for a side
     * (-1 for none). Null if the model or role is unknown or the land is not loaded.
     */
    Ref<EntityStore> spawn(String role, String modelId, double scale, double x, double y, double z, float yaw, int side) {
        NPCPlugin npcs = NPCPlugin.get();
        int roleIndex = role == null ? -1 : npcs.getIndex(role);
        if (roleIndex < 0) {
            roleIndex = npcs.getIndex(EMPTY_ROLE);
        }
        ModelAsset asset = ModelAsset.getAssetMap().getAsset(modelId);
        if (asset == null || roleIndex < 0) {
            if (MISSING.add(modelId + "/" + role)) {
                LOGGER.at(Level.WARNING).log("Cannot make a puppet of model %s with role %s", modelId, role);
            }
            return null;
        }
        Model model = Model.createScaledModel(asset, (float) scale);
        Rotation3f rotation = new Rotation3f(0f, yaw, 0f);
        var spawned = npcs.spawnEntity(this.store, roleIndex, new Vector3d(x, y, z), rotation, model,
                (npc, holder, store) -> {
                    holder.ensureComponent(Frozen.getComponentType());
                    holder.ensureComponent(Invulnerable.getComponentType());
                    holder.addComponent(EntityStore.REGISTRY.getNonSerializedComponentType(), NonSerialized.get());
                }, null);
        if (spawned == null) {
            if (MISSING.add("spawn:" + modelId)) {
                LOGGER.at(Level.WARNING).log("No puppet of %s at %.1f %.1f %.1f (land not ticking?)", modelId, x, y, z);
            }
            return null;
        }
        Ref<EntityStore> ref = spawned.first();
        HealthRegenState regen = this.store.getComponent(ref, HealthRegenState.getComponentType());
        if (regen != null) {
            regen.setRegenEnabled(false);
        }
        if (side >= 0) {
            this.effect(ref, TEAM[side]);
        }
        this.animate(ref, AnimationSlot.Movement, null, "Idle");
        return ref;
    }

    void effect(Ref<EntityStore> ref, String id) {
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(id);
        EffectControllerComponent effects = effect == null || !ref.isValid() ? null
                : this.store.getComponent(ref, EffectControllerComponent.getComponentType());
        if (effects != null) {
            effects.addEffect(ref, effect, this.store);
        }
    }

    /** Moves a puppet and turns body and head; tells the client whether it walks, runs, flies or stands. */
    void move(Ref<EntityStore> ref, double x, double y, double z, float yaw, boolean moving, boolean fast, boolean air) {
        if (!ref.isValid()) {
            return;
        }
        TransformComponent transform = this.store.getComponent(ref, TransformComponent.getComponentType());
        if (transform != null) {
            transform.setPosition(new Vector3d(x, y, z));
            transform.setRotation(new Rotation3f(0f, yaw, 0f));
        }
        HeadRotation head = this.store.getComponent(ref, HeadRotation.getComponentType());
        if (head != null) {
            head.setRotation(new Rotation3f(0f, yaw, 0f));
        }
        MovementStatesComponent movement = this.store.getComponent(ref, MovementStatesComponent.getComponentType());
        if (movement != null) {
            MovementStates states = movement.getMovementStates();
            states.idle = !moving;
            states.horizontalIdle = !moving;
            states.walking = moving && !fast;
            states.running = moving && fast;
            states.onGround = !air;
            states.flying = air;
            states.falling = false;
        }
    }

    /** Plays an animation of the model ({@code set} null) or of an item animation set. */
    void animate(Ref<EntityStore> ref, AnimationSlot slot, String set, String animation) {
        if (ref.isValid()) {
            AnimationUtils.playAnimation(ref, slot, set, animation, false, this.store);
        }
    }

    /** "Set/Animation" as the unit data writes it. */
    void attack(Ref<EntityStore> ref, String attack) {
        if (attack == null) {
            return;
        }
        int slash = attack.indexOf('/');
        if (slash < 0) {
            this.animate(ref, AnimationSlot.Action, null, attack);
        } else {
            this.animate(ref, AnimationSlot.Action, attack.substring(0, slash), attack.substring(slash + 1));
        }
    }

    /** Shows a share of health on the puppet's bar, never at zero (that would run the game's death and loot). */
    void health(Ref<EntityStore> ref, double share) {
        EntityStatMap stats = ref.isValid() ? this.store.getComponent(ref, EntityStatMap.getComponentType()) : null;
        if (stats == null) {
            return;
        }
        int health = DefaultEntityStatTypes.getHealth();
        EntityStatValue value = stats.get(health);
        if (value != null) {
            stats.setStatValue(health, (float) Math.max(1, value.getMax() * Math.max(0, Math.min(1, share))));
        }
    }

    void nameplate(Ref<EntityStore> ref, String text) {
        if (!ref.isValid()) {
            return;
        }
        Nameplate nameplate = this.store.getComponent(ref, Nameplate.getComponentType());
        if (nameplate == null) {
            this.store.putComponent(ref, Nameplate.getComponentType(), new Nameplate(text));
        } else if (!text.equals(nameplate.getText())) {
            nameplate.setText(text);
        }
    }

    void remove(Ref<EntityStore> ref) {
        if (ref != null && ref.isValid()) {
            this.store.removeEntity(ref, RemoveReason.REMOVE);
        }
    }
}
