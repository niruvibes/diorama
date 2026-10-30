package dev.diorama;

import dev.diorama.render.MapLevel;
import java.util.List;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/** A fullscreen map's selection. Browsing another dimension never replaces the live session. */
public final class MapBrowser implements AutoCloseable {
	public static final List<String> DIMENSIONS = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
	private @Nullable String selected;
	private @Nullable String archiveKey;
	private @Nullable MapLevel archive;

	public String dimension() {
		var level = Minecraft.getInstance().level;
		return this.selected != null ? this.selected : level == null ? DIMENSIONS.get(0) : level.dimension().identifier().toString();
	}

	public boolean live() {
		var level = Minecraft.getInstance().level;
		return level != null && this.dimension().equals(level.dimension().identifier().toString());
	}

	public void select(String dimension) {
		if (!dimension.equals(this.dimension())) {
			this.close();
		}
		this.selected = dimension;
	}

	public @Nullable MapLevel map() {
		MapSession session = MapSession.get();
		if (session == null) {
			return null;
		}
		if (this.live()) {
			this.close();
			return session.map();
		}
		Maps maps = Maps.get();
		if (maps == null) {
			return null;
		}
		String dimension = this.dimension();
		boolean nether = dimension.equals("minecraft:the_nether");
		boolean fog = ServerRules.fogOfWar(nether);
		var folder = maps.tileFolder(dimension, maps.active(dimension));
		String key = folder + ":" + Math.round(MapStyle.get().pitchDegrees()) + ":" + fog;
		if (!key.equals(this.archiveKey)) {
			this.close();
			// Only the saved surface image is read: these bounds never generate or load chunks.
			boolean overworld = dimension.equals("minecraft:overworld");
			this.archive = MapLevel.openSaved(overworld ? -64 : 0, overworld ? 320 : 256, nether ? 128 : MapLevel.NO_ROOF, folder);
			this.archive.setFogOfWar(fog);
			this.archiveKey = key;
		}
		return this.archive;
	}

	@Override
	public void close() {
		if (this.archive != null) {
			this.archive.close();
			this.archive = null;
		}
		this.archiveKey = null;
	}
}
