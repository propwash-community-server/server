package community.propwash.worlds;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.nucleoid.fantasy.Fantasy;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;

/**
 * Builders' worlds for new courses: /world create <name> copies the template map (template in
 * config/worlds.properties, maps:superflat_default) into worlds:<name> and opens it at once through Fantasy;
 * /world tp <name> and /world list. They're ordinary dimension folders (world/dimensions/worlds/<name>),
 * opened again on every start, with the template's dimension type, generator and border and the
 * overworld's clocks and game rules. The worlds namespace keeps them out of /map, /rtp and regen.
 * /world list and /world tp also take the maps (maps:<name>, e.g. lobby): tp lands on the map's
 * EssentialCommands warp of the same name, else on /spawn if that's in the map, else at 0 0.
 * A world gets a dimension type of its own (say a lower min_y) from a datapack file
 * data/worlds/dimension_type/<name>.json; restart to apply.
 */
public final class Worlds implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("worlds");
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("worlds.properties");
    private static final String NAMESPACE = "worlds";
    private static final String MAPS = "maps";
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,32}");
    private static final String CREATE = "propwash.worlds.create";
    private static final String TP = "propwash.worlds.tp";
    // Only the chunks: the template's data/ holds its forced chunks and raids.
    private static final List<String> COPIED = List.of("region", "entities", "poi");

    private static volatile ResourceKey<Level> template = key("maps:superflat_default");
    private static MethodHandle permissionCheck;

    static {
        try {
            permissionCheck = MethodHandles.publicLookup().findStatic(
                    Class.forName("me.lucko.fabric.api.permissions.v0.Permissions"), "check",
                    MethodType.methodType(boolean.class, Entity.class, String.class, boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.warn("No fabric-permissions-api: only the console can use /world");
        }
    }

    @Override
    public void onInitialize() {
        readConfig();
        ServerLifecycleEvents.SERVER_STARTED.register(Worlds::openAll);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            SuggestionProvider<CommandSourceStack> names = (ctx, builder) ->
                    SharedSuggestionProvider.suggest(allNames(ctx.getSource().getServer()), builder);
            dispatcher.register(Commands.literal("world")
                    .requires(s -> allowed(s, TP) || allowed(s, CREATE))
                    .executes(Worlds::list)
                    .then(Commands.literal("list").executes(Worlds::list))
                    .then(Commands.literal("create").requires(s -> allowed(s, CREATE))
                            .then(Commands.argument("name", StringArgumentType.word()).executes(Worlds::create)))
                    .then(Commands.literal("tp").requires(s -> allowed(s, TP))
                            .then(Commands.argument("name", StringArgumentType.word()).suggests(names)
                                    .executes(Worlds::tp))));
        });
    }

    private static boolean allowed(CommandSourceStack source, String permission) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return true;
        }
        if (permissionCheck == null) {
            return false;
        }
        try {
            return (boolean) permissionCheck.invokeExact((Entity) player, permission, false);
        } catch (Throwable e) {
            return false;
        }
    }

    private static ResourceKey<Level> key(String id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
    }

    private static Path folder(MinecraftServer server, ResourceKey<Level> level) {
        Identifier id = level.identifier();
        return server.getWorldPath(LevelResource.ROOT).resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
    }

    private static List<String> names(MinecraftServer server) {
        return names(server, NAMESPACE);
    }

    private static List<String> names(MinecraftServer server, String namespace) {
        return server.levelKeys().stream().map(ResourceKey::identifier)
                .filter(id -> id.getNamespace().equals(namespace)).map(Identifier::getPath).sorted().toList();
    }

    /** Builders' worlds first, then maps not shadowed by a world of the same name. */
    private static List<String> allNames(MinecraftServer server) {
        List<String> all = new ArrayList<>(names(server));
        names(server, MAPS).stream().filter(n -> !all.contains(n)).forEach(all::add);
        return all;
    }

    /** Every world made before: Fantasy doesn't open persistent dimensions again by itself. */
    private static void openAll(MinecraftServer server) {
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("dimensions").resolve(NAMESPACE);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> worlds = Files.list(dir)) {
            for (Path p : worlds.filter(Files::isDirectory).toList()) {
                String name = p.getFileName().toString();
                if (NAME.matcher(name).matches() && open(server, name) == null) {
                    LOGGER.warn("Couldn't open world {}: no template {}", name, template.identifier());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't list {}", dir, e);
        }
        LOGGER.info("Worlds open: {}", names(server));
    }

    /** Opens worlds:<name> (its folder must be in place already), null if the template isn't there. */
    private static ServerLevel open(MinecraftServer server, String name) {
        ServerLevel from = server.getLevel(template);
        if (from == null) {
            return null;
        }
        Holder<DimensionType> type = from.dimensionTypeRegistration();
        ChunkGenerator generator = from.getChunkSource().getGenerator();
        // A dimension type of the world's own name (worlds:<name>, from a datapack) wins over the
        // template's, e.g. for a lower min_y to build further down.
        Optional<Holder.Reference<DimensionType>> own = server.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)
                .get(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.fromNamespaceAndPath(NAMESPACE, name)));
        if (own.isPresent()) {
            int deeper = type.value().minY() - own.get().value().minY();
            type = own.get();
            // Superflat layers start at min_y: air under them keeps new chunks' ground where it was.
            if (deeper > 0 && generator instanceof FlatLevelSource flat) {
                FlatLevelGeneratorSettings s = flat.settings();
                List<FlatLayerInfo> layers = new ArrayList<>();
                layers.add(new FlatLayerInfo(deeper, Blocks.AIR));
                layers.addAll(s.getLayersInfo());
                generator = new FlatLevelSource(s.withBiomeAndLayers(layers, s.structureOverrides(), s.getBiome()));
            }
            LOGGER.info("World {} has its own dimension type: min_y {}", name, type.value().minY());
        }
        RuntimeLevelConfig config = new RuntimeLevelConfig()
                .setDimensionType(type)
                .setGenerator(generator)
                .mirrorOverworldGameState();
        ServerLevel level = Fantasy.get(server)
                .getOrOpenPersistentLevel(Identifier.fromNamespaceAndPath(NAMESPACE, name), config).asLevel();
        // Without a border, flying out generates superflat forever.
        WorldBorder border = level.getWorldBorder();
        border.setCenter(from.getWorldBorder().getCenterX(), from.getWorldBorder().getCenterZ());
        border.setSize(from.getWorldBorder().getSize());
        return level;
    }

    private static int create(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        String name = StringArgumentType.getString(ctx, "name");
        if (!NAME.matcher(name).matches()) {
            source.sendFailure(Component.literal("World names are 1 to 32 of a-z, 0-9 and _."));
            return 0;
        }
        ResourceKey<Level> key = key(NAMESPACE + ":" + name);
        Path to = folder(server, key);
        if (server.getLevel(key) != null || Files.exists(to)) {
            source.sendFailure(Component.literal("There's a world " + name + " already: /world tp " + name));
            return 0;
        }
        ServerLevel from = server.getLevel(template);
        if (from == null) {
            source.sendFailure(Component.literal("The template " + template.identifier() + " isn't on this server."));
            return 0;
        }
        // The template's chunks on disk as they are now, then copied.
        from.save(null, true, false);
        try {
            copy(folder(server, template), to);
        } catch (IOException e) {
            LOGGER.warn("Couldn't copy {} to {}", template.identifier(), to, e);
            source.sendFailure(Component.literal("Couldn't copy the template; see the server log."));
            return 0;
        }
        ServerLevel level = open(server, name);
        LOGGER.info("{} made world {} from {}", source.getTextName(), name, template.identifier());
        source.sendSuccess(() -> Component.literal("Made world " + name + " from " + template.identifier().getPath() + ".")
                .withStyle(ChatFormatting.GRAY), true);
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            send(player, level);
        }
        return 1;
    }

    private static void copy(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        for (String part : COPIED) {
            Path src = from.resolve(part);
            if (!Files.isDirectory(src)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(src)) {
                for (Path p : files.toList()) {
                    Path dest = to.resolve(part).resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(p, dest);
                    }
                }
            }
        }
    }

    private static int tp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        String name = StringArgumentType.getString(ctx, "name");
        MinecraftServer server = source.getServer();
        boolean valid = NAME.matcher(name).matches();
        ServerLevel level = valid ? server.getLevel(key(NAMESPACE + ":" + name)) : null;
        ServerLevel map = valid && level == null ? server.getLevel(key(MAPS + ":" + name)) : null;
        if (level == null && map == null) {
            source.sendFailure(Component.literal("No world " + name + ". /world list"));
            return 0;
        }
        if (player == null) {
            source.sendFailure(Component.literal("Only players can go to a world."));
            return 0;
        }
        if (map != null) {
            sendToMap(player, map, name);
        } else {
            send(player, level);
        }
        return 1;
    }

    /** A map: its warp of the same name, else /spawn if it's in this map, else the ground at 0 0. */
    private static void sendToMap(ServerPlayer player, ServerLevel map, String name) {
        String dimension = map.dimension().identifier().toString();
        CompoundTag data = essentialCommandsData(player.level().getServer());
        for (CompoundTag spot : new CompoundTag[] {
                data.getCompoundOrEmpty("warps").getCompoundOrEmpty(name), data.getCompoundOrEmpty("spawn")}) {
            if (dimension.equals(spot.getStringOr("WorldRegistryKey", ""))) {
                player.teleportTo(map, spot.getDoubleOr("x", 0), spot.getDoubleOr("y", 0), spot.getDoubleOr("z", 0),
                        Set.of(), spot.getFloatOr("headYaw", 0), spot.getFloatOr("pitch", 0), true);
                return;
            }
        }
        send(player, map);
    }

    /** EssentialCommands' warps and spawn (world/essentialcommands/world_data.dat), empty if there are none. */
    private static CompoundTag essentialCommandsData(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("essentialcommands").resolve("world_data.dat");
        if (!Files.exists(file)) {
            return new CompoundTag();
        }
        try {
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            // Saved data may sit under "data" depending on the version.
            return root.contains("warps") || root.contains("spawn") ? root : root.getCompoundOrEmpty("data");
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}", file, e);
            return new CompoundTag();
        }
    }

    /** To the ground at 0 0, where the template's spawn is. */
    private static void send(ServerPlayer player, ServerLevel level) {
        // Load the chunk first: an unloaded chunk's height is the world's bottom, under the bedrock.
        level.getChunk(0, 0);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
        player.teleportTo(level, 0.5, y, 0.5, Set.of(), 0, 0, true);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        List<String> names = names(server);
        List<String> maps = names(server, MAPS);
        String text = (maps.isEmpty() ? "" : "Maps: " + String.join(", ", maps) + "\n")
                + (names.isEmpty()
                        ? "No worlds yet. /world create <name> makes one from " + template.identifier().getPath() + "."
                        : "Worlds: " + String.join(", ", names));
        ctx.getSource().sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GRAY), false);
        return names.size() + maps.size();
    }

    /** config/worlds.properties: template = the map new worlds are copied from. */
    static void readConfig() {
        try {
            if (!Files.exists(CONFIG)) {
                Files.writeString(CONFIG, "# /world create <name> copies this map into worlds:<name>.\n"
                        + "template=maps:superflat_default\n");
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(CONFIG)) {
                p.load(in);
            }
            template = key(p.getProperty("template", "maps:superflat_default").trim());
            LOGGER.info("New worlds are copies of {}", template.identifier());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}; new worlds are copies of {}", CONFIG, template.identifier(), e);
        }
    }
}
