package link.infra.tinymap;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import link.infra.tinymap.mixin.MinecraftServerAccessor;
import link.infra.tinymap.mixin.PalettedContainerMixin;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.PalettedContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

/**
 * Bad name
 * Basically an abstraction for getting loaded/unloaded chunks
 * haha
 */
class BlockDigger {
	private final LongSet validRegions = new LongOpenHashSet();
	private final File regionFolder;
	private final ServerWorld world;
	private final ThreadedAnvilChunkStorage tacs;

	private static final Codec<PalettedContainer<BlockState>> CODEC = PalettedContainer.createCodec(Block.STATE_IDS, BlockState.CODEC, PalettedContainer.PaletteProvider.BLOCK_STATE, Blocks.AIR.getDefaultState());
	private static final Logger LOGGER = LogManager.getLogger();

	private static Codec<PalettedContainer<Biome>> createCodec(Registry<Biome> biomeRegistry) {
		return PalettedContainer.createCodec(biomeRegistry, biomeRegistry.getCodec(), PalettedContainer.PaletteProvider.BIOME, biomeRegistry.getOrThrow(BiomeKeys.PLAINS));
	}

	private static void logRecoverableError(ChunkPos chunkPos, int y, String message) {
		LOGGER.error("Recoverable errors when loading section [" + chunkPos.x + ", " + y + ", " + chunkPos.z + "]: " + message);
	}

	public BlockDigger(MinecraftServer server, ServerWorld world) {
		regionFolder = new File(((MinecraftServerAccessor) server).getSession().getWorldDirectory(world.getRegistryKey()).toFile(), "region");
		this.world = world;
		this.tacs = world.getChunkManager().threadedAnvilChunkStorage;
	}

	// Thread-local session of BlockDigger
	public class Session {
		// Saved in testTileExists - as this data will be read again when rendering the chunk, might as well only read it once
		private final Long2ObjectMap<NbtCompound> unloadedChunkCachedData = new Long2ObjectOpenHashMap<>();

		public boolean testTileExists(int tileX, int tileZ, int zoomShift) {
			int regionSize = TileGenerator.rightShiftButReversible(1, TileGenerator.TILE_TO_REGION_SHIFT - zoomShift);
			if (regionSize < 1) {
				regionSize = 1;
			}

			int regionOriginX = TileGenerator.rightShiftButReversible(tileX, TileGenerator.TILE_TO_REGION_SHIFT - zoomShift);
			int regionOriginZ = TileGenerator.rightShiftButReversible(tileZ, TileGenerator.TILE_TO_REGION_SHIFT - zoomShift);
			boolean regionFound = false;
			synchronized (validRegions) {
				outer:
				for (int regionOffX = 0; regionOffX < regionSize; regionOffX++) {
					for (int regionOffZ = 0; regionOffZ < regionSize; regionOffZ++) {
						// Not really a chunk pos, actually a region pos... :)
						long pos = ChunkPos.toLong(regionOriginX + regionOffX, regionOriginZ + regionOffZ);
						if (validRegions.contains(pos)) {
							regionFound = true;
							break outer;
						}
						if (new File(regionFolder, "r." + (regionOriginX + regionOffX) + "." + (regionOriginZ + regionOffZ) + ".mca").exists()) {
							regionFound = true;
							validRegions.add(pos);
							break outer;
						}
					}
				}
			}
			if (!regionFound) {
				return false;
			}

			// If there is exactly one or more regions in this tile, checking for the regions' existence is enough
			// If there are more than one tiles in this region, we need to check the chunks
			if (TileGenerator.rightShiftButReversible(1, TileGenerator.TILE_TO_REGION_SHIFT - zoomShift) >= 1) {
				return true;
			}

			int chunkSize = TileGenerator.rightShiftButReversible(1, TileGenerator.TILE_TO_CHUNK_SHIFT - zoomShift);
			int chunkOriginX = TileGenerator.rightShiftButReversible(tileX, TileGenerator.TILE_TO_CHUNK_SHIFT - zoomShift);
			int chunkOriginZ = TileGenerator.rightShiftButReversible(tileZ, TileGenerator.TILE_TO_CHUNK_SHIFT - zoomShift);

			for (int chunkOffX = 0; chunkOffX < chunkSize; chunkOffX++) {
				for (int chunkOffZ = 0; chunkOffZ < chunkSize; chunkOffZ++) {
					if (world.isChunkLoaded(chunkOriginX + chunkOffX, chunkOriginZ + chunkOffZ)) {
						return true;
					}

					// First check if the region is valid
					synchronized (validRegions) {
						if (validRegions.contains(ChunkPos.toLong((chunkOriginX + chunkOffX) >> 5, (chunkOriginZ + chunkOffZ) >> 5))) {
							return true;
						}
					}

					// Attempt to get it's NBT
					try {
						ChunkPos pos = new ChunkPos(chunkOriginX + chunkOffX, chunkOriginZ + chunkOffZ);
						NbtCompound chunkTag = tacs.getNbt(pos);
						if (chunkTag != null) {
							unloadedChunkCachedData.put(pos.toLong(), chunkTag);
							return true;
						}
					} catch (IOException e) {
						// TODO: better logging
						e.printStackTrace();
						return false;
					}
				}
			}

			return false;
		}

