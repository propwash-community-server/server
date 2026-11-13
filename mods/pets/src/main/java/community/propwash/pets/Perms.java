package community.propwash.pets;

import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PermissionNode;
import net.minecraft.server.level.ServerPlayer;

/**
 * Pets' permissions and saved choices: LuckPerms (permissions and meta, shared by every server) where
 * it's installed. Without it (a bare test server) ops have every pet and choices last until you leave.
 * Only this class touches LuckPerms' classes, so Pets loads without them.
 */
final class Perms {
    private static final boolean LUCKPERMS = FabricLoader.getInstance().isModLoaded("luckperms");

    private Perms() {
    }

    static boolean has(ServerPlayer player, String permission) {
        if (!LUCKPERMS) {
            return player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        }
        return Lp.has(player, permission);
    }

    /** Whether LuckPerms is installed (picks and their saved state need it). */
    static boolean enabled() {
        return LUCKPERMS;
    }

    /** The player's meta value for key, or null. */
    static String meta(ServerPlayer player, String key) {
        return LUCKPERMS ? Lp.meta(player, key) : null;
    }

    /** Gives the player a permission node, kept in LuckPerms (every server). Only with LuckPerms. */
    static void grant(UUID id, String permission) {
        if (LUCKPERMS) {
            Lp.grant(id, permission);
        }
    }

    /** Sets (or with null, removes) the player's meta value for key. */
    static void setMeta(UUID id, String key, String value) {
        if (LUCKPERMS) {
            Lp.setMeta(id, key, value);
        }
    }

    /** Loaded only when LuckPerms is installed. */
    private static final class Lp {
        static LuckPerms api() {
            return LuckPermsProvider.get();
        }

        static boolean has(ServerPlayer player, String permission) {
            return api().getPlayerAdapter(ServerPlayer.class).getPermissionData(player).checkPermission(permission).asBoolean();
        }

        static String meta(ServerPlayer player, String key) {
            return api().getPlayerAdapter(ServerPlayer.class).getMetaData(player).getMetaValue(key);
        }

        static void grant(UUID id, String permission) {
            // Waited for: a pick's permission and its "used" meta must be in place before the next command runs.
            api().getUserManager().modifyUser(id, user -> user.data().add(PermissionNode.builder(permission).build())).join();
        }

        static void setMeta(UUID id, String key, String value) {
            api().getUserManager().modifyUser(id, user -> {
                user.data().clear(NodeType.META.predicate(n -> n.getMetaKey().equals(key)));
                if (value != null) {
                    user.data().add(MetaNode.builder(key, value).build());
                }
            }).join();
        }
    }
}
