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
 * Fused Liquid Glass GUI element (`blur + physical bezel refraction + frosted glass + inner bevel +
 * directional Fresnel specular rim + caustic elevation shadow`) for Native ClickGUI Glass Mode.
 */
public record ClickGuiGlassGuiElementRenderState(
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
    float layerOpacity,
    float refractionStrength,
    int rimColor,
    float rimWidth,
    int shadowColor,
    float shadowSpread,
    boolean lightPollutionGlass,
    int referenceProfile,
    TextureSetup textureSetup,
    Matrix3x2f pose,
    @Nullable ScreenRectangle scissorArea,
    @Nullable ScreenRectangle bounds
) implements PoseReusableGuiElementRenderState {

    @Override
    public void buildVertices(VertexConsumer vertices) {
        float width = x1 - x0;
        float height = y1 - y0;
        if (width <= 0.0F || height <= 0.0F || layerOpacity <= 0.004F) {
            return;
        }

        int encW = encodeSize(width);
        int encH = encodeSize(height);
        float maxR = Math.min(width, height) * 0.5F;
        int encRadiiA = (encodeRadius(radiusTL, maxR) << 7) | encodeRadius(radiusTR, maxR);
        int encRadiiB = (encodeRadius(radiusBR, maxR) << 7) | encodeRadius(radiusBL, maxR);

        float halfW = width * 0.5F;
        float halfH = height * 0.5F;

        // 089: 256-spaced material lanes only for the opt-in reference shader.
        // Old Liquid Glass always keeps its exact historical transport and vertex count.
        int profile = lightPollutionGlass ? Math.clamp(referenceProfile, 0, 3) : 0;
        float lane = profile * 256.0F;

        // 1. Fused dual-layer elevation shadow + subtle backdrop caustic transmission (vMode < 0.0)
        if (isVisible(shadowColor) && shadowSpread > 0.05F) {
            float pad = shadowSpread * 2.5F + 4.0F;
            emitQuad(
                vertices,
                x0 - pad, y0 - pad, x1 + pad, y1 + pad,
                -halfW - pad, -halfH - pad, halfW + pad, halfH + pad,
                shadowColor, shadowColor, shadowColor, shadowColor,
                encW, encH, encRadiiA, encRadiiB,
                -(shadowSpread + lane)
            );
        }

        // 2. Fused blurred backdrop + curved bezel refraction + frosted tint + inner bevel (vMode in 1..65)
        int opacityBucket = Math.clamp(Math.round(layerOpacity * 64.0F), 1, 64);
        float refractFrac = Math.clamp(refractionStrength * 0.25F, 0.0F, 0.95F);
        float bodyMode = (float) opacityBucket + refractFrac + lane;
        emitQuad(
            vertices,
            x0, y0, x1, y1,
            -halfW, -halfH, halfW, halfH,
            colorTL, colorTR, colorBR, colorBL,
            encW, encH, encRadiiA, encRadiiB,
            bodyMode
        );

        // 3. Directional Fresnel specular rim + corner glint (vMode >= 100.0)
        if (isVisible(rimColor) && rimWidth > 0.05F) {
            emitQuad(
                vertices,
                x0, y0, x1, y1,
                -halfW, -halfH, halfW, halfH,
                rimColor, rimColor, rimColor, rimColor,
                encW, encH, encRadiiA, encRadiiB,
                100.0F + rimWidth + lane
            );
        }
        // 4. Detached lower-right quarter-arc: not clipped to the body's quad and not
        // dependent on a nonzero shadow. Only the two references that show it opt in.
        if ((profile == 1 || profile == 2) && isVisible(rimColor)) {
            float pad = Math.max(height * 0.20F, 3.0F);
            emitQuad(vertices, x0 - pad, y0 - pad, x1 + pad, y1 + pad,
                -halfW - pad, -halfH - pad, halfW + pad, halfH + pad,
                rimColor, rimColor, rimColor, rimColor,
                encW, encH, encRadiiA, encRadiiB, lane + 192.0F);
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
        return lightPollutionGlass ? ClientRenderPipelines.GUI.lightPollutionGlass()
            : ClientRenderPipelines.GUI.clickGuiLiquidGlass();
    }

    private static int encodeSize(float size) {
        return Math.clamp(Math.round(size * 8.0F), 1, 32767);
    }

    private static int encodeRadius(float radius, float maxRadius) {
        float clamped = Math.clamp(radius, 0.0F, Math.min(maxRadius, 63.5F));
        return Math.clamp(Math.round(clamped * 2.0F), 0, 127);
    }

    private static boolean isVisible(int argb) {
        return (argb >>> 24) != 0;
    }
}
