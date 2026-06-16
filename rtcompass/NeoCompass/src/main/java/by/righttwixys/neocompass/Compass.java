package by.righttwixys.neocompass;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;

@Mod(Compass.MODID)
public class Compass {
    public static final String MODID = "neocompass";

    private static float currentHeading = 0f;
    private static long lastTime = System.currentTimeMillis();
    private static float currentAlpha = 0f;

    private static BlockPos clientBedPos = null;
    private static String clientBedDimension = "";

    // В NeoForge контейнер мода и шина событий автоматически инжектируются в конструктор
    public Compass(ModContainer modContainer, IEventBus modEventBus) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);

        // Обновленная регистрация экрана конфигурации для NeoForge
        modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                (container, prevScreen) -> new CompassConfigScreen(prevScreen)
        );
    }

    public enum CompassPosition { TOP, ABOVE_HOTBAR }
    public enum SmoothingMode { LINEAR, EXPONENTIAL }

    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
    public static class ClientEvents {

        @SubscribeEvent
        public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
            if (event.getLevel().isClientSide() && event.getEntity() == Minecraft.getInstance().player) {
                net.minecraft.world.level.block.state.BlockState state = event.getLevel().getBlockState(event.getPos());
                if (state.getBlock() instanceof net.minecraft.world.level.block.BedBlock) {
                    clientBedPos = event.getPos();
                    clientBedDimension = event.getLevel().dimension().location().toString();
                }
            }
        }

        @SubscribeEvent
        public static void onRenderGui(RenderGuiLayerEvent.Post event) {
            // В NeoForge используется event.getName() и VanillaGuiLayers вместо устаревшего VanillaGuiOverlay
            if (!event.getName().equals(VanillaGuiLayers.CROSSHAIR)) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null || mc.options.hideGui) return;

            long now = System.currentTimeMillis();
            long elapsed = now - lastTime;
            lastTime = now;
            if (elapsed > 100) elapsed = 16;

            boolean shouldBeVisible = Config.enabled.get();
            if (shouldBeVisible && Config.requireCompassInInventory.get()) {
                boolean hasCompass = false;
                for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
                    if (mc.player.getInventory().getItem(i).is(Items.COMPASS)) {
                        hasCompass = true;
                        break;
                    }
                }
                if (!hasCompass) shouldBeVisible = false;
            }

            float animationSpeed = elapsed / (float) Math.max(1, Config.animationDuration.get());
            if (shouldBeVisible) {
                currentAlpha += animationSpeed;
                if (currentAlpha > 1f) currentAlpha = 1f;
            } else {
                currentAlpha -= animationSpeed;
                if (currentAlpha < 0f) currentAlpha = 0f;
            }

            if (currentAlpha <= 0f) return;

            GuiGraphics graphics = event.getGuiGraphics();
            Font font = mc.font;

            float targetHeading = (mc.player.getYRot() + 180f) % 360f;
            if (targetHeading < 0) targetHeading += 360f;

            float delta = targetHeading - currentHeading;
            while (delta < -180f) delta += 360f;
            while (delta > 180f) delta -= 360f;

            if (Config.smoothingEnabled.get()) {
                if (Config.smoothingMode.get() == SmoothingMode.EXPONENTIAL) {
                    float k = 1.0f - (float) Math.exp(-elapsed / Math.max(1.0f, Config.smoothingSpeed.get()));
                    currentHeading += delta * k;
                } else {
                    float step = (180f / Math.max(1.0f, Config.smoothingSpeed.get())) * elapsed;
                    if (Math.abs(delta) <= step) currentHeading = targetHeading;
                    else currentHeading += Math.signum(delta) * step;
                }
            } else {
                currentHeading = targetHeading;
            }
            currentHeading = (currentHeading % 360f + 360f) % 360f;

            int width = Config.width.get();
            int height = 40;

            int centerX = (mc.getWindow().getGuiScaledWidth() / 2) + Config.xOffset.get();
            int centerY = Config.position.get() == CompassPosition.TOP ?
                    Config.yOffset.get() : mc.getWindow().getGuiScaledHeight() - 65 + Config.yOffset.get();

            graphics.pose().pushPose();
            float scale = Config.scale.get().floatValue();
            if (scale != 1.0f) {
                graphics.pose().scale(scale, scale, 1.0f);
                centerX = (int) (centerX / scale);
                centerY = (int) (centerY / scale);
                width = (int) (width / scale);
            }

            int mainColor = parseColor(Config.mainColor.get(), 0xFFFFFFFF, currentAlpha);
            int interColor = Config.separateColors.get() ? parseColor(Config.interColor.get(), 0xFFFFAA00, currentAlpha) : mainColor;
            int numColor = Config.separateColors.get() ? parseColor(Config.numberColor.get(), 0xFFAAAAAA, currentAlpha) : mainColor;
            int indColor = parseColor(Config.indicatorColor.get(), 0xFFFFFFFF, currentAlpha);
            int lineColor = (int) (0x55 * currentAlpha) << 24 | 0x00FFFFFF;

            double guiScale = mc.getWindow().getGuiScale();
            int scissorX = (int) ((centerX - width / 2) * scale * guiScale);
            int scissorY = (int) (mc.getWindow().getHeight() - (centerY + height / 2) * scale * guiScale);
            int scissorW = (int) (width * scale * guiScale);
            int scissorH = (int) (height * scale * guiScale);

            if (scissorW > 0 && scissorH > 0) {
                RenderSystem.enableScissor(scissorX, scissorY, scissorW, scissorH);
            }

            graphics.fill(centerX - width / 2, centerY, centerX + width / 2, centerY + 1, lineColor);

            int range = 60;
            for (int angle = 0; angle < 360; angle += 15) {
                float diff = angle - currentHeading;
                while (diff < -180f) diff += 360f;
                while (diff > 180f) diff -= 360f;

                if (Math.abs(diff) <= range) {
                    int markerX = centerX + (int) ((diff / range) * (width / 2));
                    boolean isMain = angle % 90 == 0;
                    boolean isInter = angle % 45 == 0 && !isMain && Config.showOrdinalDirections.get();

                    int currentColor = isMain ? mainColor : (isInter ? interColor : numColor);

                    if (isMain || isInter) {
                        String label = getMarkerLabel(angle);
                        int yPos = Config.customElementOffsets.get() ? centerY + Config.textYOffset.get() : centerY - 14;
                        graphics.drawCenteredString(font, label, markerX, yPos, currentColor);
                    } else {
                        int yPos = Config.customElementOffsets.get() ? centerY + Config.tickYOffset.get() : centerY - 12;
                        graphics.drawCenteredString(font, "|", markerX, yPos, currentColor);
                    }

                    if (Config.showScaleDegrees.get()) {
                        int yPos = Config.customElementOffsets.get() ? centerY + Config.degreeYOffset.get() : centerY + 4;
                        graphics.drawCenteredString(font, String.valueOf(angle), markerX, yPos, currentColor);
                    }
                }
            }
            RenderSystem.disableScissor();
            // === РЕНДЕР МЕTOK ===

            // 1. Метка основного мирового спавна (Spawn)
            if (Config.showWorldSpawn.get()) {
                BlockPos spawnPos = mc.level.getSharedSpawnPos();
                renderMarker(graphics, font, mc, spawnPos, currentHeading, range, centerX, centerY, width,
                        Config.spawnColor.get(), Config.spawnSymbol.get(), "Spawn",
                        Config.showSpawnName.get(), Config.spawnTextScale.get().floatValue(), Config.spawnTextYOffset.get());
            }

            // 2. Метка спавна у кровати (Home)
            if (Config.showBedSpawn.get()) {
                if (clientBedPos != null && mc.level.dimension().location().toString().equals(clientBedDimension)) {
                    renderMarker(graphics, font, mc, clientBedPos, currentHeading, range, centerX, centerY, width,
                            Config.bedColor.get(), Config.bedSymbol.get(), "Home",
                            Config.showBedName.get(), Config.bedTextScale.get().floatValue(), Config.bedTextYOffset.get());
                }
            }

            // 3. Метка привязанных магнетитов (Lodestones)
            if (Config.showLodestones.get()) {
                for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
                    ItemStack stack = mc.player.getInventory().getItem(i);
                    if (stack.is(Items.COMPASS)) {
                        // Майнкрафт 1.20.5+ полностью избавился от классического NBT (getTag/hasTag) в пользу компонентов (Data Components)
                        LodestoneTracker tracker = stack.get(DataComponents.LODESTONE_TRACKER);
                        if (tracker != null && tracker.target().isPresent()) {
                            GlobalPos globalPos = tracker.target().get();
                            BlockPos lodestonePos = globalPos.pos();

                            boolean isCorrectDimension = globalPos.dimension().location().toString().equals(mc.level.dimension().location().toString());

                            if (isCorrectDimension) {
                                String displayName = "";
                                boolean hasCustomName = stack.has(DataComponents.CUSTOM_NAME);
                                if (hasCustomName) {
                                    displayName = stack.getHoverName().getString();
                                    if (Config.useBracketParsing.get()) {
                                        displayName = parseBracketName(displayName);
                                    }
                                }
                                renderMarker(graphics, font, mc, lodestonePos, currentHeading, range, centerX, centerY, width,
                                        Config.lodestoneColor.get(), Config.lodestoneSymbol.get(), displayName,
                                        Config.showLodestoneNames.get() && hasCustomName, Config.lodestoneTextScale.get().floatValue(), Config.lodestoneTextYOffset.get());
                            }
                        }
                    }
                }
            }

            if (scissorW > 0 && scissorH > 0) {
                RenderSystem.disableScissor();
            }

            int arrowY = Config.customElementOffsets.get() ? centerY + Config.arrowYOffset.get() : centerY - 4;
            graphics.drawCenteredString(font, "▲", centerX, arrowY, indColor);

            if (Config.showDegreeIndicator.get()) {
                int currentIntHeading = Math.round(currentHeading);
                String centerText = String.valueOf(currentIntHeading == 360 ? 0 : currentIntHeading);

                int textW = font.width(centerText);
                int boxY = Config.customElementOffsets.get() ? centerY + Config.boxYOffset.get() : centerY + 14;
                int boxBgColor = (int) (0xAA * currentAlpha) << 24 | 0x00000000;
                graphics.fill(centerX - textW / 2 - 4, boxY, centerX + textW / 2 + 4, boxY + 11, boxBgColor);
                graphics.drawCenteredString(font, centerText, centerX, boxY + 1, indColor);
            }

            graphics.pose().popPose();
        }

        private static void renderMarker(GuiGraphics graphics, Font font, Minecraft mc, BlockPos targetPos,
                                         float currentHeading, int range, int centerX, int centerY, int width,
                                         String hexColor, String symbol, String label, boolean showName,
                                         float textScale, int labelYOffset) {
            double dx = targetPos.getX() + 0.5 - mc.player.getX();
            double dz = targetPos.getZ() + 0.5 - mc.player.getZ();

            float targetAngle = (float) Math.toDegrees(Math.atan2(dx, -dz));
            targetAngle = (targetAngle % 360f + 360f) % 360f;

            float diff = targetAngle - currentHeading;
            while (diff < -180f) diff += 360f;
            while (diff > 180f) diff -= 360f;

            if (Math.abs(diff) <= range) {
                int markerX = centerX + (int) ((diff / range) * (width / 2));
                int colorWithAlpha = parseColor(hexColor, 0xFFFFFFFF, currentAlpha);

                if (symbol != null && !symbol.isEmpty()) {
                    graphics.drawCenteredString(font, symbol, markerX, centerY - 4, colorWithAlpha);
                } else {
                    graphics.fill(markerX - 1, centerY - 6, markerX + 1, centerY + 6, colorWithAlpha);
                }

                if (showName && label != null && !label.isEmpty()) {
                    graphics.pose().pushPose();

                    int baseTextY = Config.customElementOffsets.get() ? centerY + Config.textYOffset.get() - 8 : centerY - 24;
                    int finalTextY = baseTextY + labelYOffset;

                    graphics.pose().translate(markerX, finalTextY, 0);
                    graphics.pose().scale(textScale, textScale, 1.0f);

                    if (Config.useCurlyBracketBg.get() && label.contains("{") && label.contains("}")) {
                        int open = label.indexOf('{');
                        int close = label.indexOf('}');
                        if (close > open) {
                            String bgText = label.substring(open + 1, close);
                            String normalText = (label.substring(0, open) + label.substring(close + 1)).trim();

                            int bgTextWidth = font.width(bgText);
                            int normalTextWidth = font.width(normalText);

                            int boxBgColor = (int) (0xAA * currentAlpha) << 24 | 0x00000000;

                            if (!bgText.isEmpty() && normalText.isEmpty()) {
                                graphics.fill(-bgTextWidth / 2 - 3, -1, bgTextWidth / 2 + 3, 10, boxBgColor);
                                graphics.drawCenteredString(font, bgText, 0, 0, colorWithAlpha);
                            } else {
                                int totalW = bgTextWidth + 6 + (normalTextWidth > 0 ? normalTextWidth + 4 : 0);
                                int startX = -totalW / 2;

                                graphics.fill(startX, -1, startX + bgTextWidth + 6, 10, boxBgColor);
                                graphics.drawString(font, bgText, startX + 3, 0, colorWithAlpha, false);

                                if (!normalText.isEmpty()) {
                                    graphics.drawString(font, normalText, startX + bgTextWidth + 10, 0, colorWithAlpha, false);
                                }
                            }
                        } else {
                            graphics.drawCenteredString(font, label, 0, 0, colorWithAlpha);
                        }
                    } else {
                        graphics.drawCenteredString(font, label, 0, 0, colorWithAlpha);
                    }

                    graphics.pose().popPose();
                }
            }
        }

        private static String parseBracketName(String input) {
            if (input == null || input.isEmpty()) return "";
            int open = input.indexOf('[');
            int close = input.indexOf(']');
            if (open != -1 && close != -1 && close > open) {
                return input.substring(open + 1, close).trim();
            }
            return input;
        }

        private static String getMarkerLabel(int angle) {
            switch (angle) {
                case 0: return "N";
                case 45: return "NE";
                case 90: return "E";
                case 135: return "SE";
                case 180: return "S";
                case 225: return "SW";
                case 270: return "W";
                case 315: return "NW";
                default: return String.valueOf(angle);
            }
        }

        private static int parseColor(String hex, int fallback, float alpha) {
            try {
                int rawColor = Integer.parseInt(hex.replace("#", ""), 16);
                int r = (rawColor >> 16) & 0xFF;
                int g = (rawColor >> 8) & 0xFF;
                int b = rawColor & 0xFF;
                int a = (int) (255 * alpha) & 0xFF;
                return (a << 24) | (r << 16) | (g << 8) | b;
            } catch (Exception e) {
                int a = (int) (255 * alpha) & 0xFF;
                return (a << 24) | (fallback & 0x00FFFFFF);
            }
        }
    }

    public static class CompassConfigScreen extends Screen {
        private final Screen parent;

        private EditBox txtXOffset;
        private EditBox txtYOffset;
        private EditBox txtScale;
        private EditBox txtWidth;
        private EditBox txtSmoothSpeed;
        private EditBox txtAnimDuration;
        private EditBox txtMainColor;
        private EditBox txtInterColor;
        private EditBox txtNumberColor;
        private EditBox txtIndicatorColor;
        private EditBox txtTextYOffset;
        private EditBox txtTickYOffset;
        private EditBox txtDegreeYOffset;
        private EditBox txtArrowYOffset;
        private EditBox txtBoxYOffset;

        private EditBox txtSpawnColor, txtSpawnSymbol, txtSpawnScale, txtSpawnYOffset;
        private EditBox txtBedColor, txtBedSymbol, txtBedScale, txtBedYOffset;
        private EditBox txtLodeColor, txtLodeSymbol, txtLodeScale, txtLodeYOffset;

        private EditBox searchBox;
        private String activeCategory = "GENERAL";
        private double scrollAmount = 0;
        private int maxScroll = 0;

        private Button btnReset;
        private long resetTimer = 0;
        private boolean confirmReset = false;

        private final List<ConfigRow> totalRows = new ArrayList<>();

        public CompassConfigScreen(Screen parent) {
            super(Component.literal("Compass Configuration"));
            this.parent = parent;
        }

        private static class ConfigRow {
            final String category;
            final String searchKey;
            final Component label;
            final net.minecraft.client.gui.components.AbstractWidget widget;

            ConfigRow(String category, String searchKey, Component label, net.minecraft.client.gui.components.AbstractWidget widget) {
                this.category = category;
                this.searchKey = searchKey.toLowerCase();
                this.label = label;
                this.widget = widget;
            }
        }

        @Override
        protected void init() {
            this.totalRows.clear();

            this.searchBox = new EditBox(this.font, this.width / 2 - 100, 22, 200, 16, Component.literal("Search"));
            this.searchBox.setResponder(s -> this.scrollAmount = 0);
            this.addRenderableWidget(this.searchBox);

            int tabY = 42;
            int tabW = 60;
            int startTabX = this.width / 2 - ((tabW * 4 + 9) / 2);

            this.addRenderableWidget(Button.builder(Component.literal(activeCategory.equals("GENERAL") ? "[General]" : "General"), b -> activeCategory = "GENERAL")
                    .bounds(startTabX, tabY, tabW, 16).build());
            this.addRenderableWidget(Button.builder(Component.literal(activeCategory.equals("SMOOTHING") ? "[Smooth]" : "Smooth"), b -> activeCategory = "SMOOTHING")
                    .bounds(startTabX + tabW + 3, tabY, tabW, 16).build());
            this.addRenderableWidget(Button.builder(Component.literal(activeCategory.equals("DISPLAY") ? "[Display]" : "Display"), b -> activeCategory = "DISPLAY")
                    .bounds(startTabX + (tabW + 3) * 2, tabY, tabW, 16).build());
            this.addRenderableWidget(Button.builder(Component.literal(activeCategory.equals("COLORS") ? "[Colors]" : "Colors"), b -> activeCategory = "COLORS")
                    .bounds(startTabX + (tabW + 3) * 3, tabY, tabW, 16).build());

            // --- GENERAL ---
            Button btnEnabled = Button.builder(Component.literal(Config.enabled.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.enabled.get();
                Config.enabled.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("GENERAL", "enabled вкл включить", Component.literal("Enabled / Вкл:"), btnEnabled));
            this.addRenderableWidget(btnEnabled);

            Button btnPosition = Button.builder(Component.literal(Config.position.get() == CompassPosition.TOP ? "TOP" : "HOTB"), b -> {
                CompassPosition next = Config.position.get() == CompassPosition.TOP ? CompassPosition.ABOVE_HOTBAR : CompassPosition.TOP;
                Config.position.set(next);
                b.setMessage(Component.literal(next == CompassPosition.TOP ? "TOP" : "HOTB"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("GENERAL", "position поз позиция", Component.literal("Position / Поз:"), btnPosition));
            this.addRenderableWidget(btnPosition);

            this.txtYOffset = new EditBox(this.font, 0, 0, 120, 20, Component.literal("YOffset"));
            this.txtYOffset.setValue(String.valueOf(Config.yOffset.get()));
            totalRows.add(new ConfigRow("GENERAL", "y offset смещ y координата", Component.literal("Y Offset / Смещ Y:"), this.txtYOffset));
            this.addRenderableWidget(this.txtYOffset);

            this.txtScale = new EditBox(this.font, 0, 0, 120, 20, Component.literal("Scale"));
            this.txtScale.setValue(String.valueOf(Config.scale.get()));
            totalRows.add(new ConfigRow("GENERAL", "scale масштаб размер", Component.literal("Scale / Масштаб:"), this.txtScale));
            this.addRenderableWidget(this.txtScale);

            this.txtWidth = new EditBox(this.font, 0, 0, 120, 20, Component.literal("Width"));
            this.txtWidth.setValue(String.valueOf(Config.width.get()));
            totalRows.add(new ConfigRow("GENERAL", "width ширина размер шкалы", Component.literal("Width / Ширина:"), this.txtWidth));
            this.addRenderableWidget(this.txtWidth);

            Button btnNeedItem = Button.builder(Component.literal(Config.requireCompassInInventory.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.requireCompassInInventory.get();
                Config.requireCompassInInventory.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("GENERAL", "need item комп в инв инвентарь предмет компас", Component.literal("Need Item / Комп в Инв:"), btnNeedItem));
            this.addRenderableWidget(btnNeedItem);

            this.txtAnimDuration = new EditBox(this.font, 0, 0, 120, 20, Component.literal("AnimDuration"));
            this.txtAnimDuration.setValue(String.valueOf(Config.animationDuration.get()));
            totalRows.add(new ConfigRow("GENERAL", "anim time время аним анимация появление", Component.literal("Anim Time / Время Аним:"), this.txtAnimDuration));
            this.addRenderableWidget(this.txtAnimDuration);

            this.txtXOffset = new EditBox(this.font, 0, 0, 120, 20, Component.literal("XOffset"));
            this.txtXOffset.setValue(String.valueOf(Config.xOffset.get()));
            totalRows.add(new ConfigRow("GENERAL", "x offset смещ x координата", Component.literal("X Offset / Смещ X:"), this.txtXOffset));
            this.addRenderableWidget(this.txtXOffset);


            // --- SMOOTHING ---
            this.txtSmoothSpeed = new EditBox(this.font, 0, 0, 120, 20, Component.literal("Speed"));
            this.txtSmoothSpeed.setValue(String.valueOf(Config.smoothingSpeed.get()));
            totalRows.add(new ConfigRow("SMOOTHING", "speed скор скорость сглаживание ms", Component.literal("Speed / Скор (ms):"), this.txtSmoothSpeed));
            this.addRenderableWidget(this.txtSmoothSpeed);

            Button btnSmoothMode = Button.builder(Component.literal(Config.smoothingMode.get() == SmoothingMode.LINEAR ? "LIN" : "EXP"), b -> {
                SmoothingMode next = Config.smoothingMode.get() == SmoothingMode.LINEAR ? SmoothingMode.EXPONENTIAL : SmoothingMode.LINEAR;
                Config.smoothingMode.set(next);
                b.setMessage(Component.literal(next == SmoothingMode.LINEAR ? "LIN" : "EXP"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("SMOOTHING", "mode режим сглаживания линейный экспоненциальный", Component.literal("Mode / Режим:"), btnSmoothMode));
            this.addRenderableWidget(btnSmoothMode);

            Button btnSmoothEnabled = Button.builder(Component.literal(Config.smoothingEnabled.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.smoothingEnabled.get();
                Config.smoothingEnabled.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("SMOOTHING", "smooth сглаж сглаживание вкл выкл", Component.literal("Smooth / Сглаж:"), btnSmoothEnabled));
            this.addRenderableWidget(btnSmoothEnabled);


            // --- DISPLAY ---
            Button btnShowDegreeIndicator = Button.builder(Component.literal(Config.showDegreeIndicator.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.showDegreeIndicator.get();
                Config.showDegreeIndicator.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("DISPLAY", "ind box черный индик индикатор градус плашка", Component.literal("Ind Box / Черный Индик:"), btnShowDegreeIndicator));
            this.addRenderableWidget(btnShowDegreeIndicator);

            Button btnShowScaleDegrees = Button.builder(Component.literal(Config.showScaleDegrees.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.showScaleDegrees.get();
                Config.showScaleDegrees.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("DISPLAY", "scale deg цифры шкалы отображать градусы", Component.literal("Scale Deg / Цифры Шкалы:"), btnShowScaleDegrees));
            this.addRenderableWidget(btnShowScaleDegrees);

            Button btnShowOrdinalDirections = Button.builder(Component.literal(Config.showOrdinalDirections.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.showOrdinalDirections.get();
                Config.showOrdinalDirections.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("DISPLAY", "ordinals румбы nw промежуточные стороны света", Component.literal("Ordinals / Румбы NW:"), btnShowOrdinalDirections));
            this.addRenderableWidget(btnShowOrdinalDirections);

            Button btnShowWorldSpawn = Button.builder(Component.literal(Config.showWorldSpawn.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.showWorldSpawn.get();
                Config.showWorldSpawn.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("DISPLAY", "world spawn спавн мира метка видимость", Component.literal("World Spawn / Спавн:"), btnShowWorldSpawn));
            this.addRenderableWidget(btnShowWorldSpawn);

            Button btnShowSpawnName = Button.builder(Component.literal(Config.showSpawnName.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.showSpawnName.get();
                Config.showSpawnName.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("DISPLAY", "show spawn name tekst spawna imya nazvanie", Component.literal("Show Spawn Name / Имя Спавна:"), btnShowSpawnName));
            this.addRenderableWidget(btnShowSpawnName);

            Button btnShowBedSpawn = Button.builder(Component.literal(Config.showBedSpawn.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.showBedSpawn.get();
                Config.showBedSpawn.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("DISPLAY", "bed spawn спавн кровати дом кровать видимость", Component.literal("Bed Spawn / Кровать:"), btnShowBedSpawn));
            this.addRenderableWidget(btnShowBedSpawn);

            Button btnShowBedName = Button.builder(Component.literal(Config.showBedName.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.showBedName.get();
                Config.showBedName.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("DISPLAY", "show bed name текст кровати дом имя", Component.literal("Show Bed Name / Имя Дома:"), btnShowBedName));
            this.addRenderableWidget(btnShowBedName);

            Button btnShowLodestones = Button.builder(Component.literal(Config.showLodestones.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.showLodestones.get();
                Config.showLodestones.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("DISPLAY", "lodestones магнетиты метки компасы видимость", Component.literal("Lodestones / Магнетиты:"), btnShowLodestones));
            this.addRenderableWidget(btnShowLodestones);

            Button btnShowLodestoneNames = Button.builder(Component.literal(Config.showLodestoneNames.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.showLodestoneNames.get();
                Config.showLodestoneNames.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("DISPLAY", "lodestone names имена магнетитов названия текста", Component.literal("Lodestone Names / Имена Магн.:"), btnShowLodestoneNames));
            this.addRenderableWidget(btnShowLodestoneNames);

            Button btnUseBracketParsing = Button.builder(Component.literal(Config.useBracketParsing.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.useBracketParsing.get();
                Config.useBracketParsing.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("DISPLAY", "bracket parsing скобки анализатор строк парсер", Component.literal("Bracket Parsing / Парсер []:"), btnUseBracketParsing));
            this.addRenderableWidget(btnUseBracketParsing);

            Button btnUseCurlyBracketBg = Button.builder(Component.literal(Config.useCurlyBracketBg.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.useCurlyBracketBg.get();
                Config.useCurlyBracketBg.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("DISPLAY", "curly bracket bg фигурные скобки фон подложка плашка", Component.literal("Curly Bg {} / Фон Скобок:"), btnUseCurlyBracketBg));
            this.addRenderableWidget(btnUseCurlyBracketBg);

            Button btnCustomElementOffsets = Button.builder(Component.literal(Config.customElementOffsets.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.customElementOffsets.get();
                Config.customElementOffsets.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("DISPLAY", "cust offset свои смещ кастомные смещения", Component.literal("Cust Offset / Свои Смещ:"), btnCustomElementOffsets));
            this.addRenderableWidget(btnCustomElementOffsets);

            this.txtTextYOffset = new EditBox(this.font, 0, 0, 120, 20, Component.literal("TextY"));
            this.txtTextYOffset.setValue(String.valueOf(Config.textYOffset.get()));
            totalRows.add(new ConfigRow("DISPLAY", "text y буквы y смещение текста глобальное", Component.literal("Global Text Y / Общ Буквы Y:"), this.txtTextYOffset));
            this.addRenderableWidget(this.txtTextYOffset);

            this.txtTickYOffset = new EditBox(this.font, 0, 0, 120, 20, Component.literal("TickY"));
            this.txtTickYOffset.setValue(String.valueOf(Config.tickYOffset.get()));
            totalRows.add(new ConfigRow("DISPLAY", "tick y черточки y смещение делений", Component.literal("Tick Y / Черточки Y:"), this.txtTickYOffset));
            this.addRenderableWidget(this.txtTickYOffset);

            this.txtDegreeYOffset = new EditBox(this.font, 0, 0, 120, 20, Component.literal("DegY"));
            this.txtDegreeYOffset.setValue(String.valueOf(Config.degreeYOffset.get()));
            totalRows.add(new ConfigRow("DISPLAY", "deg y градусы y шкала", Component.literal("Deg Y / Градусы Y:"), this.txtDegreeYOffset));
            this.addRenderableWidget(this.txtDegreeYOffset);

            this.txtArrowYOffset = new EditBox(this.font, 0, 0, 120, 20, Component.literal("ArrowY"));
            this.txtArrowYOffset.setValue(String.valueOf(Config.arrowYOffset.get()));
            totalRows.add(new ConfigRow("DISPLAY", "arrow y стрелка y указатель", Component.literal("Arrow Y / Стрелка Y:"), this.txtArrowYOffset));
            this.addRenderableWidget(this.txtArrowYOffset);

            this.txtBoxYOffset = new EditBox(this.font, 0, 0, 120, 20, Component.literal("BoxY"));
            this.txtBoxYOffset.setValue(String.valueOf(Config.boxYOffset.get()));
            totalRows.add(new ConfigRow("DISPLAY", "box y индикатор y плашка смещение", Component.literal("Box Y / Индикатор Y:"), this.txtBoxYOffset));
            this.addRenderableWidget(this.txtBoxYOffset);


            // --- COLORS / MARKERS CONFIG ---
            this.txtMainColor = new EditBox(this.font, 0, 0, 120, 20, Component.literal("MainColor"));
            this.txtMainColor.setValue(Config.mainColor.get());
            totalRows.add(new ConfigRow("COLORS", "main clr осн цв цвет основной", Component.literal("Main Clr / Осн Цв:"), this.txtMainColor));
            this.addRenderableWidget(this.txtMainColor);

            this.txtInterColor = new EditBox(this.font, 0, 0, 120, 20, Component.literal("InterColor"));
            this.txtInterColor.setValue(Config.interColor.get());
            totalRows.add(new ConfigRow("COLORS", "inter clr пром промежуточный цвет", Component.literal("Inter Clr / Пром:"), this.txtInterColor));
            this.addRenderableWidget(this.txtInterColor);

            this.txtNumberColor = new EditBox(this.font, 0, 0, 120, 20, Component.literal("NumberColor"));
            this.txtNumberColor.setValue(Config.numberColor.get());
            totalRows.add(new ConfigRow("COLORS", "num clr цв цифр цвет градусы", Component.literal("Num Clr / Цв Цифр:"), this.txtNumberColor));
            this.addRenderableWidget(this.txtNumberColor);

            this.txtIndicatorColor = new EditBox(this.font, 0, 0, 120, 20, Component.literal("IndicatorColor"));
            this.txtIndicatorColor.setValue(Config.indicatorColor.get());
            totalRows.add(new ConfigRow("COLORS", "ind clr цв указ указатель цвет", Component.literal("Ind Clr / Цв Указ:"), this.txtIndicatorColor));
            this.addRenderableWidget(this.txtIndicatorColor);

            Button btnSepClr = Button.builder(Component.literal(Config.separateColors.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.separateColors.get();
                Config.separateColors.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(0, 0, 120, 20).build();
            totalRows.add(new ConfigRow("COLORS", "sep clr разд цв раздельные цвета", Component.literal("Sep Clr / Разд Цв:"), btnSepClr));
            this.addRenderableWidget(btnSepClr);

            // Настройки Спавна
            this.txtSpawnColor = new EditBox(this.font, 0, 0, 120, 20, Component.literal("SpawnColor"));
            this.txtSpawnColor.setValue(Config.spawnColor.get());
            totalRows.add(new ConfigRow("COLORS", "spawn color цвет спавна", Component.literal("Spawn Color / Цвет Спавна:"), this.txtSpawnColor));
            this.addRenderableWidget(this.txtSpawnColor);

            this.txtSpawnSymbol = new EditBox(this.font, 0, 0, 120, 20, Component.literal("SpawnSymbol"));
            this.txtSpawnSymbol.setValue(Config.spawnSymbol.get());
            totalRows.add(new EditBoxRow("COLORS", "spawn symbol символ спавна знак деление", Component.literal("Spawn Symbol / Символ:"), this.txtSpawnSymbol));

            this.txtSpawnScale = new EditBox(this.font, 0, 0, 120, 20, Component.literal("SpawnScale"));
            this.txtSpawnScale.setValue(String.valueOf(Config.spawnTextScale.get()));
            totalRows.add(new EditBoxRow("COLORS", "spawn scale размер маштаб текста спавна", Component.literal("Spawn Scale / Масштаб текста:"), this.txtSpawnScale));

            this.txtSpawnYOffset = new EditBox(this.font, 0, 0, 120, 20, Component.literal("SpawnYOffset"));
            this.txtSpawnYOffset.setValue(String.valueOf(Config.spawnTextYOffset.get()));
            totalRows.add(new EditBoxRow("COLORS", "spawn y offset смещение текста спавна y", Component.literal("Spawn Text Y Offset / Смещ. Y:"), this.txtSpawnYOffset));

            // Настройки Кровати (Дома)
            this.txtBedColor = new EditBox(this.font, 0, 0, 120, 20, Component.literal("BedColor"));
            this.txtBedColor.setValue(Config.bedColor.get());
            totalRows.add(new ConfigRow("COLORS", "bed color цвет кровати дома", Component.literal("Bed Color / Цвет Кровати:"), this.txtBedColor));
            this.addRenderableWidget(this.txtBedColor);

            this.txtBedSymbol = new EditBox(this.font, 0, 0, 120, 20, Component.literal("BedSymbol"));
            this.txtBedSymbol.setValue(Config.bedSymbol.get());
            totalRows.add(new EditBoxRow("COLORS", "bed symbol символ кровати дома знак деление", Component.literal("Bed Symbol / Символ:"), this.txtBedSymbol));

            this.txtBedScale = new EditBox(this.font, 0, 0, 120, 20, Component.literal("BedScale"));
            this.txtBedScale.setValue(String.valueOf(Config.bedTextScale.get()));
            totalRows.add(new EditBoxRow("COLORS", "bed scale размер маштаб текста кровати", Component.literal("Bed Scale / Масштаб текста:"), this.txtBedScale));

            this.txtBedYOffset = new EditBox(this.font, 0, 0, 120, 20, Component.literal("BedYOffset"));
            this.txtBedYOffset.setValue(String.valueOf(Config.bedTextYOffset.get()));
            totalRows.add(new EditBoxRow("COLORS", "bed y offset смещение текста кровати y", Component.literal("Bed Text Y Offset / Смещ. Y:"), this.txtBedYOffset));

            // Настройки Магнетитов
            this.txtLodeColor = new EditBox(this.font, 0, 0, 120, 20, Component.literal("LodeColor"));
            this.txtLodeColor.setValue(Config.lodestoneColor.get());
            totalRows.add(new ConfigRow("COLORS", "lodestone color цвет магнетита", Component.literal("Lodestone Color / Цвет Магн.:"), this.txtLodeColor));
            this.addRenderableWidget(this.txtLodeColor);

            this.txtLodeSymbol = new EditBox(this.font, 0, 0, 120, 20, Component.literal("LodeSymbol"));
            this.txtLodeSymbol.setValue(Config.lodestoneSymbol.get());
            totalRows.add(new EditBoxRow("COLORS", "lodestone symbol символ магнетита знак деление", Component.literal("Lodestone Symbol / Символ:"), this.txtLodeSymbol));

            this.txtLodeScale = new EditBox(this.font, 0, 0, 120, 20, Component.literal("LodeScale"));
            this.txtLodeScale.setValue(String.valueOf(Config.lodestoneTextScale.get()));
            totalRows.add(new EditBoxRow("COLORS", "lodestone scale размер маштаб текста магнетита", Component.literal("Lodestone Scale / Масштаб текста:"), this.txtLodeScale));

            this.txtLodeYOffset = new EditBox(this.font, 0, 0, 120, 20, Component.literal("LodeYOffset"));
            this.txtLodeYOffset.setValue(String.valueOf(Config.lodestoneTextYOffset.get()));
            totalRows.add(new EditBoxRow("COLORS", "lodestone y offset смещение текста магнетита y", Component.literal("Lodestone Text Y Offset / Смещ. Y:"), this.txtLodeYOffset));

            // --- Нижняя Панель Управления ---
            this.addRenderableWidget(Button.builder(Component.literal("Save / Сохранить"), b -> saveAndClose())
                    .bounds(this.width / 2 - 105, this.height - 26, 100, 20).build());

            this.btnReset = Button.builder(Component.literal("Reset / Сброс"), b -> handleResetClick())
                    .bounds(this.width / 2 + 5, this.height - 26, 100, 20).build();
            this.addRenderableWidget(this.btnReset);
        }

        private class EditBoxRow extends ConfigRow {
            EditBoxRow(String category, String searchKey, Component label, EditBox widget) {
                super(category, searchKey, label, widget);
                CompassConfigScreen.this.addRenderableWidget(widget);
            }
        }

        private void handleResetClick() {
            if (!confirmReset) {
                confirmReset = true;
                resetTimer = System.currentTimeMillis();
            } else {
                confirmReset = false;
                resetToDefaults();
                if (this.minecraft != null) {
                    this.init(this.minecraft, this.width, this.height);
                }
            }
        }

        private void resetToDefaults() {
            Config.enabled.set(true);
            Config.position.set(CompassPosition.TOP);
            Config.xOffset.set(0);
            Config.yOffset.set(10);
            Config.scale.set(1.0);
            Config.width.set(240);
            Config.requireCompassInInventory.set(false);
            Config.animationDuration.set(300);
            Config.smoothingEnabled.set(true);
            Config.smoothingSpeed.set(150);
            Config.smoothingMode.set(SmoothingMode.EXPONENTIAL);
            Config.showDegreeIndicator.set(true);
            Config.showScaleDegrees.set(true);
            Config.showOrdinalDirections.set(true);
            Config.customElementOffsets.set(false);
            Config.textYOffset.set(-14);
            Config.tickYOffset.set(-12);
            Config.degreeYOffset.set(4);
            Config.arrowYOffset.set(-4);
            Config.boxYOffset.set(14);
            Config.separateColors.set(true);
            Config.mainColor.set("#FFFFFF");
            Config.interColor.set("#FFAA00");
            Config.numberColor.set("#AAAAAA");
            Config.indicatorColor.set("#FFFFFF");

            Config.showWorldSpawn.set(true);
            Config.showSpawnName.set(true);
            Config.showBedSpawn.set(true);
            Config.showBedName.set(true);
            Config.showLodestones.set(true);
            Config.showLodestoneNames.set(true);
            Config.useBracketParsing.set(true);
            Config.useCurlyBracketBg.set(true);

            Config.spawnColor.set("#FF0000");
            Config.spawnSymbol.set("");
            Config.spawnTextScale.set(1.0);
            Config.spawnTextYOffset.set(0);

            Config.bedColor.set("#00FF00");
            Config.bedSymbol.set("");
            Config.bedTextScale.set(1.0);
            Config.bedTextYOffset.set(0);

            Config.lodestoneColor.set("#FF00FF");
            Config.lodestoneSymbol.set("");
            Config.lodestoneTextScale.set(1.0);
            Config.lodestoneTextYOffset.set(0);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            if (this.maxScroll > 0) {
                this.scrollAmount = Math.max(0, Math.min(this.maxScroll, this.scrollAmount - scrollY * 14));
                return true;
            }
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        @Override
        public void tick() {
            if (confirmReset) {
                long secondsLeft = 3 - (System.currentTimeMillis() - resetTimer) / 1000;
                if (secondsLeft <= 0) {
                    confirmReset = false;
                    if (btnReset != null) btnReset.setMessage(Component.literal("Reset / Сброс"));
                } else {
                    if (btnReset != null) btnReset.setMessage(Component.literal("Sure? / Уверены? (" + secondsLeft + "s)"));
                }
            }
        }

        // 1. Принудительно отключаем стандартный фон (и блюр)
        @Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            // Оставляем пустым, чтобы ничего не рендерилось
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            // 2. Рисуем свой собственный фон вручную (без блюра)
            graphics.fill(0, 0, this.width, this.height, 0xC0000000);

            // Рисуем заголовок
            graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);

            String filter = this.searchBox != null ? this.searchBox.getValue().trim().toLowerCase() : "";
            boolean isSearching = !filter.isEmpty();

            int topBound = 64;
            int bottomBound = this.height - 32;
            int currentY = topBound - (int) this.scrollAmount;
            int rowHeight = 24;

            for (ConfigRow row : totalRows) {
                boolean matchCategory = isSearching || row.category.equalsIgnoreCase(activeCategory);
                boolean matchSearch = !isSearching || row.searchKey.contains(filter);

                if (matchCategory && matchSearch) {
                    row.widget.setX(this.width / 2 + 10);
                    row.widget.setY(currentY);

                    if (currentY >= topBound && currentY <= bottomBound - 20) {
                        row.widget.visible = true;
                        row.widget.active = true;
                        graphics.drawString(this.font, row.label, this.width / 2 - 170, currentY + 6, 0xFFAAAAAA, false);
                    } else {
                        row.widget.visible = false;
                        row.widget.active = false;
                    }
                    currentY += rowHeight;
                } else {
                    row.widget.visible = false;
                    row.widget.active = false;
                }
            }

            int totalHeight = (currentY + (int) this.scrollAmount) - topBound;
            this.maxScroll = Math.max(0, totalHeight - (bottomBound - topBound - 16));

            // 3. Вызываем рендер виджетов поверх нашего фона
            super.render(graphics, mouseX, mouseY, partialTicks);
        }

        private void saveAndClose() {
            try {
                if (txtXOffset != null) Config.xOffset.set(Integer.parseInt(txtXOffset.getValue()));
                if (txtYOffset != null) Config.yOffset.set(Integer.parseInt(txtYOffset.getValue()));
                if (txtScale != null) Config.scale.set(Double.parseDouble(txtScale.getValue()));
                if (txtWidth != null) Config.width.set(Integer.parseInt(txtWidth.getValue()));
                if (txtSmoothSpeed != null) Config.smoothingSpeed.set(Integer.parseInt(txtSmoothSpeed.getValue()));
                if (txtAnimDuration != null) Config.animationDuration.set(Integer.parseInt(txtAnimDuration.getValue()));

                if (txtMainColor != null) Config.mainColor.set(txtMainColor.getValue());
                if (txtInterColor != null) Config.interColor.set(txtInterColor.getValue());
                if (txtNumberColor != null) Config.numberColor.set(txtNumberColor.getValue());
                if (txtIndicatorColor != null) Config.indicatorColor.set(txtIndicatorColor.getValue());

                if (txtTextYOffset != null) Config.textYOffset.set(Integer.parseInt(txtTextYOffset.getValue()));
                if (txtTickYOffset != null) Config.tickYOffset.set(Integer.parseInt(txtTickYOffset.getValue()));
                if (txtDegreeYOffset != null) Config.degreeYOffset.set(Integer.parseInt(txtDegreeYOffset.getValue()));
                if (txtArrowYOffset != null) Config.arrowYOffset.set(Integer.parseInt(txtArrowYOffset.getValue()));
                if (txtBoxYOffset != null) Config.boxYOffset.set(Integer.parseInt(txtBoxYOffset.getValue()));

                if (txtSpawnColor != null) Config.spawnColor.set(txtSpawnColor.getValue());
                if (txtSpawnSymbol != null) Config.spawnSymbol.set(txtSpawnSymbol.getValue());
                if (txtSpawnScale != null) Config.spawnTextScale.set(Double.parseDouble(txtSpawnScale.getValue()));
                if (txtSpawnYOffset != null) Config.spawnTextYOffset.set(Integer.parseInt(txtSpawnYOffset.getValue()));

                if (txtBedColor != null) Config.bedColor.set(txtBedColor.getValue());
                if (txtBedSymbol != null) Config.bedSymbol.set(txtBedSymbol.getValue());
                if (txtBedScale != null) Config.bedTextScale.set(Double.parseDouble(txtBedScale.getValue()));
                if (txtBedYOffset != null) Config.bedTextYOffset.set(Integer.parseInt(txtBedYOffset.getValue()));

                if (txtLodeColor != null) Config.lodestoneColor.set(txtLodeColor.getValue());
                if (txtLodeSymbol != null) Config.lodestoneSymbol.set(txtLodeSymbol.getValue());
                if (txtLodeScale != null) Config.lodestoneTextScale.set(Double.parseDouble(txtLodeScale.getValue()));
                if (txtLodeYOffset != null) Config.lodestoneTextYOffset.set(Integer.parseInt(txtLodeYOffset.getValue()));

            } catch (Exception e) {
                // ВАЖНО: Выведи ошибку в консоль, чтобы понять, какой именно параметр "ломает" сохранение
                e.printStackTrace();
            }

            // Сохраняем всегда, даже если парсинг был частично с ошибкой
            Config.SPEC.save();

            if (this.minecraft != null) {
                this.minecraft.setScreen(this.parent);
            }
        }
    }

    public static class Config {
        public static final ModConfigSpec SPEC;

        public static final ModConfigSpec.BooleanValue enabled;
        public static final ModConfigSpec.EnumValue<CompassPosition> position;
        public static final ModConfigSpec.IntValue xOffset;
        public static final ModConfigSpec.IntValue yOffset;
        public static final ModConfigSpec.DoubleValue scale;
        public static final ModConfigSpec.IntValue width;
        public static final ModConfigSpec.BooleanValue requireCompassInInventory;
        public static final ModConfigSpec.IntValue animationDuration;

        public static final ModConfigSpec.BooleanValue smoothingEnabled;
        public static final ModConfigSpec.IntValue smoothingSpeed;
        public static final ModConfigSpec.EnumValue<SmoothingMode> smoothingMode;

        public static final ModConfigSpec.BooleanValue showDegreeIndicator;
        public static final ModConfigSpec.BooleanValue showScaleDegrees;
        public static final ModConfigSpec.BooleanValue showOrdinalDirections;

        public static final ModConfigSpec.BooleanValue showWorldSpawn;
        public static final ModConfigSpec.BooleanValue showSpawnName;
        public static final ModConfigSpec.BooleanValue showBedSpawn;
        public static final ModConfigSpec.BooleanValue showBedName;
        public static final ModConfigSpec.BooleanValue showLodestones;
        public static final ModConfigSpec.BooleanValue showLodestoneNames;
        public static final ModConfigSpec.BooleanValue useBracketParsing;
        public static final ModConfigSpec.BooleanValue useCurlyBracketBg;

        public static final ModConfigSpec.BooleanValue customElementOffsets;
        public static final ModConfigSpec.IntValue textYOffset;
        public static final ModConfigSpec.IntValue tickYOffset;
        public static final ModConfigSpec.IntValue degreeYOffset;
        public static final ModConfigSpec.IntValue arrowYOffset;
        public static final ModConfigSpec.IntValue boxYOffset;

        public static final ModConfigSpec.BooleanValue separateColors;
        public static final ModConfigSpec.ConfigValue<String> mainColor;
        public static final ModConfigSpec.ConfigValue<String> interColor;
        public static final ModConfigSpec.ConfigValue<String> numberColor;
        public static final ModConfigSpec.ConfigValue<String> indicatorColor;

        public static final ModConfigSpec.ConfigValue<String> spawnColor;
        public static final ModConfigSpec.ConfigValue<String> spawnSymbol;
        public static final ModConfigSpec.DoubleValue spawnTextScale;
        public static final ModConfigSpec.IntValue spawnTextYOffset;

        public static final ModConfigSpec.ConfigValue<String> bedColor;
        public static final ModConfigSpec.ConfigValue<String> bedSymbol;
        public static final ModConfigSpec.DoubleValue bedTextScale;
        public static final ModConfigSpec.IntValue bedTextYOffset;

        public static final ModConfigSpec.ConfigValue<String> lodestoneColor;
        public static final ModConfigSpec.ConfigValue<String> lodestoneSymbol;
        public static final ModConfigSpec.DoubleValue lodestoneTextScale;
        public static final ModConfigSpec.IntValue lodestoneTextYOffset;

        static {
            ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

            builder.comment("General Settings / Общие настройки").push("general");
            enabled = builder.define("enabled", true);
            position = builder.defineEnum("position", CompassPosition.TOP);
            xOffset = builder.defineInRange("xOffset", 0, -1000, 1000);
            yOffset = builder.defineInRange("yOffset", 10, -1000, 1000);
            scale = builder.defineInRange("scale", 1.0, 0.1, 5.0);
            width = builder.defineInRange("width", 240, 100, 1000);
            requireCompassInInventory = builder.define("requireCompassInInventory", false);
            animationDuration = builder.defineInRange("animationDuration", 300, 1, 5000);
            builder.pop();

            builder.comment("Smoothing / Плавность движения").push("smoothing");
            smoothingEnabled = builder.define("smoothingEnabled", true);
            smoothingSpeed = builder.defineInRange("smoothingSpeed", 150, 1, 2000);
            smoothingMode = builder.defineEnum("smoothingMode", SmoothingMode.EXPONENTIAL);
            builder.pop();

            builder.comment("Display Options / Настройки отображения элементов").push("display");
            showDegreeIndicator = builder.define("showDegreeIndicator", true);
            showScaleDegrees = builder.define("showScaleDegrees", true);
            showOrdinalDirections = builder.define("showOrdinalDirections", true);

            showWorldSpawn = builder.define("showWorldSpawn", true);
            showSpawnName = builder.define("showSpawnName", true);
            showBedSpawn = builder.define("showBedSpawn", true);
            showBedName = builder.define("showBedName", true);
            showLodestones = builder.define("showLodestones", true);
            showLodestoneNames = builder.define("showLodestoneNames", true);
            useBracketParsing = builder.define("useBracketParsing", true);
            useCurlyBracketBg = builder.define("useCurlyBracketBg", true);

            customElementOffsets = builder.define("customElementOffsets", false);
            textYOffset = builder.defineInRange("textYOffset", -14, -100, 100);
            tickYOffset = builder.defineInRange("tickYOffset", -12, -100, 100);
            degreeYOffset = builder.defineInRange("degreeYOffset", 4, -100, 100);
            arrowYOffset = builder.defineInRange("arrowYOffset", -4, -100, 100);
            boxYOffset = builder.defineInRange("boxYOffset", 14, -100, 100);
            builder.pop();

            builder.comment("Colors & Markers Customization / Цвета и кастомизация меток").push("colors");
            separateColors = builder.define("separateColors", true);
            mainColor = builder.define("mainColor", "#FFFFFF");
            interColor = builder.define("interColor", "#FFAA00");
            numberColor = builder.define("numberColor", "#AAAAAA");
            indicatorColor = builder.define("indicatorColor", "#FFFFFF");

            spawnColor = builder.define("spawnColor", "#FF0000");
            spawnSymbol = builder.define("spawnSymbol", "");
            spawnTextScale = builder.defineInRange("spawnTextScale", 1.0, 0.1, 3.0);
            spawnTextYOffset = builder.defineInRange("spawnTextYOffset", 0, -100, 100);

            bedColor = builder.define("bedColor", "#00FF00");
            bedSymbol = builder.define("bedSymbol", "");
            bedTextScale = builder.defineInRange("bedTextScale", 1.0, 0.1, 3.0);
            bedTextYOffset = builder.defineInRange("bedTextYOffset", 0, -100, 100);

            lodestoneColor = builder.define("lodestoneColor", "#FF00FF");
            lodestoneSymbol = builder.define("lodestoneSymbol", "");
            lodestoneTextScale = builder.defineInRange("lodestoneTextScale", 1.0, 0.1, 3.0);
            lodestoneTextYOffset = builder.defineInRange("lodestoneTextYOffset", 0, -100, 100);
            builder.pop();

            SPEC = builder.build();
        }
    }
}