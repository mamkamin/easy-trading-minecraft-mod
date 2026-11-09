package me.min.easytrading.client;

import com.google.common.collect.Lists;
import com.mojang.blaze3d.platform.InputConstants;
import me.min.easytrading.EasyTrading;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.RandomSequence;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.EntityHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class EasyTradingClient implements ClientModInitializer {

	private static final int MERCHANT_WIDTH = 276;
	private static final int MERCHANT_HEIGHT = 166;

	record Choice(ResourceKey<Enchantment> enchantment, int level, Component label, int minPrice) {}

	static final List<Choice> CHOICES = new ArrayList<>();
	static int selected = 0;
	static int maxPrice = -1;   // -1 = "not set yet", filled in on first open

	private static int minPriceFor(Holder<Enchantment> holder, int level) {
		int price = 2 + 3 * level;
		if (holder.is(EnchantmentTags.DOUBLE_TRADE_PRICE)) price *= 2;
		return Math.min(price, 64);
	}

	private static void buildChoices(Minecraft client) {
		CHOICES.clear();
		var enchantments = client.level.registryAccess()
				.lookupOrThrow(Registries.ENCHANTMENT)
				.getTagOrEmpty(EnchantmentTags.TRADEABLE);

		for (Holder<Enchantment> holder : enchantments) {
			for (int level = holder.value().getMinLevel(); level <= holder.value().getMaxLevel(); level++) {
				CHOICES.add(new Choice(
						holder.unwrapKey().orElseThrow(),
						level,
						Enchantment.getFullname(holder, level),
						minPriceFor(holder, level)));
			}
		}
		if (selected >= CHOICES.size()) selected = 0;
	}

	private static void playRerollSound(ServerLevel level, Villager villager) {
		SoundEvent sound = villager.getVillagerData().profession().value().workSound();
		if (sound == null) sound = SoundEvents.VILLAGER_YES;
		level.playSound(null, villager.blockPosition(), sound, SoundSource.NEUTRAL, 1.0f, 1.0f);
	}

	public static final String MOD_ID = "easy-trading-client";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final Identifier TARGET_DICE_ROLLER = Identifier.withDefaultNamespace("trade_set/librarian/level_1");
	public static final ResourceKey<TradeSet> LIBRARIAN_TRADE_SET = ResourceKey.create(
			Registries.TRADE_SET,
			Identifier.withDefaultNamespace("librarian/level_1"));
	private static final int MAX_REROLLS = 10000;

	private static Screen librarianScreen = null;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category CATEGORY = KeyMapping.Category.register(EasyTrading.id("reroll_villager_trade"));
		KeyMapping rerollVillagerTrade = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.easy_trading.reroll_villager_trade",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_R,
				CATEGORY
		));

		ScreenEvents.BEFORE_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof MerchantScreen)) {
				return;
			}


			ScreenKeyboardEvents.afterKeyPress(screen).register((activeScreen, event) -> {
				if (!rerollVillagerTrade.matches(event)) return;
				client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
				LOGGER.info("EVENT: R is pressed!");
				if (client.player != null) {

					MinecraftServer server = client.getSingleplayerServer();
					if (server == null) { LOGGER.info("No server"); return; }

					UUID playerId = client.player.getUUID();
					Choice choice = CHOICES.isEmpty() ? null : CHOICES.get(selected);
					int priceLimit = maxPrice;
					server.execute(() -> {
						try {
							LOGGER.info("Server task started");
							ServerPlayer serverPlayer = server.getPlayerList().getPlayer(playerId);
							if (serverPlayer == null) {
								LOGGER.info("No server player");
								return;
							}
							ServerLevel level = (ServerLevel) serverPlayer.level();

							List<Villager> nearby = level.getEntitiesOfClass(
									Villager.class,
									serverPlayer.getBoundingBox().inflate(5) // a box 5 blocks around you
							);
							if (nearby.isEmpty()) {
								LOGGER.info("No villager nearby");
								return;
							}
							Villager villager = nearby.stream()
									.filter(v -> v.getTradingPlayer() == serverPlayer)
									.findFirst()
									.orElse(null);

							if (villager == null) {
								LOGGER.info("No villager trading with you");
								return;
							}

							if (villager.getVillagerData().level() != 1) {
								LOGGER.info("Only Novice villagers can be rerolled");
								return;
							}

							if (villager.getVillagerXp() > 0) {
								LOGGER.info("Already traded with, trades are locked");
								level.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0f, 1.0f);
								return;
							}

							boolean isLibrarian = villager.getVillagerData().profession().is(VillagerProfession.LIBRARIAN);

							if (!isLibrarian) {
								villager.setOffers(null);
								villager.getOffers();   // one normal reroll, real dice
								serverPlayer.sendMerchantOffers(
										serverPlayer.containerMenu.containerId,
										villager.getOffers(),
										villager.getVillagerData().level(),
										villager.getVillagerXp(),
										villager.showProgressBar(),
										villager.canRestock());
								EasyTradingClient.playRerollSound(level, villager);
								return;
							}

							if (choice == null) return;

							AtomicReference<RandomSequence> found = new AtomicReference<>();
							server.getRandomSequences().forAllSequences((identifier, randomSequence) -> {
								if (identifier.equals(TARGET_DICE_ROLLER)) found.set(randomSequence);
							});

							RandomSequence original = found.get();
							if (original == null) {
								LOGGER.info("No librarian sequence yet");
								return;
							}

							Tag saved = RandomSequence.CODEC.encodeStart(NbtOps.INSTANCE, original).getOrThrow();
							RandomSequence copy = RandomSequence.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();

							RandomSource dice = copy.random();

							Optional<TradeSet> tradeSetOpt = server.registryAccess().lookupOrThrow(Registries.TRADE_SET).getOptional(LIBRARIAN_TRADE_SET);
							if (tradeSetOpt.isEmpty()) {
								LOGGER.info("Missing expected trade set {}", LIBRARIAN_TRADE_SET);
							} else {
								TradeSet tradeSet = tradeSetOpt.get();
								LootParams params = new LootParams.Builder(level)
										.withParameter(LootContextParams.ORIGIN, villager.position())
										.withParameter(LootContextParams.THIS_ENTITY, villager)
										.withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE)
										.create(LootContextParamSets.VILLAGER_TRADE);

								LootContext lootContext = new LootContext.Builder(params)
										.withOptionalRandomSource(dice)
										.create(tradeSet.randomSequence());

								int foundAt = -1;
								for (int n = 1; n <= MAX_REROLLS; ++n) {
									int numberOfTrades = tradeSet.calculateNumberOfTrades(lootContext);

									List<Holder<VillagerTrade>> leftoverOffers = Lists.newArrayList(tradeSet.trades());
									int offersFound = 0;
									boolean foundThisRound = false;
									while (offersFound < numberOfTrades && !leftoverOffers.isEmpty()) {
										Holder<VillagerTrade> villagerTrade = leftoverOffers.remove(lootContext.getRandom().nextInt(leftoverOffers.size()));
										MerchantOffer offer = villagerTrade.value().getOffer(lootContext);
										if (offer != null) {
											ItemEnchantments enchantments =  offer.getResult().get(DataComponents.STORED_ENCHANTMENTS);
											if (enchantments != null) {
												for (var entry : enchantments.entrySet()) {
													Holder<Enchantment> enchantment = entry.getKey();
													int enchantLevel = entry.getIntValue();
													if (enchantment.is(choice.enchantment()) && enchantLevel == choice.level && offer.getCostA().getCount() <= priceLimit) {
														foundThisRound = true;
													}
												}
											}

											++offersFound;
										}
									}
									if (foundThisRound) {
										foundAt = n;
										break;
									}
								}

								if (foundAt == -1) {
									LOGGER.info("Target not found within {} rerolls", MAX_REROLLS);
								} else {
									LOGGER.info("Target found after {} rerolls", foundAt);
									for (int i = 0; i < foundAt; ++i) {
										villager.setOffers(null);
										villager.getOffers();
									}
									serverPlayer.sendMerchantOffers(
											serverPlayer.containerMenu.containerId,
											villager.getOffers(),
											villager.getVillagerData().level(),
											villager.getVillagerXp(),
											villager.showProgressBar(),
											villager.canRestock());
									EasyTradingClient.playRerollSound(level, villager);
								}

							}
						} catch (Throwable t) {
							LOGGER.error("Prediction failed", t);
						}
					});
				}
			});

		});

		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (!(screen instanceof MerchantScreen merchantScreen)) return;
			if (client.level == null) return;

			// Only librarians get the UI
			boolean isLibrarian;
			if (screen == librarianScreen) {
				isLibrarian = true;   // already decided for this screen (e.g. after a resize)
			} else {
				isLibrarian = client.hitResult instanceof EntityHitResult hit
						&& hit.getEntity() instanceof Villager v
						&& v.getVillagerData().profession().is(VillagerProfession.LIBRARIAN);
			}
			if (!isLibrarian) return;
			librarianScreen = screen;

			buildChoices(client);
			if (CHOICES.isEmpty()) return;
			if (maxPrice == -1) maxPrice = CHOICES.get(selected).minPrice();

			// Layout: < [label] >   - [price] +
			int left = (scaledWidth - MERCHANT_WIDTH) / 2;
			int top = (scaledHeight - MERCHANT_HEIGHT) / 2;
			int h = 12, gap = 2, groupGap = 8;
			int labelW = 150, priceW = 24;
			int rowW = h + gap + labelW + gap + h + groupGap + h + gap + priceW + gap + h;

			int x = left + (MERCHANT_WIDTH - rowW) / 2;   // centred above the window
			int y = top - h - 4;                          // 4px above the window
			int priceX = x + h + gap + labelW + gap + h + groupGap;

			// Labels (display only)
			Button enchantLabel = Button.builder(CHOICES.get(selected).label(), b -> {})
					.bounds(x + h + gap, y, labelW, h).build();
			enchantLabel.active = false;

			Button priceLabel = Button.builder(Component.literal(String.valueOf(maxPrice)), b -> {})
					.bounds(priceX + h + gap, y, priceW, h).build();
			priceLabel.active = false;

			Runnable refresh = () -> {
				enchantLabel.setMessage(CHOICES.get(selected).label());
				priceLabel.setMessage(Component.literal(String.valueOf(maxPrice)));
			};

			// Enchantment arrows: changing the book resets the price to its minimum
			Button prev = Button.builder(Component.literal("<"), b -> {
						selected = (selected - 1 + CHOICES.size()) % CHOICES.size();
						maxPrice = CHOICES.get(selected).minPrice();
						refresh.run();
					})
					.bounds(x, y, h, h).build();

			Button next = Button.builder(Component.literal(">"), b -> {
						selected = (selected + 1) % CHOICES.size();
						maxPrice = CHOICES.get(selected).minPrice();
						refresh.run();
					})
					.bounds(x + h + gap + labelW + gap, y, h, h).build();

			// Price buttons: Shift = steps of 5
			Tooltip priceTip = Tooltip.create(Component.literal("Max price in emeralds (Shift: ±5)"));

			Button minus = Button.builder(Component.literal("-"), b -> {
						int step = client.hasShiftDown() ? 5 : 1;
						maxPrice = Math.max(CHOICES.get(selected).minPrice(), maxPrice - step);
						refresh.run();
					})
					.bounds(priceX, y, h, h).tooltip(priceTip).build();

			Button plus = Button.builder(Component.literal("+"), b -> {
						int step = client.hasShiftDown() ? 5 : 1;
						maxPrice = Math.min(64, maxPrice + step);
						refresh.run();
					})
					.bounds(priceX + h + gap + priceW + gap, y, h, h).tooltip(priceTip).build();

			var widgets = Screens.getWidgets(screen);
			widgets.add(prev);
			widgets.add(enchantLabel);
			widgets.add(next);
			widgets.add(minus);
			widgets.add(priceLabel);
			widgets.add(plus);
		});
	}
}