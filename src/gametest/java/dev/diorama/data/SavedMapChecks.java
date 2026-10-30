package dev.diorama.data;

import dev.diorama.Projection;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import net.minecraft.core.BlockPos;

/** Compatibility checks for maps saved by this fork before upstream added saved picks. */
public final class SavedMapChecks {
	public static void main(String[] args) throws Exception {
		Path root = Files.createTempDirectory("diorama-fork-checks-");
		try {
			TileKey key = new TileKey(TileKey.SURFACE, -2, -3);
			MapLocations legacy = new MapLocations();
			BlockPos pos = new BlockPos(-129, 128, -275);
			legacy.put(127, 127, pos, "minecraft:snowy_taiga");
			legacy.write(root.resolve("-2_-3.omp"));
			int[] pixels = new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
			Arrays.fill(pixels, 0xFF2040E0);
			TileStore writer = new TileStore(root);
			writer.baked(TileImage.full(key, Mipmaps.build(pixels, Projection.TILE_PIXELS), new byte[Projection.COVERAGE_CELLS * Projection.COVERAGE_CELLS]));
			writer.close();
			TileStore restored = new TileStore(root);
			try {
				var location = restored.legacyLocation(key, -128.5, -256.5);
				if (location == null || !location.position().equals(pos) || !"minecraft:snowy_taiga".equals(location.biome())) {
					throw new AssertionError("The upstream update lost the fork's saved coordinates or biome");
				}
				if (!Arrays.equals(restored.read(key), pixels) || restored.legacyLocation(key, -256, -384) != null) {
					throw new AssertionError("The update changed a saved image or invented an unknown location");
				}
			} finally {
				restored.close();
			}
			System.out.println("Fork saved-map compatibility checks passed");
		} finally {
			try (var paths = Files.walk(root)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(path);
				}
			}
		}
	}
}
