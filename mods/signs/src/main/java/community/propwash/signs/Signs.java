package community.propwash.signs;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import community.propwash.signs.mixin.SignBlockEntityAccessor;
import java.util.function.UnaryOperator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * /sign, on the side of the sign the player looks at:
 * text <line|line|...>, set <1-4> <text> (& codes, &#rrggbb), clear <1-4>, click <command>, noclick, wax, unwax.
 */
public final class Signs implements ModInitializer {
    static final String PERMISSION = "propwash.sign";

    /** The sign the player looks at, and the side facing them. */
    private record Target(SignBlockEntity sign, SignTextSlot slot) {
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(command()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("sign").requires(source -> permitted(source, PERMISSION))
                .then(Commands.literal("text")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(c -> setAll(c.getSource(), StringArgumentType.getString(c, "text")))))
                .then(Commands.literal("set")
                        .then(Commands.argument("line", IntegerArgumentType.integer(1, SignText.LINES))
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(c -> setLine(c.getSource(), IntegerArgumentType.getInteger(c, "line"),
                                                StringArgumentType.getString(c, "text"))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("line", IntegerArgumentType.integer(1, SignText.LINES))
                                .executes(c -> setLine(c.getSource(), IntegerArgumentType.getInteger(c, "line"), ""))))
                .then(Commands.literal("click")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(c -> click(c.getSource(), StringArgumentType.getString(c, "command")))))
                .then(Commands.literal("noclick").executes(c -> click(c.getSource(), null)))
                .then(Commands.literal("wax").executes(c -> wax(c.getSource(), true)))
                .then(Commands.literal("unwax").executes(c -> wax(c.getSource(), false)));
    }

    /**
     * The whole sign: lines split by |, the lines not given are emptied. Colours and bold etc. carry
     * on to the next line. Clicks stay.
     */
    private static int setAll(CommandSourceStack source, String text) {
        Target target = target(source);
        if (target == null) {
            return 0;
        }
        String[] lines = text.split("\\|", -1);
        if (lines.length > SignText.LINES) {
            source.sendFailure(Component.literal("A sign has " + SignText.LINES + " lines; you gave " + lines.length + "."));
            return 0;
        }
        edit(target, signText -> {
            SignText.Mutable mutable = signText.asMutable();
            Style[] style = {Style.EMPTY};
            for (int i = 0; i < SignText.LINES; i++) {
                ClickEvent click = signText.getMessages(false).get(i).getStyle().getClickEvent();
                MutableComponent component = Component.empty().withStyle(Style.EMPTY.withClickEvent(click));
                if (i < lines.length) {
                    component.append(parse(lines[i], style));
                }
                mutable.setLine(i, component);
            }
            return mutable.asImmutable();
        });
        source.sendSuccess(() -> Component.literal("Sign text set."), false);
        return 1;
    }

    /** Replaces a line's text; a click on that line stays. */
    private static int setLine(CommandSourceStack source, int line, String text) {
        Target target = target(source);
        if (target == null) {
            return 0;
        }
        int i = line - 1;
        edit(target, signText -> {
            ClickEvent click = signText.getMessages(false).get(i).getStyle().getClickEvent();
            MutableComponent component = Component.empty().withStyle(Style.EMPTY.withClickEvent(click));
            component.append(parse(text, new Style[] {Style.EMPTY}));
            return signText.asMutable().setLine(i, component).asImmutable();
        });
        source.sendSuccess(() -> Component.literal(text.isEmpty() ? "Line " + line + " cleared." : "Line " + line + " set."), false);
        return 1;
    }

    /**
     * Puts the command on line 1 (the whole sign is clickable) and removes any from the other lines,
     * or with null removes them all. Allows click commands and waxes the sign, so a click doesn't
     * open the editor.
     */
    private static int click(CommandSourceStack source, String command) {
        Target target = target(source);
        if (target == null) {
            return 0;
        }
        ClickEvent click = command == null ? null
                : new ClickEvent.RunCommand(command.startsWith("/") ? command : "/" + command);
        edit(target, signText -> {
            SignText.Mutable mutable = signText.asMutable();
            for (int i = 0; i < SignText.LINES; i++) {
                ClickEvent lineClick = i == 0 ? click : null;
                mutable.modifyLine(i, c -> c.copy().withStyle(c.getStyle().withClickEvent(lineClick)));
            }
            return mutable.asImmutable();
        });
        if (command != null) {
            ((SignBlockEntityAccessor) target.sign()).signs$setAllowOpFeatures(true);
            target.sign().setWaxed(true);
            ((SignBlockEntityAccessor) target.sign()).signs$markUpdated();
            source.sendSuccess(() -> Component.literal("Clicking this sign now runs " + ((ClickEvent.RunCommand) click).command()
                    + " (sign waxed)."), false);
        } else {
            source.sendSuccess(() -> Component.literal("Click command removed."), false);
        }
        return 1;
    }

    private static int wax(CommandSourceStack source, boolean waxed) {
        Target target = target(source);
        if (target == null) {
            return 0;
        }
        target.sign().setWaxed(waxed);
        ((SignBlockEntityAccessor) target.sign()).signs$markUpdated();
        source.sendSuccess(() -> Component.literal(waxed ? "Sign waxed." : "Sign unwaxed: it can be edited again."), false);
        return 1;
    }

    private static void edit(Target target, UnaryOperator<SignText> change) {
        target.sign().updateText(change, target.slot());
    }

    private static Target target(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("Only a player can use /sign."));
            return null;
        }
        HitResult hit = player.pick(player.blockInteractionRange() + 1, 1.0f, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK
                && player.level().getBlockEntity(blockHit.getBlockPos()) instanceof SignBlockEntity sign) {
            return new Target(sign, sign.getSlotPlayerIsFacing(player));
        }
        source.sendFailure(Component.literal("Look at a sign first."));
        return null;
    }

    /**
     * Text with & codes: &0-&9 and &a-&f colours, &#rrggbb, &l bold, &o italic, &n underlined,
     * &m strikethrough, &k obfuscated, &r reset. Unlike chat's § codes, a colour keeps the bold etc.
     * Starts with styleHolder[0] and leaves the style at the end in it, for the next line.
     */
    static MutableComponent parse(String text, Style[] styleHolder) {
        MutableComponent result = Component.empty();
        Style style = styleHolder[0];
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 7 < text.length() && text.charAt(i + 1) == '#') {
                String hex = text.substring(i + 2, Math.min(i + 8, text.length()));
                if (hex.length() == 6 && hex.chars().allMatch(h -> Character.digit(h, 16) >= 0)) {
                    flush(result, run, style);
                    style = style.withColor(TextColor.fromRgb(Integer.parseInt(hex, 16)));
                    i += 7;
                    continue;
                }
            }
            ChatFormatting format = c == '&' && i + 1 < text.length() ? ChatFormatting.getByCode(text.charAt(i + 1)) : null;
            if (format == null) {
                run.append(c);
                continue;
            }
            flush(result, run, style);
            if (format == ChatFormatting.RESET) {
                style = Style.EMPTY;
            } else if (TextColor.fromLegacyFormat(format) != null) {
                style = style.withColor(TextColor.fromLegacyFormat(format));
            } else {
                style = style.applyFormat(format);
            }
            i++;
        }
        flush(result, run, style);
        styleHolder[0] = style;
        return result;
    }

    private static void flush(MutableComponent result, StringBuilder run, Style style) {
        if (!run.isEmpty()) {
            result.append(Component.literal(run.toString()).withStyle(style));
            run.setLength(0);
        }
    }

    private static java.lang.invoke.MethodHandle permissionCheck;

    static {
        try {
            permissionCheck = java.lang.invoke.MethodHandles.publicLookup().findStatic(
                    Class.forName("me.lucko.fabric.api.permissions.v0.Permissions"), "check",
                    java.lang.invoke.MethodType.methodType(boolean.class,
                            net.minecraft.commands.SharedSuggestionProvider.class, String.class, boolean.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            permissionCheck = null; // no permissions mod: only ops (level 2)
        }
    }

    static boolean permitted(CommandSourceStack source, String permission) {
        if (permissionCheck == null) {
            return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        }
        try {
            return (boolean) permissionCheck.invokeExact((net.minecraft.commands.SharedSuggestionProvider) source, permission, false);
        } catch (Throwable e) {
            return false;
        }
    }
}
