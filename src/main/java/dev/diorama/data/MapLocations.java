package dev.diorama.data;

import dev.diorama.Projection;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Legacy fork coordinates and biome IDs, retained for compatibility with existing saved tiles. */
public final class MapLocations {
	private static final int MAGIC = 0x4F4D5031;
	private static final int SIZE = Projection.TILE_UNITS;
	private final int[] heights = new int[SIZE * SIZE];
	private final int[] northSouth = new int[SIZE * SIZE];
	private final @Nullable String[] biomes = new String[SIZE * SIZE];

	public record Location(BlockPos position, @Nullable String biome, boolean approximate) {
	}

	public MapLocations() {
		Arrays.fill(this.heights, Integer.MIN_VALUE);
	}

	public void put(int u, int v, BlockPos pos, @Nullable String biome) {
		int i = v * SIZE + u;
		this.heights[i] = pos.getY();
		this.northSouth[i] = pos.getZ();
		this.biomes[i] = biome;
	}

	public @Nullable Location at(double u, double v) {
		int i = Math.floorMod((int) Math.floor(v), SIZE) * SIZE + Math.floorMod((int) Math.floor(u), SIZE);
		return this.heights[i] == Integer.MIN_VALUE ? null
			: new Location(new BlockPos((int) Math.floor(u), this.heights[i], this.northSouth[i]), this.biomes[i], false);
	}

	/** New known samples replace old ones; unknown samples retain already explored terrain. */
	public MapLocations merge(MapLocations fresh) {
		MapLocations merged = new MapLocations();
		for (int i = 0; i < this.heights.length; i++) {
			MapLocations from = fresh.heights[i] == Integer.MIN_VALUE ? this : fresh;
			merged.heights[i] = from.heights[i];
			merged.northSouth[i] = from.northSouth[i];
			merged.biomes[i] = from.biomes[i];
		}
		return Arrays.equals(this.heights, merged.heights) && Arrays.equals(this.northSouth, merged.northSouth)
			&& Arrays.equals(this.biomes, merged.biomes) ? this : merged;
	}

	public static @Nullable MapLocations read(Path file) throws IOException {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (var in = new DataInputStream(new InflaterInputStream(Files.newInputStream(file)))) {
			if (in.readInt() != MAGIC || in.readInt() != SIZE) {
				throw new IOException("Invalid saved map locations: " + file);
			}
			int count = in.readInt();
			if (count < 0 || count > SIZE * SIZE) {
				throw new IOException("Invalid biome palette: " + file);
			}
			String[] palette = new String[count + 1];
			for (int i = 1; i <= count; i++) {
				palette[i] = in.readUTF();
			}
			MapLocations locations = new MapLocations();
			for (int i = 0; i < locations.heights.length; i++) {
				locations.heights[i] = in.readInt();
				if (locations.heights[i] != Integer.MIN_VALUE) {
					locations.northSouth[i] = in.readInt();
					int biome = in.readUnsignedShort();
					if (biome > count) {
						throw new IOException("Invalid biome index: " + file);
					}
					locations.biomes[i] = palette[biome];
				}
			}
			return locations;
		}
	}

	public void write(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Map<String, Integer> palette = new LinkedHashMap<>();
		for (String biome : this.biomes) {
			if (biome != null) {
				palette.computeIfAbsent(biome, ignored -> palette.size() + 1);
			}
		}
		try (var out = new DataOutputStream(new DeflaterOutputStream(Files.newOutputStream(temp)))) {
			out.writeInt(MAGIC);
			out.writeInt(SIZE);
			out.writeInt(palette.size());
			for (String biome : palette.keySet()) {
				out.writeUTF(biome);
			}
			for (int i = 0; i < this.heights.length; i++) {
				out.writeInt(this.heights[i]);
				if (this.heights[i] != Integer.MIN_VALUE) {
					out.writeInt(this.northSouth[i]);
					out.writeShort(this.biomes[i] == null ? 0 : palette.get(this.biomes[i]));
				}
			}
		}
		Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}
}
