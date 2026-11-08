package com.samsara.ui.hud.target;

import java.util.Locale;
import com.samsara.ui.clickgui.opai.OpaiStyle;

/** Samsara Hud.TargetHud Opai health and painter extracted for LB adapter. GPL-3.0. */
public final class OpaiTargetPort {
    private OpaiTargetPort() {}
      public static final class OpaiTargetHudHealth {
         private float goal;
         private float maximum = 20;
         private float fillFrom;
         private float trailFrom;
         private long changedAt;
         private boolean damage;
         private boolean initialized;

         public record Sample(float health, float trail, float maximum) {
            public float fraction() { return health / maximum; }
            public float trailFraction() { return trail / maximum; }
            public String label() { return format(health); }
         }

         public void reset(float health, float maximum, long now) {
            this.maximum = maximum(maximum);
            this.goal = health(health, this.maximum);
            this.fillFrom = this.trailFrom = this.goal;
            this.changedAt = now;
            this.damage = false;
            this.initialized = true;
         }

         public Sample update(float health, float maximum, long now) {
            float max = maximum(maximum);
            float value = health(health, max);
            if (!this.initialized) reset(value, max, now);
            if (value != this.goal || max != this.maximum) {
               Sample current = sample(now);
               this.damage = value < current.health();
               this.fillFrom = current.health();
               this.trailFrom = Math.max(current.health(), current.trail());
               this.goal = value;
               this.maximum = max;
               this.changedAt = now;
            }
            return sample(now);
         }

         public Sample sample(long now) {
            double elapsed = Math.max(0, now - this.changedAt);
            float fill = response(this.fillFrom, this.goal, elapsed, 65);
            float trail = this.damage
               ? Math.max(fill, response(this.trailFrom, this.goal, Math.max(0, elapsed - 100), 220)) : fill;
            return new Sample(health(fill, this.maximum), health(trail, this.maximum), this.maximum);
         }

         private static float response(float from, float to, double elapsed, double decay) {
            float value = (float)(to + (from - to) * Math.exp(-elapsed / decay));
            return Math.abs(value - to) < .025f ? to : value;
         }

         private static float maximum(float value) {
            return Float.isFinite(value) ? Math.max(1, value) : 20;
         }

         private static float health(float value, float maximum) {
            return Float.isFinite(value) ? Math.clamp(value, 0, maximum) : 0;
         }

         public static String format(float health) {
            float rounded = Math.round(health * 10) / 10f;
            return rounded == Math.round(rounded) ? Integer.toString(Math.round(rounded))
               : String.format(Locale.ROOT, "%.1f", rounded);
         }
      }

      public static final class OpaiTargetHudPainter {
         public static final int WIDTH = 122;
         public static final int HEIGHT = 40;
         public static final int RADIUS = 6;
         public static final int SHADOW_MARGIN = 8;
         public static final int TEXTURE_SCALE = 4;
         public static final float FACE_SIZE = 26;
         public static final float FACE_RADIUS = 4.5f;
         public static final float TEXT_SIZE = 8;
         public static final String PANEL_RESOURCE = "textures/hud/target-opai.png";
         public static final String BAR_RESOURCE = "textures/hud/target-opai-bar.png";

         public record Bounds(int x, int y, int width, int height) {
            public boolean contains(double x, double y) {
               return x >= this.x && x < this.x + this.width && y >= this.y && y < this.y + this.height;
            }
         }

         public interface Surface {
            float measure(String text);
            void panel(Bounds bounds);
            void face(float x, float y, float size, float radius);
            void armor(int slot, float x, float y);
            void text(String text, float x, float y, int color);
            void bar(float x, float y, float width, float height, int color);
         }

         private OpaiTargetHudPainter() { }

         public static Bounds bounds(Surface surface, String name, float maximum, int viewportWidth,
                                     int viewportHeight, int offsetX, int offsetY) {
            // Reserve a decimal so changes in the health label do not resize the shell.
            float labelWidth = labelWidth(surface, maximum);
            int width = Math.min(Math.max(WIDTH, (int)Math.ceil(35 + surface.measure(name) + labelWidth + 4)),
               Math.max(1, viewportWidth - 4));
            int x = Math.clamp(viewportWidth / 2 + offsetX, 2, Math.max(2, viewportWidth - width - 2));
            int y = Math.clamp(viewportHeight / 2 + offsetY, 2, Math.max(2, viewportHeight - HEIGHT - 2));
            return new Bounds(x, y, width, HEIGHT);
         }

         public static void paint(Surface surface, Bounds bounds, String name, OpaiTargetHudHealth.Sample health,
                                  boolean armor, OpaiStyle.Palette palette) {
            float x = bounds.x(), y = bounds.y();
            surface.panel(bounds);
            surface.face(x + 3, y + 3, FACE_SIZE, FACE_RADIUS);
            String label = health.label();
            String fittedName = fit(surface, name, bounds.width() - 35 - labelWidth(surface, health.maximum()) - 3);
            surface.text(fittedName, x + 32, y + 5, 0xFFFFFFFF);
            surface.text(label, x + 32 + surface.measure(fittedName) + 1.5f, y + 5, palette.accent());
            if (armor) {
               for (int slot = 0; slot < 4; slot++) surface.armor(slot, x + 31.5f + slot * 15.5f, y + 14.5f);
            }
            float barWidth = Math.max(0, bounds.width() - 7);
            surface.bar(x + 3, y + 32, barWidth, 5, 0x66000000);
            // Draw only the remaining damage interval: the theme fill covers the front of it.
            surface.bar(x + 3, y + 31.25f, barWidth * health.trailFraction(), 4.5f, 0x66FFFFFF);
            surface.bar(x + 3, y + 31.25f, barWidth * health.fraction(), 4.5f, palette.accent());
         }

         private static String fit(Surface surface, String text, float width) {
            if (surface.measure(text) <= width) return text;
            int end = text.length();
            while (end > 0 && surface.measure(text.substring(0, end) + "…") > width)
               end = text.offsetByCodePoints(end, -1);
            return end == 0 ? "" : text.substring(0, end) + "…";
         }

         private static float labelWidth(Surface surface, float maximum) {
            String label = OpaiTargetHudHealth.format(maximum);
            return Math.max(surface.measure(label.contains(".") ? label : label + ".0"), surface.measure("00.0"));
         }
      }

}