		public Chunk getChunkView(int x, int z) {
			if (world.isChunkLoaded(x, z)) {
				return world.getChunk(x, z);
			} else {
				ChunkPos pos = new ChunkPos(x, z);
				NbtCompound chunkData = unloadedChunkCachedData.remove(pos.toLong());
				if (chunkData == null) {
					try {
						// TODO: cache??
						chunkData = tacs.getNbt(pos);
					} catch (IOException e) {
						// TODO: better logging
						e.printStackTrace();
						return null;
					}
					if (chunkData == null) {
						return null;
					}
				}

				ChunkStatus status;
				NbtCompound levelData = null;
				String blockStatesKey;
				String biomesKey;

				if (chunkData.contains("Level")) {
					levelData = chunkData.getCompound("Level");
					status = ChunkStatus.byId(levelData.getString("Status"));
					blockStatesKey = "BlockStates";
					biomesKey = "biomes-do-not-exist";
				} else {
					status = ChunkStatus.byId(chunkData.getString("Status"));
					blockStatesKey = "block_states";
					biomesKey = "biomes";
				}

				// Only allow fully-generated chunks
				if (!status.isAtLeast(ChunkStatus.FULL)) {
					return null;
				}

				NbtList sectionList;
				boolean isPreV118 = false;

				if (levelData != null) {
					sectionList = levelData.getList("Sections", 10);
					isPreV118 = true;
				} else {
					sectionList = chunkData.getList("sections", 10);
				}

				int vertical_section_count = world.countVerticalSections();

				ChunkSection[] sections = new ChunkSection[vertical_section_count];

				PalettedContainer<Biome> biomePalette;
				PalettedContainer<BlockState> blockStatePalette;
				Registry<Biome> registry = world.getRegistryManager().get(Registry.BIOME_KEY);
				Codec<PalettedContainer<Biome>> codec = createCodec(registry);

				// Generate dummy sections under level 0
				if (isPreV118) {
					int y = -1;
					int l;

					while ((l = world.sectionCoordToIndex(y)) > -1) {

						blockStatePalette = new PalettedContainer<>(
								Block.STATE_IDS,
								Blocks.AIR.getDefaultState(),
								PalettedContainer.PaletteProvider.BLOCK_STATE
						);

						biomePalette = new PalettedContainer<>(registry, registry.getOrThrow(BiomeKeys.PLAINS), PalettedContainer.PaletteProvider.BIOME);

						ChunkSection chunkSection = new ChunkSection(y, blockStatePalette, biomePalette);
						sections[l] = chunkSection;

						y--;
					}

					for (y = 16; y < 20; y++) {
						l = world.sectionCoordToIndex(y);

						blockStatePalette = new PalettedContainer<>(
								Block.STATE_IDS,
								Blocks.AIR.getDefaultState(),
								PalettedContainer.PaletteProvider.BLOCK_STATE
						);

						biomePalette = new PalettedContainer<>(registry, registry.getOrThrow(BiomeKeys.PLAINS), PalettedContainer.PaletteProvider.BIOME);

						ChunkSection chunkSection = new ChunkSection(y, blockStatePalette, biomePalette);
						sections[l] = chunkSection;
					}
				}

				for (int i = 0; i < sectionList.size(); ++i) {
					NbtCompound sectionTag = sectionList.getCompound(i);
					int y = sectionTag.getByte("Y");
					int l = world.sectionCoordToIndex(y);

					if (l >= 0 && l < sections.length) {

						if (isPreV118) {
							blockStatePalette = new PalettedContainer<>(
									Block.STATE_IDS,
									Blocks.AIR.getDefaultState(),
									PalettedContainer.PaletteProvider.BLOCK_STATE
							);

							NbtList palette = sectionTag.getList("Palette", 10);
							int size = palette.size();

							for (int pi = 0; pi < size; pi++) {
								NbtCompound entry = palette.getCompound(pi);
								Identifier blockId = Identifier.tryParse(entry.getString("Name"));

								if (blockId != null) {
									((PalettedContainerMixin<BlockState>) blockStatePalette).callSet(pi, Registry.BLOCK.get(blockId).getDefaultState());
								}
							}

						} else {
							blockStatePalette = sectionTag.contains(blockStatesKey, 10) ? (PalettedContainer)CODEC.parse(NbtOps.INSTANCE, sectionTag.getCompound(blockStatesKey)).promotePartial(errorMessage -> logRecoverableError(pos, y, errorMessage)).getOrThrow(false, LOGGER::error) : new PalettedContainer(Block.STATE_IDS, Blocks.AIR.getDefaultState(), PalettedContainer.PaletteProvider.BLOCK_STATE);
						}

						biomePalette = sectionTag.contains(biomesKey, 10) ? codec.parse(NbtOps.INSTANCE, sectionTag.getCompound(biomesKey)).promotePartial(errorMessage -> logRecoverableError(pos, y, errorMessage)).getOrThrow(false, LOGGER::error) : new PalettedContainer<>(registry, registry.getOrThrow(BiomeKeys.PLAINS), PalettedContainer.PaletteProvider.BIOME);
						ChunkSection chunkSection = new ChunkSection(y, blockStatePalette, biomePalette);
						chunkSection.calculateCounts();
						sections[l] = chunkSection;
					}
				}

				UnloadedChunkView unloadedChunkView;

				if (isPreV118) {
					unloadedChunkView = new UnloadedChunkView(sections, world, pos, 256, 0);
				} else {
					unloadedChunkView = new UnloadedChunkView(sections, world, pos);
				}

				NbtCompound heightmaps = null;

				if (levelData != null) {
					heightmaps = levelData.getCompound("Heightmaps");
				}

				String heightmapName = Heightmap.Type.WORLD_SURFACE.getName();
				if (heightmaps != null && heightmaps.contains(heightmapName, 12)) {
					unloadedChunkView.setHeightmap(Heightmap.Type.WORLD_SURFACE, heightmaps.getLongArray(heightmapName));
				} else {
					Heightmap.populateHeightmaps(unloadedChunkView, Collections.singleton(Heightmap.Type.WORLD_SURFACE));
				}

				return unloadedChunkView;
			}
		}
	}
}
