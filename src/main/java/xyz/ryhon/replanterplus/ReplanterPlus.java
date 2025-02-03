package xyz.ryhon.replanterplus;

import java.util.Optional;

import org.lwjgl.glfw.GLFW;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public class ReplanterPlus implements ModInitializer {
	private final MinecraftClient mc;
	private boolean useIgnore = false;

	public static final Config CONFIG = new Config();

	private int ticks = 0;
	private int autoSaveTicks = 20 * 60 * 3;

	public ReplanterPlus(){
		this.mc = MinecraftClient.getInstance();
	}

	@Override
	public void onInitialize() {
		CONFIG.load();
		registerBinds();
		registerEventCallbacks();
	}

	private void registerBinds(){
		String bindCategory = "category.replanter";
		KeyBinding menuBind = new KeyBinding("key.replanter.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
				bindCategory);
		KeyBindingHelper.registerKeyBinding(menuBind);
		KeyBinding toggleBind = new KeyBinding("key.replanter.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN,
				bindCategory);
		KeyBindingHelper.registerKeyBinding(toggleBind);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ticks++;
			if (ticks == autoSaveTicks) {
				ticks = 0;
				CONFIG.save();
			}

			if (menuBind.wasPressed())
				client.setScreen(new ConfigScreen(null));

			if (toggleBind.wasPressed())
				CONFIG.toggleEnabled();
		});
	}

	private void registerEventCallbacks(){
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (player instanceof ServerPlayerEntity || useIgnore)
				return ActionResult.PASS;

			if (!CONFIG.isEnabled() || (CONFIG.isSneakToggle() && player.isSneaking()))
				return ActionResult.PASS;

			ClientPlayerEntity p = (ClientPlayerEntity) player;
			BlockState state = world.getBlockState(hitResult.getBlockPos());

			if (state.getBlock() instanceof CocoaBlock cocoaBlock) {
				return handleCocoaBlock(p, world, state, hitResult, cocoaBlock);
			} else if (isCrop(state)) {
				return handleCrop(p, state, hitResult);
			}

			return ActionResult.PASS;
		});
	}

	private ActionResult handleCocoaBlock(ClientPlayerEntity player, World world, BlockState state, BlockHitResult hitResult, CocoaBlock cocoaBlock) {
		if (!cocoaBlock.isFertilizable(world, hitResult.getBlockPos(), state)) {
			breakAndReplantCocoa(player, state, hitResult);
			return ActionResult.SUCCESS;
		}
	
		return useBoneMeal(player, hitResult);
	}

	private ActionResult handleCrop(ClientPlayerEntity player, BlockState state, BlockHitResult hitResult) {
		if (isGrown(state)) {
			breakAndReplant(player, hitResult);
			return ActionResult.SUCCESS;
		}
	
		return useBoneMeal(player, hitResult);
	}

	private ActionResult useBoneMeal(ClientPlayerEntity player, BlockHitResult hitResult) {
		Hand hand = findAndEquipSeed(player, Items.BONE_MEAL);
		if (hand != null) {
			useIgnore = true;
			mc.interactionManager.interactBlock(player, hand, hitResult);
			useIgnore = false;
			return ActionResult.SUCCESS;
		}

		return ActionResult.PASS;
	}

	boolean findInstamineTool(ClientPlayerEntity p, BlockState state, BlockPos pos) {
		if (state.calcBlockBreakingDelta(p, p.getWorld(), pos) >= 1f)
			return true;

		if (!CONFIG.isAutoSwitch())
			return false;

		int currentSlot = p.getInventory().selectedSlot;
		for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
			p.getInventory().selectedSlot = i;
			if (state.calcBlockBreakingDelta(p, p.getWorld(), pos) >= 1f) {
				mc.interactionManager.syncSelectedSlot();
				return true;
			}
		}
		p.getInventory().selectedSlot = currentSlot;

		return false;
	}

	boolean isCrop(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof CropBlock)
			return true;
		else if (block instanceof NetherWartBlock)
			return true;
		else if (block instanceof PitcherCropBlock)
			return PitcherCropBlock.isLowerHalf(state);
		else if (block == Blocks.TORCHFLOWER || block == Blocks.TORCHFLOWER_CROP)
			return true;

		return false;
	}

	boolean isGrown(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof CropBlock crop)
			return crop.isMature(state);
		else if (block instanceof NetherWartBlock)
			return state.get(NetherWartBlock.AGE) == 3;
		else if (block instanceof PitcherCropBlock pcb)
			// Interacting with upper half will reject the use packet
			// because it's too far away
			return pcb.isFullyGrown(state) && PitcherCropBlock.isLowerHalf(state);

		return block == Blocks.TORCHFLOWER;
	}

	void breakAndReplant(ClientPlayerEntity player, BlockHitResult hit) {
		Item seed = getSeed(player.getWorld().getBlockState(hit.getBlockPos()).getBlock());
		Hand h = findAndEquipSeed(player, seed);
		if (CONFIG.isRequireSeedHeld() && h == null) {
			sendMissingItemMessage(player, seed);
			return;
		}

		holdFortuneItem(player);
		mc.interactionManager.attackBlock(hit.getBlockPos(), hit.getSide());

		if (h != null) {
			useIgnore = true;
			mc.interactionManager.interactBlock(player, h, hit.withBlockPos(
					hit.getBlockPos()));
			useIgnore = false;
		} else
			sendMissingItemMessage(player, seed);
		mc.itemUseCooldown = CONFIG.getUseDelay();
	}

	void breakAndReplantCocoa(ClientPlayerEntity p, BlockState state, BlockHitResult hitResult) {
		if (findInstamineTool(p, state, hitResult.getBlockPos())) {
			Item seed = state.getBlock().asItem();
			Hand h = findAndEquipSeed(p, seed);

			if (CONFIG.isRequireSeedHeld() && h == null) {
				sendMissingItemMessage(p, seed);
				return;
			}

			mc.interactionManager.attackBlock(hitResult.getBlockPos(), hitResult.getSide());
			if (h != null) {
				Direction dir = state.get(HorizontalFacingBlock.FACING);

				float x = dir.getOffsetX();
				float y = dir.getOffsetY();
				float z = dir.getOffsetZ();
				BlockHitResult placeHit = BlockHitResult.createMissed(
						hitResult.getPos().add(x, y, z), dir.getOpposite(),
						hitResult.getBlockPos().add(dir.getVector()));

				useIgnore = true;
				mc.interactionManager.interactBlock(p, h, placeHit);
				useIgnore = false;
			} else
				sendMissingItemMessage(p, seed);
			mc.itemUseCooldown = CONFIG.getUseDelay();
		}
	}

	Item getSeed(Block block) {
		if (block instanceof CropBlock cb) {
			return cb.asItem();
		} else if (block instanceof NetherWartBlock) {
			return Items.NETHER_WART;
		} else if (block instanceof PitcherCropBlock) {
			return Items.PITCHER_POD;
		} else if (block == Blocks.TORCHFLOWER) {
			return Items.TORCHFLOWER_SEEDS;
		}

		return null;
	}

	Hand findAndEquipSeed(PlayerEntity p, Item item) {
		if (item == null)
			return null;

		PlayerInventory pi = p.getInventory();
		if (pi.getStack(pi.selectedSlot).isOf(item))
			return Hand.MAIN_HAND;
		if (pi.getStack(PlayerInventory.OFF_HAND_SLOT).isOf(item))
			return Hand.OFF_HAND;

		if (!CONFIG.isAutoSwitch())
			return null;

		for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
			if (pi.getStack(i).isOf(item)) {
				pi.selectedSlot = i;
				mc.interactionManager.syncSelectedSlot();
				mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
						PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
				return Hand.OFF_HAND;
			}
		}
		return null;
	}

	void holdFortuneItem(PlayerEntity p) {
		int maxLevel = 0;
		int slot = -1;

		PlayerInventory pi = p.getInventory();
		Optional<Registry<Enchantment>> enchantRegistryOptional = p.getWorld().getRegistryManager().getOptional(RegistryKeys.ENCHANTMENT);
		if(enchantRegistryOptional.isEmpty()){
			return;
		}

		Registry<Enchantment> enchantRegistry = enchantRegistryOptional.get();
		Optional<RegistryEntry.Reference<Enchantment>> fortune = enchantRegistry.getEntry(Enchantments.FORTUNE.getValue());
		if (!fortune.isPresent())
			return;

		for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
			int lvl = EnchantmentHelper.getLevel(fortune.get(), pi.getStack(i));
			if (lvl > maxLevel) {
				maxLevel = lvl;
				slot = i;
			}
		}

		if (slot != -1) {
			pi.selectedSlot = slot;
			mc.interactionManager.syncSelectedSlot();
		}
	}

	void sendMissingItemMessage(PlayerEntity player, Item seed) {
		if (CONFIG.isMissingItemNotifications()) {
			MutableText itemName = seed.getDefaultStack().getName().copy();
			MutableText message = itemName
				.append(Text.translatable(CONFIG.isAutoSwitch() ? "replanter.gui.seed_not_in_hotbar" : "replanter.gui.seed_not_in_hand"))
				.setStyle(Style.EMPTY.withColor(0xFF0000));
			player.sendMessage(message, true);
		}
	}
}