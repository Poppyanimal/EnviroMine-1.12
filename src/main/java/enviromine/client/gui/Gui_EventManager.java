package enviromine.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.util.StatCollector;
import net.minecraftforge.client.event.GuiScreenEvent.ActionPerformedEvent;
import net.minecraftforge.client.event.GuiScreenEvent.InitGuiEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import enviromine.client.gui.menu.EM_Gui_Menu;

@SideOnly(Side.CLIENT)
public class Gui_EventManager 
{

	int	width, height;
	
	@SubscribeEvent
	public void renderevent(InitGuiEvent.Post event)
	{
		width = event.gui.width;
		height = event.gui.height;

		if(event.gui instanceof GuiIngameMenu)
		{
			event.buttonList.add(new GuiButton(1348, width - 175, height  - 30, 160, 20, StatCollector.translateToLocal("options.enviromine.menu.title")));
		}
	}
	@SubscribeEvent
	public void action(ActionPerformedEvent.Post event)
	{
		if(event.gui instanceof GuiIngameMenu)
		{
			if(event.button.id == 1348)
			{
				Minecraft.getMinecraft().displayGuiScreen(new EM_Gui_Menu(event.gui));
			}
	
		}	
}	}

