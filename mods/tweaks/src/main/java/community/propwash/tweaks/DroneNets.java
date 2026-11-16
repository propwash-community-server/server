package community.propwash.tweaks;

import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drone nets: vanilla blocks the maps resource pack draws as nets (docs/maps.md), chosen among copper
 * blocks the maps didn't use:
 * oxidized copper bars (Drone Net), copper bars (Worn Drone Net), waxed copper bars (Torn Drone Net),
 * copper trapdoors (Flat Drone Net), exposed copper trapdoors (Worn Flat Drone Net) and weathered copper
 * trapdoors (Torn Flat Drone Net). Waxed oxidized copper bars are nets too, as maps built before
 * 2026-11-16 used them (the pack gives them back their vanilla name and icon, so nobody picks them).
 *
 * Nets stay what they are: they don't age, axes don't scrape them, honeycomb doesn't wax them and flat
 * nets don't open (DroneNetAgingMixin, DroneNetToolMixin, DroneNetWaxMixin, DroneNetTrapdoorMixin).
 * Placing one plays propwash:block.drone_net.place (DroneNetSoundMixin), breaking one
 * propwash:block.drone_net.break, for everyone nearby: sounds the maps pack defines, silent without it.
 */
public final class DroneNets {
    public static final SoundEvent PLACE = SoundEvent.createVariableRangeEvent(
            Identifier.fromNamespaceAndPath("propwash", "block.drone_net.place"));
    public static final SoundEvent BREAK = SoundEvent.createVariableRangeEvent(
            Identifier.fromNamespaceAndPath("propwash", "block.drone_net.break"));
    private static final Set<String> NETS = Set.of(
            "oxidized_copper_bars", "waxed_oxidized_copper_bars",
            "copper_bars", "waxed_copper_bars",
            "copper_trapdoor", "exposed_copper_trapdoor", "weathered_copper_trapdoor");

    private DroneNets() {
    }

    static void init() {
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (isNet(state)) {
                level.playSound(null, pos, BREAK, net.minecraft.sounds.SoundSource.BLOCKS, 1.0f,
                        0.9f + level.getRandom().nextFloat() * 0.2f);
            }
        });
    }

    public static boolean isNet(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id.getNamespace().equals("minecraft") && NETS.contains(id.getPath());
    }
}
