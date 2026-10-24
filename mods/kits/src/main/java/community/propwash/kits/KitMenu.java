package community.propwash.kits;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The kit editor: a chest (as many rows as the player's item limit needs) whose slots hold copies.
 * Clicking a kit slot with an item on the cursor puts a copy there (right click: one of it), with an
 * empty cursor clears it; shift-clicking an item in the inventory copies it into the first free kit
 * slot. The player's own items never move, so nothing is lost or duplicated. Slots past the limit
 * are glass panes. Closing saves the kit slots.
 */
final class KitMenu extends ChestMenu {
    private static final MenuType<?>[] TYPES = {MenuType.GENERIC_9x1, MenuType.GENERIC_9x2, MenuType.GENERIC_9x3,
            MenuType.GENERIC_9x4, MenuType.GENERIC_9x5, MenuType.GENERIC_9x6};

    private final int kitSlots;
    private final int limit;
    private final Consumer<List<ItemStack>> onClose;

    private KitMenu(int id, Inventory inventory, SimpleContainer container, int rows, int limit,
                    Consumer<List<ItemStack>> onClose) {
        super(TYPES[rows - 1], id, inventory, container, rows);
        this.kitSlots = rows * 9;
        this.limit = limit;
        this.onClose = onClose;
    }

    /** A menu for these items (null: empty) with room for limit of them. */
    static KitMenu create(int id, Inventory inventory, List<ItemStack> items, int limit, Component lockedName,
                          Consumer<List<ItemStack>> onClose) {
        int rows = Math.max(1, Math.min(6, (limit + 8) / 9));
        SimpleContainer container = new SimpleContainer(rows * 9);
        for (int i = 0; i < rows * 9; i++) {
            if (i >= limit) {
                ItemStack pane = new ItemStack(Items.STAINED_GLASS_PANE.gray());
                pane.set(DataComponents.CUSTOM_NAME, lockedName);
                container.setItem(i, pane);
            } else if (items != null && i < items.size()) {
                container.setItem(i, items.get(i).copy());
            }
        }
        return new KitMenu(id, inventory, container, rows, limit, onClose);
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (slotId >= 0 && slotId < kitSlots) {
            if (slotId < limit && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_MOVE)) {
                ItemStack carried = getCarried();
                if (input == ContainerInput.QUICK_MOVE || carried.isEmpty()) {
                    getContainer().setItem(slotId, ItemStack.EMPTY);
                } else {
                    getContainer().setItem(slotId, button == 1 ? carried.copyWithCount(1) : carried.copy());
                }
            }
            sendAllDataToRemote();
            return;
        }
        if (slotId >= kitSlots && input == ContainerInput.QUICK_MOVE) {
            ItemStack stack = slots.get(slotId).getItem();
            if (!stack.isEmpty()) {
                int free = -1;
                for (int i = 0; i < limit && free < 0; i++) {
                    if (getContainer().getItem(i).isEmpty()) {
                        free = i;
                    }
                }
                if (free >= 0) {
                    getContainer().setItem(free, stack.copy());
                } else if (player instanceof ServerPlayer sp) {
                    sp.sendSystemMessage(Kits.gray("This kit is full (" + limit + " items). Click a kit slot to clear it."));
                }
            }
            sendAllDataToRemote();
            return;
        }
        super.clicked(slotId, button, input, player);
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return slot.index >= kitSlots && super.canDragTo(slot);
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.index >= kitSlots && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // shift-clicks are handled in clicked
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            items.add(getContainer().getItem(i).copy());
        }
        onClose.accept(items);
    }
}
