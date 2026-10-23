package dev.diorama.data;

import com.google.gson.JsonParser;
import dev.diorama.Projection;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Disk-only regressions: run without a window using runSavedMapChecks, or with the game tests. */
public final class SavedMapChecks {
	private static final int RED = 0xFF2040E0;
	private static final int GREEN = 0xFF40E020;

	public static void main(String[] args) throws Exception {
		Path root = Files.createTempDirectory("diorama-saved-map-checks-");
		try {
			repair(root.resolve("repair"));
			migration(root.resolve("migration"));
			locations(root.resolve("locations"));
			System.out.println("Saved map checks passed: overview repair, Realm recovery, saved coordinates and biomes, metadata-only updates");
		} finally {
			try (var paths = Files.walk(root)) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					Files.delete(path);
				}
			}
		}
	}

	private static void locations(Path folder) throws Exception {
		TileKey key = new TileKey(TileKey.SURFACE, -2, -3);
		MapLocations first = new MapLocations();
		first.put(127, 127, new net.minecraft.core.BlockPos(-129, 128, -275), "minecraft:snowy_taiga");
		int[] pixels = new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
		Arrays.fill(pixels, RED);
		TileImage image = TileImage.full(key, Mipmaps.build(pixels, Projection.TILE_PIXELS), new byte[Projection.COVERAGE_CELLS * Projection.COVERAGE_CELLS]);
		TileStore store = new TileStore(folder);
		store.baked(image.withLocations(first));
		store.close();
		TileStore reopened = new TileStore(folder);
		reopened.read(key);
		MapLocations loaded = reopened.locations(key);
		check(loaded != null, "Saved terrain coordinates weren't restored");
		var location = loaded.at(-128.5, -256.5);
		check(location != null && location.position().equals(new net.minecraft.core.BlockPos(-129, 128, -275))
			&& "minecraft:snowy_taiga".equals(location.biome()) && !location.approximate(), "Restored the wrong location or biome at negative coordinates");
		check(loaded.at(-256, -384) == null, "Invented a location in an unknown cell");

		// Terrain metadata must save even when the baked colours haven't changed.
		MapLocations update = new MapLocations();
		update.put(0, 0, new net.minecraft.core.BlockPos(-256, 80, -430), "minecraft:forest");
		reopened.baked(image.withLocations(update));
		reopened.close();
		MapLocations merged = MapLocations.read(folder.resolve("-2_-3.omp"));
		check(merged.at(-128.5, -256.5).position().equals(location.position()), "A partial metadata update erased unloaded exploration");
		check("minecraft:forest".equals(merged.at(-256, -384).biome()), "Skipped an update because the map colours were unchanged");

		TileStore oldMap = new TileStore(folder.resolve("image-only"));
		oldMap.read(key);
		check(oldMap.locations(key) == null, "Invented biome data for an image-only map");
		oldMap.close();
	}

	private static void repair(Path folder) throws Exception {
		TileKey child = new TileKey(TileKey.SURFACE, -1, -1);
		int[] pixels = new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
		Arrays.fill(pixels, RED);
		TileStore.writeFile(file(folder, child), pixels);
		// This is the broken-cache case: a newer overview still omits its detailed child.
		TileStore.writeFile(file(folder, child.parent()), new int[pixels.length]);
		Files.setLastModifiedTime(file(folder, child.parent()), FileTime.fromMillis(System.currentTimeMillis() + 60_000));
		new TileStore(folder).close();
		assertAncestors(folder, child, RED);

		// A reader caching the old pixels must not make later overview levels lose the update.
		TileStore store = new TileStore(folder);
		ConcurrentLinkedQueue<TileKey> changed = new ConcurrentLinkedQueue<>();
		store.onOverviewChanged(changed::add);
		store.read(child.parent());
		Arrays.fill(pixels, GREEN);
		store.baked(TileImage.full(child, Mipmaps.build(pixels, Projection.TILE_PIXELS), new byte[Projection.COVERAGE_CELLS * Projection.COVERAGE_CELLS]));
		store.close();
		assertAncestors(folder, child, GREEN);
		check(changed.stream().anyMatch(key -> key.lod() == TileKey.MAX_LOD), "The coarsest resident overview wasn't invalidated");

		// A crash can leave an absent ancestor even after the revision marker was saved.
		TileKey ancestor = child.parent().parent().parent();
		Files.delete(file(folder, ancestor));
		new TileStore(folder).close();
		assertAncestors(folder, child, GREEN);
	}

	private static void assertAncestors(Path folder, TileKey child, int color) throws Exception {
		TileKey key = child;
		for (int lod = 1; lod <= TileKey.MAX_LOD; lod++) {
			key = key.parent();
			int[] pixels = TileStore.readFile(file(folder, key));
			check(pixels != null, "Missing overview at level " + lod);
			check(pixels[pixels.length - 1] == color, "Lost the negative-coordinate child's pixels at level " + lod);
			long known = Arrays.stream(pixels).filter(p -> (p >>> 24) != 0).count();
			check(known == pixels.length >> (2 * lod), "Incorrect explored area at level " + lod + ": " + known);
		}
	}

	private static void migration(Path root) throws Exception {
		String seed = "0123456789abcdef";
		String key = "realm-42-slot-1-world-" + seed;
		Path first = root.resolve("realms-old-ip-a-world-" + seed);
		Path second = root.resolve("realms-new-ip-b-world-" + seed);
		Path unrelated = root.resolve("realms-other-world-1111111111111111");
		Files.createDirectories(unrelated);
		String tile = "dim-overworld/surface-50/-1_-1.omt";
		int[] old = new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
		old[0] = RED;
		old[2] = RED;
		int[] recent = new int[old.length];
		recent[1] = GREEN;
		recent[2] = GREEN;
		TileStore.writeFile(first.resolve(tile), old);
		TileStore.writeFile(second.resolve(tile), recent);
		Files.setLastModifiedTime(first.resolve(tile), FileTime.fromMillis(1000));
		Files.setLastModifiedTime(second.resolve(tile), FileTime.fromMillis(2000));
		TileStore.writeFile(first.resolve("dim-overworld/surface-50/lod1/-1_-1.omt"), old);
		Files.writeString(first.resolve("waypoints.json"), "[{\"id\":\"one\",\"name\":\"Old\"}]");
		Files.writeString(second.resolve("waypoints.json"), "[{\"id\":\"one\",\"name\":\"Renamed\"},{\"id\":\"two\",\"name\":\"Home\"}]");
		Files.setLastModifiedTime(first.resolve("waypoints.json"), FileTime.fromMillis(1000));
		Files.setLastModifiedTime(second.resolve("waypoints.json"), FileTime.fromMillis(2000));
		Files.writeString(first.resolve("folders.json"), "{\"custom\":[\"Mines\"],\"hidden\":[\"dim:nether\"]}");
		Files.writeString(second.resolve("folders.json"), "{\"custom\":[\"Home\"],\"hidden\":[]}");
		writeSeen(first.resolve("dim-overworld/seen.bin"), 1);
		writeSeen(second.resolve("dim-overworld/seen.bin"), 2);
		RealmMigration.prepare(root, key, seed);
		Path destination = root.resolve(key);
		int[] merged = TileStore.readFile(destination.resolve(tile));
		check(merged[0] == RED && merged[1] == GREEN && merged[2] == GREEN, "Realm migration erased exploration or used stale overlapping pixels");
		check(!Files.exists(destination.resolve("dim-overworld/surface-50/lod1")), "Imported the broken overview cache");
		var waypoints = JsonParser.parseString(Files.readString(destination.resolve("waypoints.json"))).getAsJsonArray();
		check(waypoints.size() == 2 && waypoints.get(0).getAsJsonObject().get("name").getAsString().equals("Renamed"), "Lost or duplicated waypoints");
		var folders = JsonParser.parseString(Files.readString(destination.resolve("folders.json"))).getAsJsonObject();
		check(folders.getAsJsonArray("custom").size() == 2 && folders.getAsJsonArray("hidden").size() == 1, "Lost waypoint folders or hidden state");
		try (var in = new DataInputStream(new InflaterInputStream(Files.newInputStream(destination.resolve("dim-overworld/seen.bin"))))) {
			check(in.readInt() == 0x4F4D5331 && in.readInt() == 1 && in.readLong() == 7 && in.readLong() == 3, "Lost seen blocks");
		}
		check(Files.isDirectory(unrelated), "Migrated another world's seed");
		check(Files.isDirectory(root.resolve("legacy-realms").resolve(first.getFileName())), "Didn't retain the original map backup");
		RealmMigration.prepare(root, key, seed);
		check(Arrays.equals(merged, TileStore.readFile(destination.resolve(tile))), "A repeat migration changed the map");

		// Retry with existing canonical data (for example after a failed archive).
		Files.createDirectories(first);
		old[0] = GREEN;
		TileStore.writeFile(first.resolve(tile), old);
		RealmMigration.prepare(root, key, seed);
		check(Arrays.equals(merged, TileStore.readFile(destination.resolve(tile))), "A migration retry overwrote newer canonical data");
	}

	private static void writeSeen(Path file, long bits) throws Exception {
		Files.createDirectories(file.getParent());
		try (var out = new DataOutputStream(new DeflaterOutputStream(Files.newOutputStream(file)))) {
			out.writeInt(0x4F4D5331);
			out.writeInt(1);
			out.writeLong(7);
			out.writeLong(bits);
			for (int i = 1; i < 64; i++) {
				out.writeLong(0);
			}
		}
	}

	private static Path file(Path folder, TileKey key) {
		return (key.lod() == 0 ? folder : folder.resolve("lod" + key.lod())).resolve(key.tileU() + "_" + key.tileV() + ".omt");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
