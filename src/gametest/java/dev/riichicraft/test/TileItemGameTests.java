package dev.riichicraft.test;

import com.mojang.serialization.JsonOps;
import dev.riichicraft.game.TileCatalog;
import dev.riichicraft.item.ModItems;
import dev.riichicraft.item.TileAppearance;
import io.netty.buffer.Unpooled;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

public final class TileItemGameTests {
    @GameTest
    public void allPhysicalTilesMapToCorrectFacesWithoutExposingCopyIdentity(GameTestHelper helper) {
        Set<String> keys = new HashSet<>();
        for (int id = 0; id < 136; id++) {
            int type = id / 4;
            String expected = type >= 27 ? (type - 26) + "z"
                    : (id == 16 || id == 52 || id == 88 ? "0" : Integer.toString(type % 9 + 1))
                            + "mps".charAt(type / 9);
            helper.assertTrue(TileAppearance.key(id).equals(expected), "Physical copy maps to the correct visible face: " + id);
            keys.add(expected);
            ItemStack stack = ModItems.tileStack(id);
            helper.assertTrue(stack.is(ModItems.MAHJONG_TILE) && stack.getCount() == 1
                    && stack.get(DataComponents.CUSTOM_DATA) == null, "Display item contains no physical game identity");
            if (!TileCatalog.isRed(id) && id % 4 > 1) {
                helper.assertTrue(ItemStack.isSameItemSameComponents(stack, ModItems.tileStack(id - 1)),
                        "Ordinary copies are indistinguishable inventory items");
            }
        }
        helper.assertTrue(keys.size() == 37 && TileAppearance.samples().size() == 37
                && TileAppearance.samples().stream().map(TileAppearance::key).collect(java.util.stream.Collectors.toSet()).equals(keys),
                "Creative samples cover all and only the 37 distinct appearances");
        helper.assertTrue(ItemStack.isSameItemSameComponents(new ItemStack(ModItems.MAHJONG_TILE), ModItems.tileStack(0)),
                "The default /give item stacks with the matching creative one-man appearance");
        for (int invalid : new int[] {-1, 136, Integer.MAX_VALUE}) {
            boolean rejected = false;
            try { ModItems.tileStack(invalid); } catch (IllegalArgumentException exception) { rejected = true; }
            helper.assertTrue(rejected, "Invalid physical identity must be rejected before appearance creation");
        }
        helper.succeed();
    }

    @GameTest
    public void everyAppearanceSurvivesNativeStackSaveAndNetworkRoundTrips(GameTestHelper helper) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, helper.getLevel().registryAccess());
        for (int id : TileAppearance.samples()) {
            ItemStack original = ModItems.tileStack(id);
            original.setCount(7);
            var encoded = ItemStack.CODEC.encodeStart(ops, original).getOrThrow();
            ItemStack restored = ItemStack.CODEC.parse(ops, encoded).getOrThrow();
            helper.assertTrue(ItemStack.matches(original, restored), "Saved stack retains face, localized name and count: "
                    + TileAppearance.key(id) + " original=" + original.getComponents() + " restored=" + restored.getComponents());
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
            try {
                ItemStack.STREAM_CODEC.encode(buffer, original);
                ItemStack received = ItemStack.STREAM_CODEC.decode(buffer);
                helper.assertTrue(ItemStack.matches(original, received) && buffer.readableBytes() == 0,
                        "Native item stack packet retains every appearance without trailing data");
            } finally { buffer.release(); }
        }
        helper.succeed();
    }
}
