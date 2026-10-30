package dev.diorama.test;

import com.mojang.blaze3d.platform.InputConstants;
import dev.diorama.ExportScreen;
import dev.diorama.MapExport;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.MapStyle;
import dev.diorama.Maps;
import dev.diorama.Projection;
import dev.diorama.ServerRules;
import dev.diorama.bake.Mipmaps;
import dev.diorama.bake.TileImage;
import dev.diorama.bake.TileKey;
import dev.diorama.bake.TileSpots;
import dev.diorama.data.TileStore;
import dev.diorama.drawing.Drawing;
import dev.diorama.drawing.DrawingStore;
import dev.diorama.waypoint.Waypoint;
import dev.diorama.waypoint.WaypointStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.function.Function;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;

/** Exercises the dimension buttons while the player and the live recording stay in the Overworld. */
final class DimensionBrowsingTest {
	private static final String OVERWORLD = "minecraft:overworld";
	private static final String NETHER = "minecraft:the_nether";
	private static final String END = "minecraft:the_end";
	private static final TileKey TILE = new TileKey(TileKey.SURFACE, 2, 1);
	private static final int RED = 0xFF2040E0;
	private static final int GREEN = 0xFF40C020;

	static void run(ClientGameTestContext context) {
		Maps maps = context.computeOnClient(mc -> Maps.get());
		MapSession live = context.computeOnClient(mc -> MapSession.get());
		String oldNether = context.computeOnClient(mc -> maps.active(NETHER));
		String oldEnd = context.computeOnClient(mc -> maps.active(END));
		String nether = context.computeOnClient(mc -> maps.create(NETHER, "Browser Nether"));
		String end = context.computeOnClient(mc -> maps.create(END, "Browser End"));
		if (nether == null || end == null) {
			throw new AssertionError("Couldn't create isolated browser fixtures");
		}
		try {
			Path netherFile = fixture(context, context.computeOnClient(mc -> maps.tileFolder(NETHER, nether)), true, RED);
			Path endFile = fixture(context, context.computeOnClient(mc -> maps.tileFolder(END, end)), false, GREEN);
			byte[] netherBefore = bytes(netherFile);
			byte[] endBefore = bytes(endFile);
			context.setScreen(MapScreen::new);
			clickDimension(context, "Nether", NETHER, live);
			showTile(context, true);
			context.takeScreenshot("dimension-browser-nether");
			// The archive's right-click actions use its saved spots and its dimension, not the player's.
			click(context, mc -> new double[] {mc.gui.screen().width / 2.0, mc.gui.screen().height / 2.0}, InputConstants.MOUSE_BUTTON_RIGHT);
			context.waitFor(mc -> ((MapScreen) mc.gui.screen()).menu() != null
				&& ((MapScreen) mc.gui.screen()).menu().centreOf("Add temporary waypoint") != null, 600);
			click(context, mc -> {
				float[] p = ((MapScreen) mc.gui.screen()).menu().centreOf("Teleport here");
				return new double[] {p[0], p[1]};
			}, InputConstants.MOUSE_BUTTON_LEFT);
			if (!context.computeOnClient(mc -> mc.gui.screen() instanceof MapScreen)) {
				throw new AssertionError("Browsing an archive allowed teleporting in the live dimension");
			}
			click(context, mc -> {
				float[] p = ((MapScreen) mc.gui.screen()).menu().centreOf("Add temporary waypoint");
				return new double[] {p[0], p[1]};
			}, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> WaypointStore.get().in(NETHER).stream().anyMatch(w -> w.kind == Waypoint.Kind.TEMPORARY), 100);
			if (!context.computeOnClient(mc -> WaypointStore.get().in(NETHER).stream().anyMatch(w -> nether.equals(w.map) && w.y == 64))) {
				throw new AssertionError("The archived waypoint was added to another map or at the wrong height");
			}
			context.waitTicks(4);
			if (!context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).pins().stream().anyMatch(p -> p.waypoint().dimension.equals(NETHER)))) {
				throw new AssertionError("The archived dimension's waypoint isn't drawn");
			}
			clickDimension(context, "End", END, live);
			showTile(context, false);
			if (!context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).pins().isEmpty())) {
				throw new AssertionError("The Nether waypoint leaked into the End");
			}
			context.takeScreenshot("dimension-browser-end");
			// Export uses the selected dimension too, even though the player never visited it.
			Set<Path> oldExports = new HashSet<>(exports(context));
			click(context, mc -> centre(((MapScreen) mc.gui.screen()).exportButton()), InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> mc.gui.screen() instanceof ExportScreen, 100);
			click(context, mc -> {
				int[] p = ((ExportScreen) mc.gui.screen()).tabCenter(1);
				return new double[] {p[0], p[1]};
			}, InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> ((ExportScreen) mc.gui.screen()).plannedArea() != null, 600);
			click(context, mc -> centre(((ExportScreen) mc.gui.screen()).saveButton()), InputConstants.MOUSE_BUTTON_LEFT);
			context.waitFor(mc -> mc.gui.screen() instanceof MapScreen && MapExport.running() == null, 1200);
			List<Path> made = exports(context).stream().filter(p -> !oldExports.contains(p)).toList();
			if (made.size() != 1) {
				throw new AssertionError("The archived dimension didn't export: " + made);
			}
			try {
				var image = ImageIO.read(made.get(0).toFile());
				int pixel = image.getRGB(image.getWidth() / 2, image.getHeight() / 2);
				// The fog's grain changes even fully explored pixels by a few colour levels.
				if (Math.abs((pixel >> 16 & 255) - 32) > 8 || Math.abs((pixel >> 8 & 255) - 192) > 8 || Math.abs((pixel & 255) - 64) > 8) {
					throw new AssertionError("The export isn't the green End fixture");
				}
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			clickDimension(context, "Nether", NETHER, live);
			context.waitFor(mc -> ((MapScreen) mc.gui.screen()).view() != null, 100);
			if (!context.computeOnClient(mc -> Math.abs(((MapScreen) mc.gui.screen()).view().centerU() - (TILE.minU() + TILE.units() / 2)) < 1)) {
				throw new AssertionError("Switching dimensions forgot the Nether camera");
			}
			clickDimension(context, "Overworld", OVERWORLD, live);
			if (!context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).map() == live.map())) {
				throw new AssertionError("Returning to the live dimension replaced its map");
			}
			context.takeScreenshot("dimension-browser-overworld");
			click(context, mc -> centre(((MapScreen) mc.gui.screen()).drawButton()), InputConstants.MOUSE_BUTTON_LEFT);
			context.runOnClient(mc -> {
				var screen = (MapScreen) mc.gui.screen();
				Button pen = screen.drawMode().toolButtons().get(0);
				for (var child : screen.children()) {
					if (child instanceof Button b && List.of("Overworld", "Nether", "End").contains(b.getMessage().getString())
						&& b.getX() < pen.getX() + pen.getWidth()) {
						throw new AssertionError("The dimension selector overlaps the drawing toolbar");
					}
				}
			});
			clickDimension(context, "Nether", NETHER, live);
			if (context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).drawMode().active())) {
				throw new AssertionError("Switching dimensions left a drawing gesture active");
			}
			click(context, mc -> centre(((MapScreen) mc.gui.screen()).drawButton()), InputConstants.MOUSE_BUTTON_LEFT);
			click(context, mc -> centre(((MapScreen) mc.gui.screen()).drawMode().toolButtons().get(3)), InputConstants.MOUSE_BUTTON_LEFT);
			click(context, mc -> new double[] {mc.gui.screen().width / 2.0, mc.gui.screen().height / 2.0}, InputConstants.MOUSE_BUTTON_LEFT);
			context.getInput().typeChars("Nether note");
			context.getInput().pressKey(InputConstants.KEY_RETURN);
			context.waitTicks(3);
			// Keep a second half-typed note by switching dimensions, then come back.
			click(context, mc -> new double[] {mc.gui.screen().width / 2.0 + 20, mc.gui.screen().height / 2.0 + 20}, InputConstants.MOUSE_BUTTON_LEFT);
			context.getInput().typeChars("Kept on switch");
			clickDimension(context, "End", END, live);
			if (!context.computeOnClient(mc -> DrawingStore.get().in(NETHER).stream().filter(d -> d.kind == Drawing.Kind.NOTE && nether.equals(d.map)).count() == 2
				&& DrawingStore.get().in(OVERWORLD).stream().noneMatch(d -> d.text.equals("Nether note") || d.text.equals("Kept on switch")))) {
				throw new AssertionError("Notes made in the archived Nether were saved in another dimension");
			}
			clickDimension(context, "Nether", NETHER, live);
			context.getInput().resizeWindow(960, 720);
			context.waitTicks(5);
			context.takeScreenshot("dimension-browser-small");
			clickDimension(context, "Overworld", OVERWORLD, live);
			context.getInput().resizeWindow(1600, 900);
			context.waitTicks(4);
			// An unvisited dimension stays empty; no live Overworld tiles are baked into its folder.
			String empty = context.computeOnClient(mc -> maps.create(END, "Browser Empty"));
			clickDimension(context, "End", END, live);
			context.waitTicks(30);
			context.takeScreenshot("dimension-browser-empty");
			context.setScreen(() -> null);
			if (!Arrays.equals(netherBefore, bytes(netherFile)) || !Arrays.equals(endBefore, bytes(endFile))) {
				throw new AssertionError("Browsing changed the archived map pixels or saved spots");
			}
			Path emptyFolder = context.computeOnClient(mc -> maps.tileFolder(END, empty));
			try (var files = Files.walk(emptyFolder)) {
				if (files.anyMatch(p -> p.toString().endsWith(".omt"))) {
					throw new AssertionError("Browsing an empty End wrote live terrain there");
				}
			} catch (java.nio.file.NoSuchFileException expected) {
				// An empty archive need not create its folder.
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			context.runOnClient(mc -> { maps.use(END, end); maps.delete(END, empty); });
			dev.diorama.Diorama.LOGGER.info("Dimension browser checks passed: buttons, saved terrain, pins, export, live isolation and unchanged archive files");
		} finally {
			context.setScreen(() -> null);
			context.runOnClient(mc -> {
				maps.use(NETHER, oldNether);
				maps.use(END, oldEnd);
				maps.delete(NETHER, nether);
				maps.delete(END, end);
			});
		}
	}

	private static Path fixture(ClientGameTestContext context, Path folder, boolean nether, int colour) {
		String name = context.computeOnClient(mc -> {
			long pitch = Math.round(MapStyle.get().pitchDegrees());
			return nether ? "under-roof-" + pitch + (ServerRules.fogOfWar(true) ? "-fog" : "") : pitch == 60 ? "surface" : "surface-" + pitch;
		});
		TileStore store = new TileStore(folder.resolve(name));
		int[] pixels = new int[Projection.TILE_PIXELS * Projection.TILE_PIXELS];
		Arrays.fill(pixels, colour);
		byte[] coverage = new byte[Projection.COVERAGE_CELLS * Projection.COVERAGE_CELLS];
		Arrays.fill(coverage, (byte) 255);
		int[] spots = new int[TileSpots.COLUMNS * TileSpots.ROWS];
		Arrays.fill(spots, 0x02000040);
		store.baked(TileImage.full(TILE, Mipmaps.build(pixels, Projection.TILE_PIXELS), coverage).withSpots(spots));
		store.close();
		return folder.resolve(name).resolve("2_1.omt");
	}

	private static void showTile(ClientGameTestContext context, boolean nether) {
		context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(TILE.minU() + TILE.units() / 2, TILE.minV() + TILE.units() / 2, 1, TileKey.SURFACE));
		context.waitFor(mc -> {
			var map = ((MapScreen) mc.gui.screen()).map();
			return map != null && map.chunkCount() == 0 && map.roofless() == nether && map.peek(TILE) != null && !map.peek(TILE).isEmpty();
		}, 600);
		context.waitTicks(8);
	}

	private static void clickDimension(ClientGameTestContext context, String label, String expected, MapSession live) {
		click(context, mc -> centre((Button) mc.gui.screen().children().stream()
			.filter(w -> w instanceof Button b && b.getMessage().getString().equals(label)).findFirst().orElseThrow()), InputConstants.MOUSE_BUTTON_LEFT);
		if (!context.computeOnClient(mc -> ((MapScreen) mc.gui.screen()).dimension().equals(expected)
			&& MapSession.get() == live && mc.level.dimension().identifier().toString().equals(OVERWORLD))) {
			throw new AssertionError("The " + label + " button didn't change only the fullscreen dimension");
		}
	}

	private static double[] centre(Button b) {
		return new double[] {b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0};
	}

	private static void click(ClientGameTestContext context, Function<Minecraft, double[]> point, int button) {
		double[] p = context.computeOnClient(mc -> {
			double[] at = point.apply(mc);
			double scale = mc.getWindow().getGuiScale();
			return new double[] {at[0] * scale, at[1] * scale};
		});
		context.getInput().setCursorPos(p[0], p[1]);
		context.waitTicks(2);
		context.getInput().pressMouse(button);
		context.waitTicks(4);
	}

	private static byte[] bytes(Path file) {
		try { return Files.readAllBytes(file); } catch (java.io.IOException e) { throw new AssertionError(e); }
	}

	private static List<Path> exports(ClientGameTestContext context) {
		Path folder = context.computeOnClient(mc -> mc.gameDirectory.toPath().resolve("screenshots"));
		try (var files = Files.list(folder)) {
			return files.filter(p -> p.getFileName().toString().startsWith("diorama_") && p.toString().endsWith(".png")).toList();
		} catch (java.io.IOException e) { throw new AssertionError(e); }
	}
}
