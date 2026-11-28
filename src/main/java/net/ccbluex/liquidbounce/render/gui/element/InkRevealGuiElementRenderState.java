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

package net.ccbluex.liquidbounce.render.gui.element;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.ccbluex.liquidbounce.render.ClientRenderPipelines;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/**
 * Dual-layer crossing-free SDF + 16-bit arc-time calligraphic stroke reveal quad.
 *
 * Evaluates exact union coverage {@code max(cov0, cov1)} with a circular pen-nib cap
 * in a single pass so crossings and joins never double-blend or bulge.
 */
public record InkRevealGuiElementRenderState(
    float x0,
    float y0,
    float x1,
    float y1,
    float u0,
    float v0,
    float u1,
    float v1,
    float progress,
    int totalArcPx,
    float nominalRadius,
    int colorMode,
    int argb,
    TextureSetup textureSetup,
    Matrix3x2f pose,
    @Nullable ScreenRectangle scissorArea,
    @Nullable ScreenRectangle bounds
) implements PoseReusableGuiElementRenderState {

    @Override
    public void buildVertices(VertexConsumer vertices) {
        if (x1 <= x0 || y1 <= y0 || (argb >>> 24) == 0 || progress <= 0.0F) {
            return;
        }

        int progressFixed = Math.clamp(Math.round(Math.clamp(progress, 0.0F, 1.0F) * 32767.0F), 0, 32767);
        if (progressFixed == 0) {
            return;
        }
        int arcClamped = Math.clamp(totalArcPx, 1, 32767);
        float r = Math.max(nominalRadius, 0.25F);

        vertices.addVertexWith2DPose(pose, x0, y0)
            .setUv(u0, v0)
            .setColor(argb)
            .setUv1(progressFixed, arcClamped)
            .setUv2(colorMode, 0)
            .setLineWidth(r);
        vertices.addVertexWith2DPose(pose, x0, y1)
            .setUv(u0, v1)
            .setColor(argb)
            .setUv1(progressFixed, arcClamped)
            .setUv2(colorMode, 0)
            .setLineWidth(r);
        vertices.addVertexWith2DPose(pose, x1, y1)
            .setUv(u1, v1)
            .setColor(argb)
            .setUv1(progressFixed, arcClamped)
            .setUv2(colorMode, 0)
            .setLineWidth(r);
        vertices.addVertexWith2DPose(pose, x1, y0)
            .setUv(u1, v0)
            .setColor(argb)
            .setUv1(progressFixed, arcClamped)
            .setUv2(colorMode, 0)
            .setLineWidth(r);
    }

    @Override
    public RenderPipeline pipeline() {
        return ClientRenderPipelines.GUI.inkReveal();
    }
}
