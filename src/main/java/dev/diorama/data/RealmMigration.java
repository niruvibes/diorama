package dev.diorama.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.diorama.Diorama;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Recovers maps split across temporary Realms addresses, retaining the originals as backups. */
public final class RealmMigration {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int SEEN_MAGIC = 0x4F4D5331;

	private RealmMigration() {
	}

	/** Called once, before any of this world's stores open. The seed also separates world resets. */
	public static void prepare(Path root, String key, String seed) throws IOException {
		if (!Files.isDirectory(root)) {
			return;
		}
		List<Path> sources;
		try (var worlds = Files.list(root)) {
			sources = worlds.filter(Files::isDirectory).filter(path -> {
				String name = path.getFileName().toString();
				return name.startsWith("realms-") && name.endsWith("-world-" + seed);
			}).sorted().toList();
		}
		if (sources.isEmpty()) {
			return;
		}
		Path destination = root.resolve(key);
		Map<Path, List<Path>> files = new LinkedHashMap<>();
		for (Path source : sources) {
			try (var tree = Files.walk(source)) {
				for (Path file : tree.filter(Files::isRegularFile).toList()) {
					Path relative = source.relativize(file);
					// Overviews are derived data. Rebuild from the merged detailed tiles instead.
					if (relative.toString().matches(".*(?:^|[\\\\/])lod[0-9]+[\\\\/].*")
						|| file.getFileName().toString().endsWith(".tmp")
						|| file.getFileName().toString().equals("overviews.version")) {
						continue;
					}
					files.computeIfAbsent(relative, ignored -> new ArrayList<>()).add(file);
				}
			}
		}
		for (var entry : files.entrySet()) {
			Path target = destination.resolve(entry.getKey());
			List<Path> versions = entry.getValue();
			// Newer known pixels win where visits overlap; older exploration fills the gaps.
			Map<Path, java.nio.file.attribute.FileTime> times = new LinkedHashMap<>();
			for (Path file : versions) {
				times.put(file, Files.getLastModifiedTime(file));
			}
			versions.sort(Comparator.comparing(times::get));
			// A previous partial migration, or a later visit, must keep the canonical data.
			if (Files.isRegularFile(target)) {
				versions.add(target);
			}
			String name = target.getFileName().toString();
			if (name.endsWith(".omt")) {
				mergeTiles(target, versions);
			} else if (name.endsWith(".omp")) {
				MapLocations merged = new MapLocations();
				for (Path file : versions) {
					MapLocations locations = MapLocations.read(file);
					if (locations != null) {
						merged = merged.merge(locations);
					}
				}
				merged.write(target);
			} else if (name.equals("waypoints.json")) {
				mergeWaypoints(target, versions);
			} else if (name.equals("folders.json")) {
				mergeFolders(target, versions);
			} else if (name.equals("seen.bin")) {
				mergeSeen(target, versions);
			} else if (!Files.exists(target)) {
				Files.createDirectories(target.getParent());
				Files.copy(versions.getLast(), target);
			}
		}
		// Only archive after every merge succeeded. An interrupted migration can be retried:
		// originals stay intact and canonical pixels/waypoints take precedence on the retry.
		Path backup = root.resolve("legacy-realms");
		Files.createDirectories(backup);
		for (Path source : sources) {
			Path archived = backup.resolve(source.getFileName());
			if (Files.exists(archived)) {
				archived = backup.resolve(source.getFileName() + "-" + java.util.UUID.randomUUID());
			}
			Files.move(source, archived, StandardCopyOption.ATOMIC_MOVE);
		}
		Diorama.LOGGER.info("Recovered {} older Realm map folders into {}; originals are in {}", sources.size(), key, backup);
	}

	private static void mergeTiles(Path target, List<Path> versions) throws IOException {
		int[] merged = null;
		for (Path file : versions) {
			int[] pixels = TileStore.readFile(file);
			if (pixels == null) {
				throw new IOException("Invalid map tile: " + file);
			}
			if (merged == null) {
				merged = pixels;
			} else {
				for (int i = 0; i < pixels.length; i++) {
					if ((pixels[i] >>> 24) != 0) {
						merged[i] = pixels[i];
					}
				}
			}
		}
		TileStore.writeFile(target, merged);
	}

	private static JsonElement json(Path file) throws IOException {
		try (var reader = Files.newBufferedReader(file)) {
			return JsonParser.parseReader(reader);
		}
	}

	private static void writeJson(Path target, JsonElement value) throws IOException {
		Files.createDirectories(target.getParent());
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(temp, GSON.toJson(value));
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static void mergeWaypoints(Path target, List<Path> versions) throws IOException {
		Map<String, JsonElement> waypoints = new LinkedHashMap<>();
		for (Path file : versions) {
			for (JsonElement waypoint : json(file).getAsJsonArray()) {
				JsonObject object = waypoint.getAsJsonObject();
				String id = object.has("id") ? object.get("id").getAsString() : object.toString();
				waypoints.put(id, waypoint);
			}
		}
		JsonArray merged = new JsonArray();
		waypoints.values().forEach(merged::add);
		writeJson(target, merged);
	}

	private static void mergeFolders(Path target, List<Path> versions) throws IOException {
		JsonObject merged = new JsonObject();
		for (String field : List.of("custom", "hidden")) {
			Map<String, JsonElement> values = new LinkedHashMap<>();
			for (Path file : versions) {
				JsonObject folders = json(file).getAsJsonObject();
				if (folders.has(field)) {
					for (JsonElement value : folders.getAsJsonArray(field)) {
						values.put(value.getAsString(), value);
					}
				}
			}
			JsonArray array = new JsonArray();
			values.values().forEach(array::add);
			merged.add(field, array);
		}
		writeJson(target, merged);
	}

	private static void mergeSeen(Path target, List<Path> versions) throws IOException {
		Map<Long, long[]> sections = new LinkedHashMap<>();
		for (Path file : versions) {
			try (var in = new DataInputStream(new InflaterInputStream(Files.newInputStream(file)))) {
				if (in.readInt() != SEEN_MAGIC) {
					throw new IOException("Invalid fog-of-war data: " + file);
				}
				int count = in.readInt();
				for (int s = 0; s < count; s++) {
					long[] bits = sections.computeIfAbsent(in.readLong(), ignored -> new long[64]);
					for (int i = 0; i < bits.length; i++) {
						bits[i] |= in.readLong();
					}
				}
			}
		}
		Files.createDirectories(target.getParent());
		Path temp = target.resolveSibling(target.getFileName() + ".tmp");
		try (var out = new DataOutputStream(new DeflaterOutputStream(Files.newOutputStream(temp)))) {
			out.writeInt(SEEN_MAGIC);
			out.writeInt(sections.size());
			for (var section : sections.entrySet()) {
				out.writeLong(section.getKey());
				for (long bits : section.getValue()) {
					out.writeLong(bits);
				}
			}
		}
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
