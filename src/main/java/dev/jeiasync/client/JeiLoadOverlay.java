package dev.jeiasync.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.jeiasync.JeiAsyncMod;
import dev.jeiasync.JeiLoadProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Draws JEI's load progress on a container screen opened before startup has published a runtime.
 * <p>
 * JEI's own overlay is registered only after startup, so until then the screen is vanilla. This
 * bar does not take input, and a failure while drawing it must not close the screen.
 */
@Mod.EventBusSubscriber(modid = JeiAsyncMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class JeiLoadOverlay {
	private static final Logger LOGGER = LogManager.getLogger();
	private static boolean reportedDrawFailure;

	private JeiLoadOverlay() {
	}

	@SubscribeEvent
	public static void onRender(ScreenEvent.Render.Post event) {
		if (!JeiLoadProgress.isVisible() || !(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
			return;
		}
		GuiGraphics graphics = event.getGuiGraphics();
		PoseStack pose = graphics.pose();
		pose.pushPose();
		pose.setIdentity();
		try {
			draw(screen, graphics);
		} catch (Throwable t) {
			if (!reportedDrawFailure) {
				reportedDrawFailure = true;
				LOGGER.error("JEI Async: progress bar failed to draw", t);
			}
		} finally {
			pose.popPose();
		}
	}

	private static void draw(AbstractContainerScreen<?> screen, GuiGraphics graphics) {
		Minecraft minecraft = Minecraft.getInstance();
		Font font = minecraft.font;
		if (font == null) {
			return;
		}

		int barWidth = Math.min(Math.max(screen.getXSize() - 16, 120), 220);
		int panelWidth = barWidth + 12;
		int panelHeight = 38;
		int x = screen.getGuiLeft() + (screen.getXSize() - panelWidth) / 2;
		int y = screen.getGuiTop() + screen.getYSize() + 6;
		if (y + panelHeight > screen.height - 4) {
			y = screen.getGuiTop() - panelHeight - 4;
		}
		if (y < 4 || x < 4 || x + panelWidth > screen.width - 4) {
			x = Math.max(4, (screen.width - panelWidth) / 2);
			y = Math.max(4, screen.height - panelHeight - 8);
		}

		float fraction = JeiLoadProgress.fraction();
		int percent = (int) (fraction * 100f);
		boolean failed = JeiLoadProgress.failed();
		int fillColor = failed ? 0xFFB04040 : 0xFFE39A2B;
		int barX = x + 6;
		int barY = y + 16;
		int fill = (int) (barWidth * fraction);

		graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xC0101010);
		graphics.fill(x, y, x + panelWidth, y + 1, 0xFF3A3A3A);
		graphics.fill(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, 0xFF3A3A3A);
		graphics.fill(barX, barY, barX + barWidth, barY + 6, 0xFF2A2A2A);
		if (fill > 0) {
			graphics.fill(barX, barY, barX + Math.min(fill, barWidth), barY + 6, fillColor);
		}

		String title = failed ? "JEI failed" : "Loading JEI  " + percent + "%";
		graphics.drawString(font, Component.literal(title), barX, y + 5, 0xFFE8E8E8, true);

		String label = JeiLoadProgress.label();
		int maxWidth = barWidth;
		if (font.width(label) > maxWidth) {
			while (label.length() > 4 && font.width(label + "...") > maxWidth) {
				label = label.substring(0, label.length() - 1);
			}
			label = label + "...";
		}
		graphics.drawString(font, Component.literal(label), barX, barY + 8, failed ? 0xFFFF8080 : 0xFFB0B0B0, false);
	}
}
