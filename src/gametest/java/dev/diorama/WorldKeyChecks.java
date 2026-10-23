package dev.diorama;

import com.mojang.realmsclient.dto.RealmsServer;
import net.minecraft.client.multiplayer.ServerData;

/** Runs in the real client so the Realm's ServerData is created through the production mixin. */
public final class WorldKeyChecks {
	public static void check() {
		RealmsServer realm = new RealmsServer();
		realm.id = 42;
		realm.activeSlot = 1;
		realm.name = "Home";
		String first = WorldKey.address(realm.toServerData("20.5.81.223"));
		WorldKey.onJoinOrLeave();
		realm.name = "Renamed";
		String restarted = WorldKey.address(realm.toServerData("20.5.80.173"));
		check(first.equals(restarted), "Restarting or renaming a Realm changed its identity");
		check(!first.equals(WorldKey.key("realm", "unknown")), "The Realm identity mixin didn't run");
		realm.id = 43;
		check(!first.equals(WorldKey.address(realm.toServerData("20.5.80.173"))), "Different Realms share an identity");
		realm.id = 42;
		realm.activeSlot = 2;
		check(!first.equals(WorldKey.address(realm.toServerData("20.5.80.173"))), "Different Realm slots share an identity");
		ServerData ordinary = new ServerData("Server", "example.org:25565", ServerData.Type.OTHER);
		check(WorldKey.address(ordinary).equals(WorldKey.key("server", ordinary.ip)), "Changed ordinary server map identities");
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
