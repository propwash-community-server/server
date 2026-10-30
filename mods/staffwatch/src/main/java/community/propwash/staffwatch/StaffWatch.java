package community.propwash.staffwatch;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.PermissionNode;
import org.slf4j.Logger;

/**
 * What staff do: for everyone with a group other than default and supporter, or a permission of their
 * own (LuckPerms, set on the player), every command they type (whether it works or not), their joins,
 * server switches and leaves. Private messages keep who they're to but not what they say
 * ("msg Steve …"; see privateText). One JSON object per line in plugins/staffwatch/YYYY-MM.jsonl (UTC
 * months), never rewritten:
 * <ul>
 *   <li>{"t":..., "e":"command", "uuid":..., "name":..., "server":..., "command":"gamemode creative"}</li>
 *   <li>{"t":..., "e":"join", "uuid":..., "name":...}</li>
 *   <li>{"t":..., "e":"server", "uuid":..., "name":..., "server":..., "from":...}</li>
 *   <li>{"t":..., "e":"leave", "uuid":..., "name":..., "server":...}</li>
 * </ul>
 * Other files in the folder are read too: mods/tweaks on plots writes plots-YYYY-MM.jsonl there, with
 * {"e":"admin_break" or "admin_place", ..., "block":..., "x":..., "y":..., "z":..., "plot":...} for every
 * block broken or placed (or bucket poured) in /plot admin mode. /staffwatch [player] [page] shows it
 * all, newest first (propwash.staffwatch).
 */
@Plugin(id = "staffwatch", name = "StaffWatch", version = "1.2.0",
        description = "Records staff commands, joins and leaves; /staffwatch shows them",
        dependencies = {@Dependency(id = "luckperms")})
public final class StaffWatch {
    private static final String PERMISSION = "propwash.staffwatch";
    /** Groups everyone has: only these don't make a player watched. */
    private static final Set<String> ORDINARY = Set.of("default", "supporter");
    private static final int PAGE = 12;
    /** Private messages to a player (Carbon's whisper and its aliases, EssentialsX's): "msg Steve …". */
    private static final Set<String> TO_PLAYER = Set.of("whisper", "w", "message", "msg", "m", "tell", "pm",
            "emsg", "etell", "ewhisper", "epm");
    /** Private messages without a name (a reply, the same conversation, the team): "r …". */
    private static final Set<String> NO_NAME = Set.of("reply", "r", "continue", "c", "ereply", "er", "teammsg", "tm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path folder;
    /** Watched at some point of their visit: their leave is written even if LuckPerms has let go of them. */
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();

    @Inject
    public StaffWatch(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.folder = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("staffwatch").plugin(this).build(),
                new Command());
    }

