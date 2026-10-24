package community.propwash.kits;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Players' own kits: /kit create <name> opens a chest titled with the name, filled with copies of
 * items (KitMenu); /kit <name> gives copies of them again, once per cooldown (staff, with
 * propwash.kits.staff: staff.cooldown); /kit edit <name>, /kit delete <name>, /kit list. How many kits and items a player gets
 * is config/kits.properties: one set for everyone, one for players with propwash.kits.supporter
 * (LuckPerms, through the fabric-permissions-api that Vanilla Permissions brings). A player over
 * their limit (no longer a supporter) keeps their kits, but only the first ones work. Kits are saved
 * per player in the folder storage= of the config (<uuid>.dat), default the world's data/kits: servers
 * given the same folder share kits, read again whenever the player joins one of them. The cooldown is
 * per server.
 */
public final class Kits implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("kits");
    private static final Path CONFIG = FabricLoader.getInstance().getConfigDir().resolve("kits.properties");
    private static final String SUPPORTER = "propwash.kits.supporter";
    private static final String STAFF = "propwash.kits.staff";
    private static final Set<String> RESERVED = Set.of("list", "create", "edit", "delete");

    private static volatile long cooldownSeconds = 20;
    private static volatile long staffCooldownSeconds = 2;
    private static volatile int kits = 1;
    private static volatile int items = 3;
    private static volatile int supporterKits = 3;
    private static volatile int supporterItems = 18;
    private static volatile long configModified = -1;
    private static volatile String storage = "";

    private static MethodHandle permissionCheck;
    private static MinecraftServer server;
    /** Each player's kits by name, in the order they were made; loaded when first needed. */
    private static final Map<UUID, LinkedHashMap<String, List<ItemStack>>> loaded = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    static {
        try {
            permissionCheck = MethodHandles.publicLookup().findStatic(
                    Class.forName("me.lucko.fabric.api.permissions.v0.Permissions"), "check",
                    MethodType.methodType(boolean.class, Entity.class, String.class, boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.warn("No fabric-permissions-api: everyone gets the default kit limits");
        }
    }

    @Override
    public void onInitialize() {
        readConfig();
        ServerLifecycleEvents.SERVER_STARTED.register(s -> server = s);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> loaded.clear());
        // Another server may have changed them meanwhile (a shared storage folder).
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> loaded.remove(handler.getPlayer().getUUID()));
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            SuggestionProvider<CommandSourceStack> own = (ctx, builder) -> {
                ServerPlayer player = ctx.getSource().getPlayer();
                return SharedSuggestionProvider.suggest(player == null ? List.of() : kitsOf(player).keySet(), builder);
            };
            dispatcher.register(Commands.literal("kit")
                    .executes(Kits::list)
                    .then(Commands.literal("list").executes(Kits::list))
                    .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word())
                            .executes(Kits::create)))
                    .then(Commands.literal("edit").then(Commands.argument("name", StringArgumentType.word())
                            .suggests(own).executes(Kits::edit)))
                    .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word())
                            .suggests(own).executes(Kits::delete)))
                    .then(Commands.argument("name", StringArgumentType.word()).suggests(own).executes(Kits::give)));
        });
    }

    static MutableComponent gray(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static boolean has(ServerPlayer player, String permission) {
        if (permissionCheck == null) {
            return false;
        }
        try {
            return (boolean) permissionCheck.invokeExact((Entity) player, permission, false);
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean supporter(ServerPlayer player) {
        return has(player, SUPPORTER);
    }

    private static int maxKits(ServerPlayer player) {
        readConfig();
        return supporter(player) ? supporterKits : kits;
    }

    private static int maxItems(ServerPlayer player) {
        readConfig();
        return supporter(player) ? supporterItems : items;
    }

    private static String name(CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, "name").toLowerCase();
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        Map<String, List<ItemStack>> mine = kitsOf(player);
        int max = maxKits(player);
        if (mine.isEmpty()) {
            player.sendSystemMessage(gray("No kits yet. /kit create <name> makes one (you can have " + max
                    + ", up to " + maxItems(player) + " items each)."));
            return 1;
        }
        player.sendSystemMessage(gray("Your kits (" + mine.size() + " of " + max + "): " + String.join(", ", mine.keySet())
                + ". /kit <name> to take one, /kit edit <name>, /kit delete <name>."));
        return 1;
    }

    private static int create(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        String name = name(ctx);
        if (!name.matches("[a-z0-9_-]{1,16}") || RESERVED.contains(name)) {
            player.sendSystemMessage(gray("Kit names: up to 16 letters, digits, _ or -, and not list, create, edit or delete."));
            return 0;
        }
        Map<String, List<ItemStack>> mine = kitsOf(player);
        if (mine.containsKey(name)) {
            player.sendSystemMessage(gray("You have a kit called " + name + " already: /kit edit " + name + "."));
            return 0;
        }
        int max = maxKits(player);
        if (mine.size() >= max) {
            player.sendSystemMessage(gray("You can have " + max + (max == 1 ? " kit" : " kits")
                    + (max < supporterKits ? " (supporters: " + supporterKits + ")" : "") + ". /kit delete <name> to make room."));
            return 0;
        }
        open(player, name, null);
        return 1;
    }

    private static int edit(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        String name = name(ctx);
        List<ItemStack> kit = kitsOf(player).get(name);
        if (kit == null) {
            player.sendSystemMessage(gray("No kit called " + name + ". /kit list shows yours."));
            return 0;
        }
        open(player, name, kit);
        return 1;
    }

    /** The editor; closing it saves the kit (a new one only if it has something in it). */
    private static void open(ServerPlayer player, String name, List<ItemStack> kit) {
        int limit = maxItems(player);
        Component locked = gray(limit < supporterItems ? "Supporters' kits hold " + supporterItems + " items" : "Kits hold " + limit + " items");
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> KitMenu.create(id, inventory, kit, limit, locked, slots -> {
            boolean empty = slots.stream().allMatch(ItemStack::isEmpty);
            LinkedHashMap<String, List<ItemStack>> mine = kitsOf(player);
            if (kit == null && empty) {
                player.sendSystemMessage(gray("Kit " + name + " not saved: it was empty."));
                return;
            }
            if (kit == null && mine.size() >= maxKits(player)) {
                return; // made another kit meanwhile
            }
            mine.put(name, slots);
            save(player.getUUID());
            player.sendSystemMessage(gray("Kit " + name + " saved. /kit " + name + " to take it."));
        }), Component.literal("Kit: " + name)));
    }

    private static int delete(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        String name = name(ctx);
        if (kitsOf(player).remove(name) == null) {
            player.sendSystemMessage(gray("No kit called " + name + "."));
            return 0;
        }
        save(player.getUUID());
        player.sendSystemMessage(gray("Kit " + name + " deleted."));
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return 0;
        }
        String name = name(ctx);
        LinkedHashMap<String, List<ItemStack>> mine = kitsOf(player);
        List<ItemStack> kit = mine.get(name);
        if (kit == null) {
            player.sendSystemMessage(gray("No kit called " + name + ". /kit list shows yours, /kit create <name> makes one."));
            return 0;
        }
        int max = maxKits(player);
        if (new ArrayList<>(mine.keySet()).indexOf(name) >= max) {
            player.sendSystemMessage(gray("You can use " + max + (max == 1 ? " kit" : " kits") + " now; /kit delete one of the others."));
            return 0;
        }
        long now = System.currentTimeMillis();
        Long last = lastUse.get(player.getUUID());
        long cooldown = has(player, STAFF) ? staffCooldownSeconds : cooldownSeconds;
        long wait = last == null ? 0 : last + cooldown * 1000 - now;
        if (wait > 0) {
            player.sendSystemMessage(gray("Wait " + (wait + 999) / 1000 + " s before the next /kit."));
            return 0;
        }
        lastUse.put(player.getUUID(), now);
        int given = 0;
        int limit = maxItems(player);
        for (ItemStack stack : kit) {
            if (!stack.isEmpty() && given < limit) {
                player.getInventory().placeItemBackInInventory(stack.copy(), Prediction.SERVER_ONLY);
                given++;
            }
        }
        player.sendSystemMessage(gray("Kit " + name + ": " + given + (given == 1 ? " item." : " items.")));
        return 1;
    }

    private static Path file(UUID player) {
        readConfig();
        Path folder = storage.isBlank() ? server.getWorldPath(LevelResource.ROOT).resolve("data/kits") : Path.of(storage);
        return folder.resolve(player + ".dat");
    }

    private static LinkedHashMap<String, List<ItemStack>> kitsOf(ServerPlayer player) {
        return loaded.computeIfAbsent(player.getUUID(), Kits::load);
    }

    private static LinkedHashMap<String, List<ItemStack>> load(UUID player) {
        LinkedHashMap<String, List<ItemStack>> out = new LinkedHashMap<>();
        Path f = file(player);
        if (!Files.exists(f)) {
            return out;
        }
        try {
            RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
            ListTag list = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap()).getListOrEmpty("kits");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag kit = list.getCompoundOrEmpty(i);
                ListTag slots = kit.getListOrEmpty("items");
                List<ItemStack> stacks = new ArrayList<>();
                for (int j = 0; j < slots.size(); j++) {
                    Tag tag = slots.get(j);
                    stacks.add(ItemStack.OPTIONAL_CODEC.parse(ops, tag).result().orElse(ItemStack.EMPTY));
                }
                out.put(kit.getStringOr("name", "kit" + i), stacks);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}", f, e);
        }
        return out;
    }

    private static void save(UUID player) {
        Map<String, List<ItemStack>> mine = loaded.get(player);
        if (mine == null) {
            return;
        }
        RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ListTag list = new ListTag();
        mine.forEach((name, stacks) -> {
            CompoundTag kit = new CompoundTag();
            kit.putString("name", name);
            ListTag slots = new ListTag();
            for (ItemStack stack : stacks) {
                slots.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).getOrThrow());
            }
            kit.put("items", slots);
            list.add(kit);
        });
        CompoundTag root = new CompoundTag();
        root.put("kits", list);
        try {
            Path f = file(player);
            Files.createDirectories(f.getParent());
            NbtIo.writeCompressed(root, f);
        } catch (IOException e) {
            LOGGER.warn("Couldn't save the kits of {}", player, e);
        }
    }

    /** config/kits.properties, read again when it changes. */
    static void readConfig() {
        try {
            if (!Files.exists(CONFIG)) {
                Files.writeString(CONFIG, "# /kit: seconds between two kits for each player (nobody skips it), and how many kits\n"
                        + "# and items per kit; supporter.*: for players with propwash.kits.supporter.\n"
                        + "cooldown=20\nkits=1\nitems=3\nsupporter.kits=3\nsupporter.items=18\n"
                        + "# The cooldown for players with propwash.kits.staff.\nstaff.cooldown=2\n"
                        + "# Folder for the kits; servers with the same one share them. Empty: this world's data/kits.\nstorage=\n");
            }
            long modified = Files.getLastModifiedTime(CONFIG).toMillis();
            if (modified == configModified) {
                return;
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(CONFIG)) {
                p.load(in);
            }
            cooldownSeconds = Math.max(0, Long.parseLong(p.getProperty("cooldown", "20").trim()));
            staffCooldownSeconds = Math.max(0, Long.parseLong(p.getProperty("staff.cooldown", "2").trim()));
            kits = Math.max(0, Integer.parseInt(p.getProperty("kits", "1").trim()));
            items = Math.clamp(Integer.parseInt(p.getProperty("items", "3").trim()), 1, 54);
            supporterKits = Math.max(0, Integer.parseInt(p.getProperty("supporter.kits", "3").trim()));
            supporterItems = Math.clamp(Integer.parseInt(p.getProperty("supporter.items", "18").trim()), 1, 54);
            storage = p.getProperty("storage", "").trim();
            configModified = modified;
            LOGGER.info("Kits: {} s cooldown ({} s for staff), {} kits of {} items ({} of {} for supporters), stored in {}",
                    cooldownSeconds, staffCooldownSeconds, kits, items, supporterKits, supporterItems,
                    storage.isBlank() ? "the world" : storage);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}; keeping the settings as they were", CONFIG, e);
        }
    }
}
