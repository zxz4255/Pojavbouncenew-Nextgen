/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce).
 * Copyright (C) 2015-2026 CCBlueX and contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package net.ccbluex.liquidbounce.features.module.modules.combat.moonlight;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * MoonLight-style blink queue with explicit end-of-motion markers. The progress counter
 * tracks movement packets actually in the queue. A modern client can send zero or multiple
 * movement packets in one tick (e.g. during correction/knockback); counting POST callbacks
 * and subtracting every packet could produce negative or phantom progress after a hit.
 */
public final class MoonlightPacketQueue<T> {
    private static final class Entry<T> {
        final T packet; // null denotes MoonLight's end-of-tick marker
        final boolean movement;
        Entry(T packet, boolean movement) { this.packet = packet; this.movement = movement; }
    }

    private final ArrayDeque<Entry<T>> packets = new ArrayDeque<>();
    private int movingPackets;

    public synchronized void add(T packet) {
        packets.addLast(new Entry<>(Objects.requireNonNull(packet), false));
    }

    public synchronized void addMovement(T packet) {
        packets.addLast(new Entry<>(Objects.requireNonNull(packet), true));
        movingPackets++;
    }

    public synchronized void postMotion() {
        packets.addLast(new Entry<>(null, false));
    }

    public synchronized int movingPackets() {
        return movingPackets;
    }

    /** Release up to and including the first tick marker, in order. */
    public synchronized List<T> drainOneTick() {
        List<T> result = new ArrayList<>();
        Entry<T> entry;
        while ((entry = packets.pollFirst()) != null) {
            if (entry.packet == null) break;
            if (entry.movement) movingPackets--;
            result.add(entry.packet);
        }
        return result;
    }

    /** Release everything except packets that MoonLight drops on a full release. */
    public synchronized List<T> drainAll(Predicate<? super T> dropOnRelease) {
        List<T> result = new ArrayList<>();
        Entry<T> entry;
        while ((entry = packets.pollFirst()) != null) {
            if (entry.packet != null && !dropOnRelease.test(entry.packet)) result.add(entry.packet);
        }
        movingPackets = 0;
        return result;
    }

    public synchronized void reset() {
        packets.clear();
        movingPackets = 0;
    }
}
