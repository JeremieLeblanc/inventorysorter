/*
 *     Copyright © 2016 cpw
 *     This file is part of Inventorysorter.
 *
 *     Inventorysorter is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Inventorysorter is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Inventorysorter.  If not, see <http://www.gnu.org/licenses/>.
 */

package cpw.mods.inventorysorter;

import com.google.common.collect.*;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.PlayerContainer;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.Level;

import java.util.function.Consumer;

/**
 * @author cpw
 */
public enum SortingHandler implements Consumer<ContainerContext>
{
    INSTANCE;
    @Override
    public void accept(ContainerContext context)
    {
        if (context == null) throw new NullPointerException("WHUT");
        // Ignore if we can't find ourselves in the slot set
        if (context.slotMapping == null) return;
        final Multiset<ItemStackHolder> itemcounts = InventoryHandler.INSTANCE.getInventoryContent(context);

        if (context.slot.inventory instanceof CraftingInventory)
        {
            distributeInventory(context, itemcounts);
        }
        else if (!context.slotMapping.markAsHeterogeneous)
        {
            compactInventory(context, itemcounts);
        }
    }

    private static ItemStack getStackInRowAndColumn(CraftingInventory inventory, int x, int y) {
        return inventory.getStackInSlot(y * inventory.getWidth() + x);
    }

    private void distributeInventory(final ContainerContext context, final Multiset<ItemStackHolder> itemcounts)
    {
        CraftingInventory ic = (CraftingInventory) context.slot.inventory;
        Multiset<ItemStackHolder> slotCounts = TreeMultiset.create(new InventoryHandler.ItemStackComparator());
        for (int x=0; x<ic.getWidth(); x++)
        {
            for (int y=0; y<ic.getHeight(); y++)
            {
                ItemStack is = getStackInRowAndColumn(ic, x, y);
                if (!is.isEmpty())
                {
                    slotCounts.add(new ItemStackHolder(is));
                }
            }
        }

        final ImmutableMultiset<ItemStackHolder> staticcounts = ImmutableMultiset.copyOf(itemcounts);
        for (int x=0; x<ic.getWidth(); x++)
        {
            for (int y = 0; y < ic.getHeight(); y++)
            {
                ItemStack is = getStackInRowAndColumn(ic, x, y);
                if (!is.isEmpty())
                {
                    ItemStackHolder ish = new ItemStackHolder(is);
                    int count = staticcounts.count(ish);
                    int slotNum = slotCounts.count(ish);
                    final int occurrences = count / slotNum;
                    itemcounts.remove(ish, occurrences);
                    is.setCount(occurrences);
                }
            }
        }
        for (int x=0; x<ic.getWidth(); x++)
        {
            for (int y = 0; y < ic.getHeight(); y++)
            {
                ItemStack is = getStackInRowAndColumn(ic, x, y);
                if (!is.isEmpty())
                {
                    ItemStackHolder ish = new ItemStackHolder(is);
                    if (itemcounts.count(ish) > 0)
                    {
                        is.grow(itemcounts.setCount(ish,0));
                    }
                }
            }
        }
        for (int slot = context.slotMapping.begin; slot < context.slotMapping.end + 1; slot++)
        {
            context.player.openContainer.getSlot(slot).onSlotChanged();
        }
    }

    private void compactInventory(final ContainerContext context, final Multiset<ItemStackHolder> itemcounts)
    {
        final ResourceLocation containerTypeName = lookupContainerTypeName(context.player.container);
        InventorySorter.INSTANCE.lastContainerType = containerTypeName;
        if (InventorySorter.INSTANCE.containerblacklist.contains(containerTypeName)) {
            InventorySorter.INSTANCE.debugLog("Container {} blacklisted", ()->new String[] {containerTypeName.toString()});
            return;
        }

        InventorySorter.INSTANCE.debugLog("Container \"{}\" being sorted", ()->new String[] {containerTypeName.toString()});
        final UnmodifiableIterator<Multiset.Entry<ItemStackHolder>> itemsIterator;
        try
        {
            if (ModList.get().isLoaded("jei")) {
                itemsIterator = JeiInventorySorterPlugin.sortItems(itemcounts);
            } else {
                itemsIterator = Multisets.copyHighestCountFirst(itemcounts).entrySet().iterator();
            }
        }
        catch (Exception e)
        {
            InventorySorter.LOGGER.warn("Weird, the sorting didn't quite work!", e);
            return;
        }
        int slotLow = context.slotMapping.begin;
        int slotHigh = context.slotMapping.end + 1;

        Multiset.Entry<ItemStackHolder> stackHolder = itemsIterator.hasNext() ? itemsIterator.next() : null;
        int itemCount = stackHolder != null ? stackHolder.getCount() : 0;
        for (int i = slotLow; i < slotHigh; i++)
        {
            final Slot slot = context.player.openContainer.getSlot(i);
            if (!slot.canTakeStack(context.player) && slot.getHasStack()) {
                InventorySorter.LOGGER.log(Level.DEBUG, "Slot {} of container {} disallows canTakeStack", ()->slot.slotNumber, ()-> containerTypeName);
                continue;
            }
            slot.putStack(ItemStack.EMPTY);
            ItemStack target = ItemStack.EMPTY;
            if (itemCount > 0 && stackHolder != null)
            {
                target = stackHolder.getElement().is.copy();
                target.setCount(Math.min(itemCount, target.getMaxStackSize()));
            }
            // The item isn't valid for this slot
            if (!target.isEmpty() && !slot.isItemValid(target)) {
                final ItemStack trg = target;
                InventorySorter.LOGGER.log(Level.DEBUG, "Item {} is not valid in slot {} of container {}", ()->trg, ()->slot.slotNumber, ()-> containerTypeName);
                continue;
            }
            slot.putStack(target.isEmpty() ? ItemStack.EMPTY : target);
            itemCount -= !target.isEmpty() ? target.getCount() : 0;
            if (itemCount == 0)
            {
                stackHolder = itemsIterator.hasNext() ? itemsIterator.next() : null;
                itemCount = stackHolder != null ? stackHolder.getCount() : 0;
            }
        }
    }

    private static final ResourceLocation DUMMY_PLAYER_CONTAINER = new ResourceLocation("inventorysorter:dummyplayercontainer");
    private ResourceLocation lookupContainerTypeName(Container container) {
        return container instanceof PlayerContainer ? DUMMY_PLAYER_CONTAINER : container.getType().getRegistryName();
    }
}
