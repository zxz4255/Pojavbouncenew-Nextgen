package net.ccbluex.liquidbounce.ui.clickgui
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
class TabbedClickGuiScreen : Screen(Component.empty()) { fun isTypingIntoSetting() = false }
object NativeScreens {fun isClientScreen(screen: Screen?) = false}
