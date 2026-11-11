package community.propwash.pets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

/**
 * Drone pets from Propwash share codes (/pet code): PropwashApi.shareCodes().decode(code), then
 * builds().createDrone(data). By reflection, so pets runs without Propwash, or with a Propwash before
 * 0.6 (no share-code API: then no custom drones). Each player's code is a file, ~/pets/<uuid>.txt of the
 * server's user (/home/mc/pets), so every server on the machine shares it: codes run to 4096
 * characters, too long for LuckPerms meta.
 */
final class ShareDrones {
    private static final Path FOLDER = Path.of(System.getProperty("user.home"), "pets");

    private ShareDrones() {
    }

    /** A decoded drone (its item, with spinning props), or why there isn't one. */
    record Result(ItemStack drone, String error, List<?> unknownParts) {
    }

    static Result decode(String code) {
        try {
            Class<?> api = Class.forName("de.lardtom.propwash.api.PropwashApi");
            Class<?> codesType = Class.forName("de.lardtom.propwash.api.share.ShareCodes");
            Class<?> resultType = Class.forName("de.lardtom.propwash.api.share.ShareResult");
            Class<?> buildsType = Class.forName("de.lardtom.propwash.api.build.BuildAccess");
            Class<?> dataType = Class.forName("de.lardtom.propwash.api.build.DroneData");
            Object codes = api.getMethod("shareCodes").invoke(null);
            Object result = codesType.getMethod("decode", String.class).invoke(codes, code.strip());
            Optional<?> data = (Optional<?>) resultType.getMethod("data").invoke(result);
            if (!(boolean) resultType.getMethod("ok").invoke(result) || data.isEmpty()) {
                return new Result(null, problem(resultType.getMethod("status").invoke(result).toString()), List.of());
            }
            Object builds = api.getMethod("builds").invoke(null);
            ItemStack drone = (ItemStack) buildsType.getMethod("createDrone", dataType).invoke(builds, data.get());
            if (drone.isEmpty()) {
                return new Result(null, "Propwash couldn't build a drone from that code.", List.of());
            }
            drone.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of("blur"), List.of()));
            return new Result(drone, null, (List<?>) resultType.getMethod("unknownParts").invoke(result));
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            return new Result(null, "Share-code drones need Propwash 0.6 or newer on this server.", List.of());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            org.slf4j.LoggerFactory.getLogger("pets").warn("Decoding a share code failed", e);
            return new Result(null, "That share code didn't work (" + e.getClass().getSimpleName() + ").", List.of());
        }
    }

    /** ShareResult.Status in words. */
    private static String problem(String status) {
        return switch (status) {
            case "EMPTY" -> "That share code is empty.";
            case "INVALID_PREFIX", "OTHER_CODE" -> "That isn't a Propwash drone share code.";
            case "BAD_CHARACTER", "TRUNCATED", "CHECKSUM", "CORRUPT" -> "That share code is damaged; copy it again.";
            case "TOO_LONG", "TOO_LARGE" -> "That share code is too long.";
            case "UNSUPPORTED_VERSION" -> "That share code is from a newer Propwash than this server's.";
            default -> "That share code didn't work (" + status + ").";
        };
    }

    /** The player's saved code, or null. */
    static String load(UUID player) {
        Path file = FOLDER.resolve(player + ".txt");
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).strip() : null;
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger("pets").warn("Reading {} failed", file, e);
            return null;
        }
    }

    static void save(UUID player, String code) throws IOException {
        Files.createDirectories(FOLDER);
        Path tmp = FOLDER.resolve(player + ".txt.tmp");
        Files.writeString(tmp, code.strip() + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, FOLDER.resolve(player + ".txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
