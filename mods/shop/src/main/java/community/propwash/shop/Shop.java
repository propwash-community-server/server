package community.propwash.shop;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * /shop: a clickable link to the Ko-fi shop (pets and drone trails) in chat, on every server. Any player
 * can use it; the link text and the Ko-fi address are fixed here, like the link on the hub sign.
 */
@Plugin(id = "shop", name = "Shop", version = "1.0.2",
        description = "/shop: the Ko-fi shop link")
public final class Shop {
    private static final String KOFI_URL = "https://ko-fi.com/propwashfpv";

    // The Propwash Yellow prefix (palette.toml) and the link styling of the announcements plugin.
    private static final String MESSAGE = "<#FFBB00><bold>[Propwash]</bold></#FFBB00> "
            + "Visit our kofi shop to help support the server! "
            + "<click:open_url:'" + KOFI_URL + "'><hover:show_text:'ko-fi.com/propwashfpv'>"
            + "<white><u>ko-fi.com/propwashfpv</u></white></hover></click>";

    private final ProxyServer proxy;

    @Inject
    public Shop(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("shop").plugin(this).build(),
                new SimpleCommand() {
                    @Override
                    public void execute(Invocation invocation) {
                        Component text = MiniMessage.miniMessage().deserialize(MESSAGE);
                        invocation.source().sendMessage(text);
                    }
                });
    }
}
