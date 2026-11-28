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
 * 4-corner CSS rounded box GUI element supporting independent corner radii, 4-corner color gradients,
 * inner SDF borders / inset spread shadows, and analytical Gaussian outer box-shadows in a single pipeline.
 */
public record CssBoxGuiElementRenderState(
    float x0,
    float y0,
    float x1,
    float y1,
    float radiusTL,
    float radiusTR,
    float radiusBR,
    float radiusBL,
    int colorTL,
    int colorTR,
    int colorBR,
    int colorBL,
    int borderColor,
    float borderWidth,
    int shadowColor,
    float shadowBlur,
    Matrix3x2f pose,
    @Nullable ScreenRectangle scissorArea,
    @Nullable ScreenRectangle bounds
) implements PoseReusableGuiElementRenderState {

    @Override
    public void buildVertices(VertexConsumer vertices) {
        float width = x1 - x0;
        float height = y1 - y0;
        if (width <= 0.0F || height <= 0.0F) {
            return;
        }

        int encW = encodeSize(width);
        int encH = encodeSize(height);
        float maxR = Math.min(width, height) * 0.5F;
        int encRadiiA = (encodeRadius(radiusTL, maxR) << 7) | encodeRadius(radiusTR, maxR);
        int encRadiiB = (encodeRadius(radiusBR, maxR) << 7) | encodeRadius(radiusBL, maxR);

        float halfW = width * 0.5F;
        float halfH = height * 0.5F;

        if (isVisible(shadowColor) && shadowBlur > 0.01F) {
            float pad = shadowBlur * 2.2F;
            emitQuad(
                vertices,
                x0 - pad, y0 - pad, x1 + pad, y1 + pad,
                -halfW - pad, -halfH - pad, halfW + pad, halfH + pad,
                shadowColor, shadowColor, shadowColor, shadowColor,
                encW, encH, encRadiiA, encRadiiB,
                -shadowBlur
            );
        }

        if (isVisible(colorTL) || isVisible(colorTR) || isVisible(colorBR) || isVisible(colorBL)) {
            emitQuad(
                vertices,
                x0, y0, x1, y1,
                -halfW, -halfH, halfW, halfH,
                colorTL, colorTR, colorBR, colorBL,
                encW, encH, encRadiiA, encRadiiB,
                0.0F
            );
        }

        if (isVisible(borderColor) && borderWidth > 0.01F) {
            emitQuad(
                vertices,
                x0, y0, x1, y1,
                -halfW, -halfH, halfW, halfH,
                borderColor, borderColor, borderColor, borderColor,
                encW, encH, encRadiiA, encRadiiB,
                borderWidth
            );
        }
    }

    private void emitQuad(
        VertexConsumer vertices,
        float vx0, float vy0, float vx1, float vy1,
        float lx0, float ly0, float lx1, float ly1,
        int cTL, int cTR, int cBR, int cBL,
        int encW, int encH, int encRadiiA, int encRadiiB,
        float mode
    ) {
        vertices.addVertexWith2DPose(pose, vx0, vy0)
            .setUv(lx0, ly0)
            .setColor(cTL)
            .setUv1(encW, encH)
            .setUv2(encRadiiA, encRadiiB)
            .setLineWidth(mode);
        vertices.addVertexWith2DPose(pose, vx0, vy1)
            .setUv(lx0, ly1)
            .setColor(cBL)
            .setUv1(encW, encH)
            .setUv2(encRadiiA, encRadiiB)
            .setLineWidth(mode);
        vertices.addVertexWith2DPose(pose, vx1, vy1)
            .setUv(lx1, ly1)
            .setColor(cBR)
            .setUv1(encW, encH)
            .setUv2(encRadiiA, encRadiiB)
            .setLineWidth(mode);
        vertices.addVertexWith2DPose(pose, vx1, vy0)
            .setUv(lx1, ly0)
            .setColor(cTR)
            .setUv1(encW, encH)
            .setUv2(encRadiiA, encRadiiB)
            .setLineWidth(mode);
    }

    @Override
    public RenderPipeline pipeline() {
        return ClientRenderPipelines.GUI.cssBox();
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    private static int encodeSize(float size) {
        return Math.clamp(Math.round(size * 16.0F), 1, 32767);
    }

    private static int encodeRadius(float radius, float maxRadius) {
        float clamped = Math.clamp(radius, 0.0F, Math.min(maxRadius, 63.5F));
        return Math.clamp(Math.round(clamped * 2.0F), 0, 127);
    }

    private static boolean isVisible(int argb) {
        return (argb >>> 24) != 0;
    }
}