    /** First, so it's the command as typed, before anything changes or refuses it. */
    @Subscribe(order = PostOrder.FIRST)
    public void onCommand(CommandExecuteEvent event) {
        if (event.getCommandSource() instanceof Player player && watching(player)) {
            JsonObject o = player("command", player);
            player.getCurrentServer().ifPresent(s -> o.addProperty("server", s.getServerInfo().getName()));
            o.addProperty("command", privateText(event.getCommand()));
            write(o);
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onJoin(PostLoginEvent event) {
        if (watching(event.getPlayer())) {
            write(player("join", event.getPlayer()));
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onServer(ServerConnectedEvent event) {
        if (watching(event.getPlayer())) {
            JsonObject o = player("server", event.getPlayer());
            o.addProperty("server", event.getServer().getServerInfo().getName());
            event.getPreviousServer().ifPresent(s -> o.addProperty("from", s.getServerInfo().getName()));
            write(o);
        }
    }

    @Subscribe(order = PostOrder.LAST)
    public void onLeave(DisconnectEvent event) {
        if (online.remove(event.getPlayer().getUniqueId())) {
            JsonObject o = player("leave", event.getPlayer());
            event.getPlayer().getCurrentServer().ifPresent(s -> o.addProperty("server", s.getServerInfo().getName()));
            write(o);
        }
    }

    /**
     * Asked again on every event, so whoever is given a group or permission is watched from then on,
     * and whoever loses theirs isn't; one watched during their visit gets their leave written.
     */
    private boolean watching(Player player) {
        if (watched(player.getUniqueId())) {
            online.add(player.getUniqueId());
            return true;
        }
        return false;
    }

    /**
     * A command with the text of a private message left out, so the watch shows who staff messaged but
     * not what they said: "msg Steve …", "r …", "mail send Steve …". Anything else as it was typed.
     */
    static String privateText(String command) {
        String[] parts = command.strip().split("\\s+", 4);
        String name = parts[0].toLowerCase(Locale.ROOT);
        name = name.substring(name.indexOf(':') + 1); // carbon:whisper, minecraft:msg
        if (TO_PLAYER.contains(name) && parts.length > 2) {
            return parts[0] + " " + parts[1] + " …";
        }
        if (NO_NAME.contains(name) && parts.length > 1) {
            return parts[0] + " …";
        }
        if ((name.equals("mail") || name.equals("email")) && parts.length > 3 && parts[1].equalsIgnoreCase("send")) {
            return parts[0] + " send " + parts[2] + " …";
        }
        return command;
    }

    /** A group other than default and supporter, or any permission set on the player themselves. */
    private static boolean watched(UUID uuid) {
        User user = LuckPermsProvider.get().getUserManager().getUser(uuid);
        if (user == null) {
            return false;
        }
        for (Node node : user.getNodes()) {
            if (!node.getValue()) {
                continue; // a false permission takes something away
            }
            if (node instanceof InheritanceNode group ? !ORDINARY.contains(group.getGroupName()) : node instanceof PermissionNode) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject player(String type, Player player) {
        JsonObject o = new JsonObject();
        o.addProperty("t", Instant.now().toString());
        o.addProperty("e", type);
        o.addProperty("uuid", player.getUniqueId().toString());
        o.addProperty("name", player.getUsername());
        return o;
    }

    private synchronized void write(JsonObject o) {
        Path file = folder.resolve(YearMonth.now(ZoneOffset.UTC) + ".jsonl");
        try {
            Files.createDirectories(folder);
            Files.writeString(file, o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.warn("Couldn't write to {}", file, e);
        }
    }

    /** Every record in the folder, newest first. */
    private List<JsonObject> records() {
        List<JsonObject> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".jsonl")).toList()) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        try {
                            out.add(JsonParser.parseString(line).getAsJsonObject());
                        } catch (RuntimeException e) {
                            // a line cut short (the server stopped while writing it)
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (Files.exists(folder)) {
                logger.warn("Couldn't read {}", folder, e);
            }
        }
        out.sort(Comparator.comparing((JsonObject o) -> text(o, "t")).reversed());
        return out;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static Component line(JsonObject o) {
        String time;
        try {
            time = TIME.format(Instant.parse(text(o, "t")));
        } catch (RuntimeException e) {
            time = text(o, "t");
        }
        String server = text(o, "server");
        String what = switch (text(o, "e")) {
            case "command" -> (server.isEmpty() ? "" : server + ": ") + "/" + text(o, "command");
            case "join" -> "joined";
            case "server" -> "to " + server + (o.has("from") ? " (from " + text(o, "from") + ")" : "");
            case "leave" -> "left" + (server.isEmpty() ? "" : " (on " + server + ")");
            case "admin_break", "admin_place" -> server + (text(o, "e").equals("admin_break") ? ": broke " : ": placed ")
                    + text(o, "block").replace("minecraft:", "") + " at "
                    + text(o, "x") + " " + text(o, "y") + " " + text(o, "z") + " (" + text(o, "plot") + ", plot admin)";
            default -> text(o, "e");
        };
        NamedTextColor color = switch (text(o, "e")) {
            case "command" -> NamedTextColor.WHITE;
            case "admin_break", "admin_place" -> NamedTextColor.GOLD;
            default -> NamedTextColor.GRAY;
        };
        return Component.text(time + " ", NamedTextColor.DARK_GRAY)
                .append(Component.text(text(o, "name") + " ", NamedTextColor.AQUA))
                .append(Component.text(what, color));
    }

    /** /staffwatch [player] [page]: the records, newest first, UTC times. */
    private final class Command implements SimpleCommand {
        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission(PERMISSION);
        }

        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            String who = null;
            int page = 1;
            for (String arg : args) {
                if (arg.matches("\\d{1,4}")) {
                    page = Math.max(1, Integer.parseInt(arg));
                } else if (who == null) {
                    who = arg;
                }
            }
            String name = who;
            int wanted = page;
            // Reading the files is for another thread, not the proxy's.
            proxy.getScheduler().buildTask(StaffWatch.this, () -> show(invocation, name, wanted)).schedule();
        }

        private void show(Invocation invocation, String who, int page) {
            List<JsonObject> rows = records();
            if (who != null) {
                rows = rows.stream().filter(o -> text(o, "name").equalsIgnoreCase(who)).toList();
            }
            String whom = who == null ? "everyone" : who;
            if (rows.isEmpty()) {
                invocation.source().sendMessage(Component.text("Staff watch: nothing recorded for " + whom + ".", NamedTextColor.GRAY));
                return;
            }
            int pages = (rows.size() + PAGE - 1) / PAGE;
            int p = Math.min(page, pages);
            invocation.source().sendMessage(Component.text("Staff watch: " + whom + ", page " + p + "/" + pages
                    + " (newest first, UTC)", NamedTextColor.YELLOW));
            for (JsonObject o : rows.subList((p - 1) * PAGE, Math.min(rows.size(), p * PAGE))) {
                invocation.source().sendMessage(line(o));
            }
            if (p < pages) {
                String next = "/staffwatch " + (who == null ? "" : who + " ") + (p + 1);
                invocation.source().sendMessage(Component.text("Older: " + next, NamedTextColor.GRAY)
                        .clickEvent(ClickEvent.runCommand(next)));
            }
        }

        @Override
        public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length > 1) {
                return CompletableFuture.completedFuture(List.of());
            }
            String start = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return CompletableFuture.supplyAsync(() -> {
                Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (JsonObject o : records()) {
                    String name = text(o, "name");
                    if (!name.isEmpty() && name.toLowerCase(Locale.ROOT).startsWith(start)) {
                        names.add(name);
                    }
                }
                return List.copyOf(names);
            });
        }
    }
}
