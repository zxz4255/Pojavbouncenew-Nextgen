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
 * Rounded-corner textured GUI quad supporting both full textures (0..1) and 64x64 player skin sub-regions.
 */
public record RoundedTexGuiElementRenderState(
    float x0,
    float y0,
    float x1,
    float y1,
    float u0,
    float v0,
    float u1,
    float v1,
    float radius,
    int argb,
    TextureSetup textureSetup,
    Matrix3x2f pose,
    @Nullable ScreenRectangle scissorArea,
    @Nullable ScreenRectangle bounds
) implements PoseReusableGuiElementRenderState {

    @Override
    public void buildVertices(VertexConsumer vertices) {
        float width = x1 - x0;
        float height = y1 - y0;
        if (width <= 0.0F || height <= 0.0F || (argb >>> 24) == 0) {
            return;
        }

        int encW = Math.clamp(Math.round(width * 16.0F), 1, 32767);
        int encH = Math.clamp(Math.round(height * 16.0F), 1, 32767);
        int encUvMin = (encodeUv64(u0) << 7) | encodeUv64(v0);
        int encUvMax = (encodeUv64(u1) << 7) | encodeUv64(v1);
        float halfW = width * 0.5F;
        float halfH = height * 0.5F;
        float r = Math.clamp(radius, 0.0F, Math.min(halfW, halfH));

        vertices.addVertexWith2DPose(pose, x0, y0)
            .setUv(-halfW, -halfH)
            .setColor(argb)
            .setUv1(encW, encH)
            .setUv2(encUvMin, encUvMax)
            .setLineWidth(r);
        vertices.addVertexWith2DPose(pose, x0, y1)
            .setUv(-halfW, halfH)
            .setColor(argb)
            .setUv1(encW, encH)
            .setUv2(encUvMin, encUvMax)
            .setLineWidth(r);
        vertices.addVertexWith2DPose(pose, x1, y1)
            .setUv(halfW, halfH)
            .setColor(argb)
            .setUv1(encW, encH)
            .setUv2(encUvMin, encUvMax)
            .setLineWidth(r);
        vertices.addVertexWith2DPose(pose, x1, y0)
            .setUv(halfW, -halfH)
            .setColor(argb)
            .setUv1(encW, encH)
            .setUv2(encUvMin, encUvMax)
            .setLineWidth(r);
    }

    @Override
    public RenderPipeline pipeline() {
        return ClientRenderPipelines.GUI.roundedTex();
    }

    private static int encodeUv64(float uv) {
        return Math.clamp(Math.round(uv * 64.0F), 0, 127);
    }
}
