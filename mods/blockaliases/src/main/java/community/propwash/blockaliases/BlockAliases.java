package community.propwash.blockaliases;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.internal.registry.InputParser;
import com.sk89q.worldedit.world.block.BaseBlock;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/**
 * Names for WorldEdit that match what the maps resource pack calls a block. "drone_net" (with or
 * without block states, e.g. drone_net[east=true]) is handed to WorldEdit's own parser as
 * oxidized_copper_bars, so patterns, masks and states work as for any block.
 */
public final class BlockAliases implements ModInitializer {
    static final Map<String, String> ALIASES = Map.of(
            "drone_net", "minecraft:oxidized_copper_bars",
            "waxed_drone_net", "minecraft:waxed_oxidized_copper_bars");

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                WorldEdit.getInstance().getBlockFactory().register(new Parser(WorldEdit.getInstance())));
    }

    /** "drone_net[...]" -> "minecraft:oxidized_copper_bars[...]", or null when it isn't an alias. */
    static String expand(String input) {
        int cut = input.length();
        for (char c : new char[] {'[', '{', '|'}) {
            int i = input.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        String name = input.substring(0, cut).toLowerCase(Locale.ROOT);
        if (name.startsWith("propwash:")) name = name.substring("propwash:".length());
        String target = ALIASES.get(name);
        return target == null ? null : target + input.substring(cut);
    }

    static final class Parser extends InputParser<BaseBlock> {
        Parser(WorldEdit worldEdit) {
            super(worldEdit);
        }

        @Override
        public BaseBlock parseFromInput(String input, ParserContext context) throws InputParseException {
            String expanded = expand(input);
            // The expanded name is a real block, so this goes to WorldEdit's default parser, not back here.
            return expanded == null ? null : worldEdit.getBlockFactory().parseFromInput(expanded, context);
        }

        @Override
        public Stream<String> getSuggestions(String input, ParserContext context) {
            String lower = input.toLowerCase(Locale.ROOT);
            return ALIASES.keySet().stream().filter(a -> a.startsWith(lower)).sorted();
        }
    }
}
