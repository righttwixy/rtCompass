package by.righttwixys.neocompass;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
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

@Mod(Compass.MODID)
public class Compass {
    public static final String MODID = "neocompass";


    private static float currentHeading = 0f;
    private static long lastTime = System.currentTimeMillis();


    private static float currentAlpha = 0f;

    public Compass(ModContainer container, IEventBus modEventBus) {

        container.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);


        container.registerExtensionPoint(IConfigScreenFactory.class, new IConfigScreenFactory() {
            @Override
            public Screen createScreen(ModContainer modContainer, Screen screen) {
                return new CompassConfigScreen(screen);
            }
        });
    }


    public enum CompassPosition { TOP, ABOVE_HOTBAR }
    public enum SmoothingMode { LINEAR, EXPONENTIAL }

    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ClientEvents {

        @SubscribeEvent
        public static void onRenderGui(RenderGuiLayerEvent.Post event) {

            if (!event.getName().equals(VanillaGuiLayers.CROSSHAIR)) {
                return;
            }

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.options.hideGui) return;


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
            int centerX = mc.getWindow().getGuiScaledWidth() / 2;
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
            int boxBgColor = (int) (0xAA * currentAlpha) << 24 | 0x00000000;


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
                    boolean isInter = angle % 45 == 0 && !isMain;

                    int currentColor = isMain ? mainColor : (isInter ? interColor : numColor);


                    if (isMain || isInter) {
                        String label = getMarkerLabel(angle);
                        graphics.drawCenteredString(font, label, markerX, centerY - 14, currentColor);
                    } else {
                        graphics.drawCenteredString(font, "|", markerX, centerY - 12, currentColor);
                    }


                    if (Config.showScaleDegrees.get()) {
                        graphics.drawCenteredString(font, String.valueOf(angle), markerX, centerY + 4, currentColor);
                    }
                }
            }

            if (scissorW > 0 && scissorH > 0) {
                RenderSystem.disableScissor();
            }


            graphics.drawCenteredString(font, "▲", centerX, centerY - 4, indColor);


            if (Config.showDegreeIndicator.get()) {
                int currentIntHeading = Math.round(currentHeading);
                String centerText = String.valueOf(currentIntHeading == 360 ? 0 : currentIntHeading);


                int textW = font.width(centerText);
                graphics.fill(centerX - textW / 2 - 4, centerY + 14, centerX + textW / 2 + 4, centerY + 25, boxBgColor);
                graphics.drawCenteredString(font, centerText, centerX, centerY + 15, indColor);
            }

            graphics.pose().popPose();
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
        private EditBox txtYOffset;
        private EditBox txtScale;
        private EditBox txtWidth;
        private EditBox txtSmoothSpeed;
        private EditBox txtAnimDuration;
        private EditBox txtMainColor;
        private EditBox txtInterColor;
        private EditBox txtNumberColor;
        private EditBox txtIndicatorColor;

        public CompassConfigScreen(Screen parent) {
            super(Component.literal("Compass Configuration"));
            this.parent = parent;
        }


        @Override
        public void renderBlurredBackground(float partialTicks) {

        }

        @Override
        protected void init() {
            int leftWidgetX = this.width / 2 - 180 + 105;
            int rightWidgetX = this.width / 2 + 10 + 105;


            this.addRenderableWidget(Button.builder(Component.literal(Config.enabled.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.enabled.get();
                Config.enabled.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(leftWidgetX, 42, 65, 20).build());


            this.txtSmoothSpeed = new EditBox(this.font, rightWidgetX, 42, 65, 20, Component.literal("Speed"));
            this.txtSmoothSpeed.setValue(String.valueOf(Config.smoothingSpeed.get()));
            this.addRenderableWidget(this.txtSmoothSpeed);


            this.addRenderableWidget(Button.builder(Component.literal(Config.position.get() == CompassPosition.TOP ? "TOP" : "HOTB"), b -> {
                CompassPosition next = Config.position.get() == CompassPosition.TOP ? CompassPosition.ABOVE_HOTBAR : CompassPosition.TOP;
                Config.position.set(next);
                b.setMessage(Component.literal(next == CompassPosition.TOP ? "TOP" : "HOTB"));
            }).bounds(leftWidgetX, 42 + 22, 65, 20).build());


            this.addRenderableWidget(Button.builder(Component.literal(Config.smoothingMode.get() == SmoothingMode.LINEAR ? "LIN" : "EXP"), b -> {
                SmoothingMode next = Config.smoothingMode.get() == SmoothingMode.LINEAR ? SmoothingMode.EXPONENTIAL : SmoothingMode.LINEAR;
                Config.smoothingMode.set(next);
                b.setMessage(Component.literal(next == SmoothingMode.LINEAR ? "LIN" : "EXP"));
            }).bounds(rightWidgetX, 42 + 22, 65, 20).build());


            this.txtYOffset = new EditBox(this.font, leftWidgetX, 42 + 2 * 22, 65, 20, Component.literal("YOffset"));
            this.txtYOffset.setValue(String.valueOf(Config.yOffset.get()));
            this.addRenderableWidget(this.txtYOffset);


            this.txtMainColor = new EditBox(this.font, rightWidgetX, 42 + 2 * 22, 65, 20, Component.literal("MainColor"));
            this.txtMainColor.setValue(Config.mainColor.get());
            this.addRenderableWidget(this.txtMainColor);


            this.txtScale = new EditBox(this.font, leftWidgetX, 42 + 3 * 22, 65, 20, Component.literal("Scale"));
            this.txtScale.setValue(String.valueOf(Config.scale.get()));
            this.addRenderableWidget(this.txtScale);


            this.txtInterColor = new EditBox(this.font, rightWidgetX, 42 + 3 * 22, 65, 20, Component.literal("InterColor"));
            this.txtInterColor.setValue(Config.interColor.get());
            this.addRenderableWidget(this.txtInterColor);


            this.txtWidth = new EditBox(this.font, leftWidgetX, 42 + 4 * 22, 65, 20, Component.literal("Width"));
            this.txtWidth.setValue(String.valueOf(Config.width.get()));
            this.addRenderableWidget(this.txtWidth);


            this.txtNumberColor = new EditBox(this.font, rightWidgetX, 42 + 4 * 22, 65, 20, Component.literal("NumberColor"));
            this.txtNumberColor.setValue(Config.numberColor.get());
            this.addRenderableWidget(this.txtNumberColor);


            this.addRenderableWidget(Button.builder(Component.literal(Config.smoothingEnabled.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.smoothingEnabled.get();
                Config.smoothingEnabled.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(leftWidgetX, 42 + 5 * 22, 65, 20).build());

            this.txtIndicatorColor = new EditBox(this.font, rightWidgetX, 42 + 5 * 22, 65, 20, Component.literal("IndicatorColor"));
            this.txtIndicatorColor.setValue(Config.indicatorColor.get());
            this.addRenderableWidget(this.txtIndicatorColor);


            this.addRenderableWidget(Button.builder(Component.literal(Config.separateColors.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.separateColors.get();
                Config.separateColors.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(leftWidgetX, 42 + 6 * 22, 65, 20).build());


            this.addRenderableWidget(Button.builder(Component.literal(Config.requireCompassInInventory.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.requireCompassInInventory.get();
                Config.requireCompassInInventory.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(rightWidgetX, 42 + 6 * 22, 65, 20).build());


            this.addRenderableWidget(Button.builder(Component.literal(Config.showDegreeIndicator.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.showDegreeIndicator.get();
                Config.showDegreeIndicator.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(leftWidgetX, 42 + 7 * 22, 65, 20).build());


            this.addRenderableWidget(Button.builder(Component.literal(Config.showScaleDegrees.get() ? "ON" : "OFF"), b -> {
                boolean next = !Config.showScaleDegrees.get();
                Config.showScaleDegrees.set(next);
                b.setMessage(Component.literal(next ? "ON" : "OFF"));
            }).bounds(rightWidgetX, 42 + 7 * 22, 65, 20).build());


            this.txtAnimDuration = new EditBox(this.font, leftWidgetX, 42 + 8 * 22, 65, 20, Component.literal("AnimDuration"));
            this.txtAnimDuration.setValue(String.valueOf(Config.animationDuration.get()));
            this.addRenderableWidget(this.txtAnimDuration);


            this.addRenderableWidget(Button.builder(Component.literal("Save & Close / Сохранить"), b -> {
                saveAndClose();
            }).bounds(this.width / 2 - 100, 42 + 9 * 22 + 12, 200, 20).build());
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            graphics.fill(0, 0, this.width, this.height, 0xAA101010); // Классический темный фон без размытия
            graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

            int leftLabelX = this.width / 2 - 180;
            int rightLabelX = this.width / 2 + 10;


            graphics.drawString(this.font, "Enabled / Вкл:", leftLabelX, 42 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Position / Поз:", leftLabelX, 42 + 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Y Offset / Смещ:", leftLabelX, 42 + 2 * 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Scale / Масштаб:", leftLabelX, 42 + 3 * 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Width / Ширина:", leftLabelX, 42 + 4 * 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Smooth / Сглаж:", leftLabelX, 42 + 5 * 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Sep Clr / Разд Цв:", leftLabelX, 42 + 6 * 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Ind Box / Черный Индик:", leftLabelX, 42 + 7 * 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Anim Time / Время Аним:", leftLabelX, 42 + 8 * 22 + 6, 0xFFAAAAAA, false);


            graphics.drawString(this.font, "Speed / Скор (ms):", rightLabelX, 42 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Mode / Режим:", rightLabelX, 42 + 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Main Clr / Осн Цв:", rightLabelX, 42 + 2 * 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Inter Clr / Пром:", rightLabelX, 42 + 3 * 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Num Clr / Цв Цифр:", rightLabelX, 42 + 4 * 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Ind Clr / Цв Указ:", rightLabelX, 42 + 5 * 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Need Item / Комп в Инв:", rightLabelX, 42 + 6 * 22 + 6, 0xFFAAAAAA, false);
            graphics.drawString(this.font, "Scale Deg / Цифры Шкалы:", rightLabelX, 42 + 7 * 22 + 6, 0xFFAAAAAA, false);

            super.render(graphics, mouseX, mouseY, partialTicks);
        }

        private void saveAndClose() {
            try {
                Config.yOffset.set(Integer.parseInt(txtYOffset.getValue()));
                Config.scale.set(Double.parseDouble(txtScale.getValue()));
                Config.width.set(Integer.parseInt(txtWidth.getValue()));
                Config.smoothingSpeed.set(Integer.parseInt(txtSmoothSpeed.getValue()));
                Config.animationDuration.set(Integer.parseInt(txtAnimDuration.getValue()));

                Config.mainColor.set(txtMainColor.getValue());
                Config.interColor.set(txtInterColor.getValue());
                Config.numberColor.set(txtNumberColor.getValue());
                Config.indicatorColor.set(txtIndicatorColor.getValue());
            } catch (Exception e) {

            }

            Config.SPEC.save();
            if (this.minecraft != null) {
                this.minecraft.setScreen(this.parent);
            }
        }
    }

    // Класс конфигурации NeoForge
    public static class Config {
        public static final ModConfigSpec SPEC;

        public static final ModConfigSpec.BooleanValue enabled;
        public static final ModConfigSpec.EnumValue<CompassPosition> position;
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

        public static final ModConfigSpec.BooleanValue separateColors;
        public static final ModConfigSpec.ConfigValue<String> mainColor;
        public static final ModConfigSpec.ConfigValue<String> interColor;
        public static final ModConfigSpec.ConfigValue<String> numberColor;
        public static final ModConfigSpec.ConfigValue<String> indicatorColor;

        static {
            ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

            builder.comment("General Settings / Общие настройки").push("general");
            enabled = builder.define("enabled", true);
            position = builder.defineEnum("position", CompassPosition.TOP);
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
            builder.pop();

            builder.comment("Colors / Цвета").push("colors");
            separateColors = builder.define("separateColors", true);
            mainColor = builder.define("mainColor", "#FFFFFF");
            interColor = builder.define("interColor", "#FFAA00");
            numberColor = builder.define("numberColor", "#AAAAAA");
            indicatorColor = builder.define("indicatorColor", "#FFFFFF");
            builder.pop();

            SPEC = builder.build();
        }
    }
}