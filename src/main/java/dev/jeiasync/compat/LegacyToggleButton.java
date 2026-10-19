package dev.jeiasync.compat;

import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.buttons.IButtonState;
import mezz.jei.api.gui.buttons.IIconButtonController;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.inputs.IJeiUserInput;
import mezz.jei.common.gui.JeiTooltip;
import mezz.jei.common.input.IInputTarget;
import mezz.jei.common.input.UserInput;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.elements.IconButton;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Stand-in for the toggle button JEI removed in 15.71.
 * JEI++ still subclasses that type for the recipe-tree sidebar. The real button is an
 * {@link IconButton}, so the sidebar can be created while Forge builds the GUI off the render thread.
 */
public abstract class LegacyToggleButton {
	private final IDrawable offIcon;
	private final IDrawable onIcon;
	private final IconButton button;

	protected LegacyToggleButton(IDrawable offIcon, IDrawable onIcon) {
		this.offIcon = offIcon;
		this.onIcon = onIcon;
		this.button = new IconButton(new Controller());
	}

	protected abstract void getTooltips(JeiTooltip tooltip);

	protected abstract boolean isIconToggledOn();

	protected abstract boolean onMouseClicked(UserInput input);

	public void updateBounds(ImmutableRect2i area) {
		button.updateBounds(area);
	}

	public void tick() {
		button.tick();
	}

	public void draw(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		button.draw(graphics, mouseX, mouseY, partialTicks);
	}

	public void drawTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
		button.drawTooltips(graphics, mouseX, mouseY);
	}

	public boolean isMouseOver(double mouseX, double mouseY) {
		return button.isMouseOver(mouseX, mouseY);
	}

	public IInputTarget createInputHandler() {
		return button.createInputHandler();
	}

	private final class Controller implements IIconButtonController {
		@Override
		public boolean onPress(IJeiUserInput input) {
			if (input instanceof UserInput user) {
				return onMouseClicked(user);
			}
			return false;
		}

		@Override
		public void getTooltips(ITooltipBuilder tooltip) {
			if (tooltip instanceof JeiTooltip jeiTooltip) {
				LegacyToggleButton.this.getTooltips(jeiTooltip);
			}
		}

		@Override
		public void initState(IButtonState state) {
			state.setIcon(offIcon);
		}

		@Override
		public void updateState(IButtonState state) {
			boolean toggled = isIconToggledOn();
			state.setIcon(toggled ? onIcon : offIcon);
			state.setForcePressed(toggled);
		}
	}
}
