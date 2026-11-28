/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.ui.clickgui.setting

import com.google.common.base.CaseFormat
import net.ccbluex.liquidbounce.config.types.ValueType
import net.ccbluex.liquidbounce.features.module.ClientModule
import net.ccbluex.liquidbounce.features.module.ModuleManager
import net.ccbluex.liquidbounce.render.withPush
import net.ccbluex.liquidbounce.ui.common.UiLocalization
import net.ccbluex.liquidbounce.ui.common.UiTextUtils
import net.ccbluex.liquidbounce.utils.client.mc
import net.ccbluex.liquidbounce.utils.client.toName
import net.ccbluex.liquidbounce.utils.item.getOrNull
import net.ccbluex.liquidbounce.utils.network.packetRegistry
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.Hud
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block

/**
 * Full registry catalog for `REGISTRY_LIST` and `REGISTRY_MUTABLE_LIST` settings across all 10
 * supported `ValueType` registries, matching 043's `RegistryFunctions.kt`.
 */
object RegistryCatalog {

    data class CatalogEntry<E>(
        val value: E,
        val idKey: String,
        val displayName: String,
        val searchLower: String,
        val iconStack: ItemStack? = null,
        val mobEffect: MobEffect? = null,
    ) {
        fun resolvedLabel(): String = when {
            value is ClientModule -> UiLocalization.moduleName(value.name)
            mobEffect != null -> UiLocalization.effectName(displayName)
            else -> displayName
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <E> entriesFor(innerType: ValueType): List<CatalogEntry<E>> {
        val raw: List<CatalogEntry<*>> = when (innerType) {
            ValueType.BLOCK -> {
                BuiltInRegistries.BLOCK.mapNotNull { block: Block ->
                    val id = BuiltInRegistries.BLOCK.getKey(block) ?: return@mapNotNull null
                    val name = block.name.string
                    val stack = block.asItem().let { if (it == Items.AIR) ItemStack.EMPTY else it.defaultInstance }
                    CatalogEntry(
                        value = block,
                        idKey = id.toString(),
                        displayName = name,
                        searchLower = "${name.lowercase()} ${id.path.lowercase()} $id",
                        iconStack = stack.takeIf { !it.isEmpty },
                    )
                }
            }

            ValueType.ITEM -> {
                BuiltInRegistries.ITEM.mapNotNull { item: Item ->
                    if (item == Items.AIR) return@mapNotNull null
                    val id = BuiltInRegistries.ITEM.getKey(item) ?: return@mapNotNull null
                    val name = item.components()[DataComponents.ITEM_NAME]?.string ?: id.toString()
                    CatalogEntry(
                        value = item,
                        idKey = id.toString(),
                        displayName = name,
                        searchLower = "${name.lowercase()} ${id.path.lowercase()} $id",
                        iconStack = item.defaultInstance,
                    )
                }
            }

            ValueType.SOUND_EVENT -> {
                val disc = Items.MUSIC_DISC_13.defaultInstance
                BuiltInRegistries.SOUND_EVENT.mapNotNull { se ->
                    val id = BuiltInRegistries.SOUND_EVENT.getKey(se) ?: return@mapNotNull null
                    val name = se.location.toName()
                    CatalogEntry(
                        value = se,
                        idKey = id.toString(),
                        displayName = name,
                        searchLower = "${name.lowercase()} $id",
                        iconStack = disc,
                    )
                }
            }

            ValueType.MOB_EFFECT -> {
                BuiltInRegistries.MOB_EFFECT.mapNotNull { effect ->
                    val id = BuiltInRegistries.MOB_EFFECT.getKey(effect) ?: return@mapNotNull null
                    val name = effect.displayName.string
                    val zh = UiLocalization.chineseCandidate(name)
                    CatalogEntry(
                        value = effect,
                        idKey = id.toString(),
                        displayName = name,
                        searchLower = "${name.lowercase()} ${zh.lowercase()} ${id.path.lowercase()} $id",
                        mobEffect = effect,
                    )
                }
            }

            ValueType.ENCHANTMENT -> {
                val reg = Registries.ENCHANTMENT.getOrNull()
                val book = Items.ENCHANTED_BOOK.defaultInstance
                reg?.mapNotNull { ench ->
                    val id = reg.getKey(ench) ?: return@mapNotNull null
                    val name = ench.description.string
                    CatalogEntry(
                        value = ench,
                        idKey = id.toString(),
                        displayName = name,
                        searchLower = "${name.lowercase()} ${id.path.lowercase()} $id",
                        iconStack = book,
                    )
                } ?: emptyList()
            }

            ValueType.C2S_PACKET -> {
                (packetRegistry[PacketFlow.SERVERBOUND] ?: emptySet()).map { id ->
                    val name = id.toName()
                    CatalogEntry(
                        value = id,
                        idKey = id.toString(),
                        displayName = name,
                        searchLower = "${name.lowercase()} $id",
                    )
                }
            }

            ValueType.S2C_PACKET -> {
                (packetRegistry[PacketFlow.CLIENTBOUND] ?: emptySet()).map { id ->
                    val name = id.toName()
                    CatalogEntry(
                        value = id,
                        idKey = id.toString(),
                        displayName = name,
                        searchLower = "${name.lowercase()} $id",
                    )
                }
            }

            ValueType.ENTITY_TYPE -> {
                BuiltInRegistries.ENTITY_TYPE.mapNotNull { et ->
                    val id = BuiltInRegistries.ENTITY_TYPE.getKey(et) ?: return@mapNotNull null
                    val name = et.description.string
                    CatalogEntry(
                        value = et,
                        idKey = id.toString(),
                        displayName = name,
                        searchLower = "${name.lowercase()} ${id.path.lowercase()} $id",
                    )
                }
            }

            ValueType.MENU -> {
                val converter = CaseFormat.LOWER_UNDERSCORE.converterTo(CaseFormat.UPPER_CAMEL)
                BuiltInRegistries.MENU.mapNotNull { menu ->
                    val id = BuiltInRegistries.MENU.getKey(menu) ?: return@mapNotNull null
                    val name = converter.convert(id.toName()) ?: id.toString()
                    CatalogEntry(
                        value = menu,
                        idKey = id.toString(),
                        displayName = name,
                        searchLower = "${name.lowercase()} $id",
                    )
                }
            }

            ValueType.CLIENT_MODULE -> {
                ModuleManager.map { mod ->
                    val zh = UiLocalization.chineseCandidate(mod.name)
                    val spaced = UiTextUtils.convertToSpacedString(mod.name)
                    CatalogEntry(
                        value = mod,
                        idKey = mod.name,
                        displayName = mod.name,
                        searchLower = (listOf(mod.name, spaced, zh) + mod.aliases).joinToString(" ").lowercase(),
                    )
                }
            }

            else -> emptyList()
        }
        return raw as List<CatalogEntry<E>>
    }

    fun drawEntryIcon(ctx: GuiGraphicsExtractor, entry: CatalogEntry<*>, x: Float, y: Float, sizePx: Float): Boolean {
        entry.iconStack?.let { stack ->
            if (!stack.isEmpty) {
                val s = sizePx / 16f
                ctx.pose().withPush {
                    translate(x, y)
                    scale(s, s)
                    ctx.item(stack, 0, 0)
                }
                return true
            }
        }
        entry.mobEffect?.let { effect ->
            runCatching {
                val holder = BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect)
                val spriteId = Hud.getMobEffectSprite(holder)
                ctx.pose().withPush {
                    translate(x, y)
                    val s = sizePx / 18f
                    scale(s, s)
                    ctx.blitSprite(RenderPipelines.GUI_TEXTURED, spriteId, 0, 0, 18, 18)
                }
                return true
            }
        }
        return false
    }
}
