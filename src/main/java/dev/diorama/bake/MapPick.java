package dev.diorama.bake;

import dev.diorama.Projection;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.MapLocations;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.BiomeManager;
import org.jspecify.annotations.Nullable;

/**
 * Finds the block a map pixel shows, by walking the same camera ray the baker traces. The map is
 * a top view, so a screen point alone doesn't say how high the ground is; this does.
 */
public final class MapPick {
	private MapPick() {
	}

	/**
	 * The position just above the first solid block or water surface the ray through map point
	 * {@code (u, v)} reaches, or null where the map has no data.
	 */
	public static @Nullable BlockPos standingSpot(ChunkSource source, BlockLooks looks, double u, double v, int layer, boolean fogOfWar) {
		return standingSpot(source, looks, new VoxelReader(source, looks.kind), u, v, layer, fogOfWar, null);
	}

	/** Snapshot biomes remain valid after the live game unloads a chunk. Never use its fallback. */
	public static @Nullable String biomeAt(ChunkSource source, BlockPos pos) {
		ChunkSnapshot snapshot = source.chunk(pos.getX() >> 4, pos.getZ() >> 4);
		if (snapshot == null) {
			return null;
		}
		BiomeManager manager = new BiomeManager((qx, qy, qz) -> {
			int dx = (qx >> 2) - snapshot.chunkX;
			int dz = (qz >> 2) - snapshot.chunkZ;
			return dx < -1 || dx > 1 || dz < -1 || dz > 1 || !snapshot.hasBiomesAround(dx, dz)
				? null : snapshot.noiseBiome(qx, qy, qz);
		}, snapshot.biomeZoomSeed);
		var biome = manager.getBiome(pos);
		return biome == null ? null : biome.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
	}

	/** One location per map unit, with the same ray as clicking, for CPU and GPU bakes alike. */
	public static @Nullable MapLocations locations(TileKey key, ChunkSource source, BlockLooks looks, boolean fogOfWar, @Nullable BitSet blocks) {
		if (key.isCave() || key.lod() != 0) {
			return null;
		}
		MapLocations locations = new MapLocations();
		VoxelReader reader = new VoxelReader(source, looks.kind);
		Map<TileKey, Integer> tops = new HashMap<>();
		Map<String, String> biomeNames = new HashMap<>();
		boolean any = false;
		int perRow = Projection.TILE_PIXELS / TileImage.BLOCK;
		int unitsPerBlock = TileImage.BLOCK / Projection.PIXELS_PER_UNIT;
		for (int y = 0; y < Projection.TILE_UNITS; y++) {
			for (int x = 0; x < Projection.TILE_UNITS; x++) {
				if (blocks != null && !blocks.get((y / unitsPerBlock) * perRow + x / unitsPerBlock)) {
					continue;
				}
				BlockPos pos = standingSpot(source, looks, reader, key.minU() + x + 0.5, key.minV() + y + 0.5, key.layer(), fogOfWar, tops);
				if (pos != null) {
					String biome = biomeAt(source, pos);
					locations.put(x, y, pos, biome == null ? null : biomeNames.computeIfAbsent(biome, name -> name));
					any = true;
				}
			}
		}
		return any ? locations : null;
	}

	private static @Nullable BlockPos standingSpot(ChunkSource source, BlockLooks looks, VoxelReader reader, double u, double v, int layer,
		boolean fogOfWar, @Nullable Map<TileKey, Integer> tops) {
		boolean underRoof = source.roof() != ChunkSource.NO_ROOF;
		boolean cave = layer != TileKey.SURFACE;
		boolean roofless = !cave && source.roof() != ChunkSource.NO_ROOF;
		double sin = Projection.sin();
		double cos = Projection.cos();
		// Start where the baker starts for this tile, so whatever the map draws can be picked.
		TileKey tile = new TileKey(layer, (int) Math.floor(u / Projection.TILE_UNITS), (int) Math.floor(v / Projection.TILE_UNITS));
		int startY = cave ? Math.min(layer, source.maxY()) : roofless ? Math.min(source.roof(), source.maxY())
			: tops == null ? TileBaker.localTop(tile, source, 0) : tops.computeIfAbsent(tile, k -> TileBaker.localTop(k, source, 0));
		int limitY = cave ? Math.max(source.minY(), startY - TileBaker.CAVE_DEPTH) : source.minY();
		if (startY <= limitY) {
			return null;
		}
		int x = (int) Math.floor(u);
		double z0 = (v + startY * cos) / sin;
		int by = startY - 1;
		int bz = (int) Math.floor(z0);
		double tMaxY = 1 / sin;
		double tMaxZ = (z0 - bz) / cos;
		boolean skippingRock = cave || roofless;
		while (by >= limitY) {
			int id = reader.state(x, by, bz);
			if (id == VoxelReader.UNKNOWN) {
				return null;
			}
			byte kind = looks.kind[id];
			if (skippingRock && kind != BlockLooks.SOLID && !(underRoof && by + 1 < startY && reader.pocket(x, by, bz))) {
				skippingRock = false;
			}
			boolean underside = !skippingRock && underRoof && (cave || roofless) && kind == BlockLooks.SOLID
				&& reader.ceilingUnderside(x, by, bz, reader.lid(x, bz, startY, limitY), limitY);
			// The baker passes through the underside of a ceiling once the ray has left the rock.
			if (!skippingRock && !underside && (kind == BlockLooks.SOLID || kind == BlockLooks.LAVA || kind == BlockLooks.WATER)) {
				// Fog of war: a cave the player hasn't seen can't be picked, or its floor would leak.
				if ((cave || roofless) && fogOfWar && !source.isSeen(x, by, bz) && !source.isSeen(x, by + 1, bz)) {
					return null;
				}
				return new BlockPos(x, by + 1, bz);
			}
			if (tMaxY < tMaxZ) {
				by--;
				tMaxY += 1 / sin;
			} else {
				bz--;
				tMaxZ += 1 / cos;
			}
		}
		return null;
	}
}
