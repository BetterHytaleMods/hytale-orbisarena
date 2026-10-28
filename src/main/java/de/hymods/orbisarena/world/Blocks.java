package de.hymods.orbisarena.world;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Setting blocks and water in an arena world; on the world's thread. Unknown block ids are logged once and skipped. */
public final class Blocks {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int SETTINGS = SetBlockSettings.NO_DROP_ITEMS | SetBlockSettings.NO_SEND_PARTICLES
            | SetBlockSettings.NO_SEND_AUDIO;
    private static final int TICKING = 4;
    private static final Set<String> MISSING = new HashSet<>();

    private final World world;
    private final ChunkStore chunks;

    public Blocks(World world) {
        this.world = world;
        this.chunks = world.getChunkStore();
    }

    /** Loads the chunk columns covering a block rectangle, ticking. */
    public static CompletableFuture<Void> load(World world, int x0, int z0, int x1, int z1) {
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int cx = Math.floorDiv(x0, 32); cx <= Math.floorDiv(x1, 32); cx++) {
            for (int cz = Math.floorDiv(z0, 32); cz <= Math.floorDiv(z1, 32); cz++) {
                loads.add(world.getChunkStore().getChunkReferenceAsync(ChunkUtil.indexChunk(cx, cz), TICKING));
                for (int cy = 0; cy <= 3; cy++) {
                    loads.add(world.getChunkStore().getChunkSectionReferenceAsync(cx, cy, cz, TICKING));
                }
            }
        }
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
    }

    /**
     * Keeps the chunk columns of a block rectangle loaded and ticking (their timers reset every second) until the
     * returned task runs. On the world's thread.
     */
    public static Runnable hold(World world, int x0, int z0, int x1, int z1) {
        ChunkStore chunks = world.getChunkStore();
        List<WorldChunk> columns = new ArrayList<>();
        List<ChunkSection> sections = new ArrayList<>();
        for (int cx = Math.floorDiv(x0, 32); cx <= Math.floorDiv(x1, 32); cx++) {
            for (int cz = Math.floorDiv(z0, 32); cz <= Math.floorDiv(z1, 32); cz++) {
                Ref<ChunkStore> column = chunks.getChunkReference(ChunkUtil.indexChunk(cx, cz));
                WorldChunk chunk = column == null || !column.isValid() ? null : chunks.getStore().getComponent(column, WorldChunk.getComponentType());
                if (chunk != null) {
                    chunk.addKeepLoaded();
                    columns.add(chunk);
                }
                for (int cy = 0; cy <= 3; cy++) {
                    Ref<ChunkStore> ref = chunks.getChunkSectionReference(cx, cy, cz);
                    ChunkSection section = ref == null || !ref.isValid() ? null : chunks.getStore().getComponent(ref, ChunkSection.getComponentType());
                    if (section != null) {
                        section.addKeepLoaded();
                        sections.add(section);
                    }
                }
            }
        }
        boolean[] held = {true};
        awake(world, columns, sections, held);
        return () -> world.execute(() -> {
            if (held[0]) {
                held[0] = false;
                columns.forEach(WorldChunk::removeKeepLoaded);
                sections.forEach(ChunkSection::removeKeepLoaded);
            }
        });
    }

    private static void awake(World world, List<WorldChunk> columns, List<ChunkSection> sections, boolean[] held) {
        if (held[0]) {
            columns.forEach(WorldChunk::resetActiveTimer);
            sections.forEach(ChunkSection::resetActiveTimer);
            world.scheduleAfter(() -> awake(world, columns, sections, held), 1, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    public void set(int x, int y, int z, String id) {
        this.set(x, y, z, id, 0);
    }

    public void set(int x, int y, int z, String id, int rotation) {
        int index = BlockType.getAssetMap().getIndex(id);
        BlockType type = index == Integer.MIN_VALUE ? null : BlockType.getAssetMap().getAsset(index);
        if (type == null) {
            if (MISSING.add(id)) {
                LOGGER.at(Level.WARNING).log("Unknown block %s", id);
            }
            return;
        }
        Ref<ChunkStore> section = this.chunks.getChunkSectionReferenceAtBlock(x, y, z);
        if (section == null || !section.isValid()) {
            return;
        }
        BlockOperations.setBlock(this.chunks, section, x, y, z, index, type, rotation, 0, SETTINGS);
    }

    public void clear(int x, int y, int z) {
        this.set(x, y, z, "Empty");
    }

    /** Puts still water into a block (which should be air), or takes it out. */
    public void water(int x, int y, int z, boolean on) {
        Ref<ChunkStore> section = this.chunks.getChunkSectionReferenceAtBlock(x, y, z);
        FluidSection fluids = section == null || !section.isValid() ? null
                : this.chunks.getStore().getComponent(section, FluidSection.getComponentType());
        if (fluids == null) {
            return;
        }
        if (on) {
            int water = Fluid.getAssetMap().getIndex("Water_Source");
            if (water != Integer.MIN_VALUE) {
                fluids.setFluid(x, y, z, water, (byte) Fluid.getAssetMap().getAsset(water).getMaxFluidLevel());
            }
        } else {
            fluids.setFluid(x, y, z, 0, (byte) 0);
        }
    }

    public World world() {
        return this.world;
    }
}
