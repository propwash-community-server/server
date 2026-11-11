package community.propwash.crossserver;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * /ping: everyone's own ping to the proxy. Staff (propwash.ping.others) and the console can
 * /ping <player> for anyone they can see (vanished players stay hidden from those who can't see them).
 */
final class Ping {
    static final String OTHERS = "propwash.ping.others";

    private final ProxyServer proxy;
    private final Object plugin;
    private final BiPredicate<Player, Player> visible;

    Ping(ProxyServer proxy, Object plugin, BiPredicate<Player, Player> visible) {
        this.proxy = proxy;
        this.plugin = plugin;
        this.visible = visible;
    }

    void register() {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("ping").plugin(plugin).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        ping(invocation.source(), invocation.arguments());
                    }

                    @Override
                    public List<String> suggest(Invocation invocation) {
                        CommandSource source = invocation.source();
                        if (!source.hasPermission(OTHERS) || invocation.arguments().length > 1) {
                            return List.of();
                        }
                        String prefix = invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase();
                        return proxy.getAllPlayers().stream().filter(p -> sees(source, p)).map(Player::getUsername)
                                .filter(n -> n.toLowerCase().startsWith(prefix)).sorted().toList();
                    }
                });
    }

    private void ping(CommandSource source, String[] args) {
        if (args.length == 0) {
            if (source instanceof Player player) {
                source.sendMessage(Component.text("Your ping: ", NamedTextColor.GRAY).append(ms(player.getPing())));
            } else {
                source.sendMessage(Component.text("/ping <player>", NamedTextColor.RED));
            }
            return;
        }
        if (!source.hasPermission(OTHERS)) {
            source.sendMessage(Component.text("You can only see your own ping: /ping", NamedTextColor.RED));
            return;
        }
        Optional<Player> target = proxy.getPlayer(args[0]).filter(p -> sees(source, p));
        if (target.isEmpty()) {
            source.sendMessage(Component.text(args[0] + " isn't online.", NamedTextColor.RED));
            return;
        }
        Player them = target.get();
        String server = them.getCurrentServer().map(s -> " (" + s.getServerInfo().getName() + ")").orElse("");
        source.sendMessage(Component.text(them.getUsername() + "'s ping: ", NamedTextColor.GRAY)
                .append(ms(them.getPing())).append(Component.text(server, NamedTextColor.DARK_GRAY)));
    }

    private boolean sees(CommandSource source, Player target) {
        return !(source instanceof Player player) || visible.test(player, target);
    }

    private static Component ms(long ping) {
        if (ping < 0) {
            return Component.text("not known yet", NamedTextColor.GRAY);
        }
        NamedTextColor color = ping < 80 ? NamedTextColor.GREEN : ping < 150 ? NamedTextColor.YELLOW : NamedTextColor.RED;
        return Component.text(ping + " ms", color);
    }
}
