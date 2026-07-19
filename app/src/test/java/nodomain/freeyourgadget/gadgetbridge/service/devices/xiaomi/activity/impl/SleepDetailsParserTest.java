/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepTimeSample;

public class SleepDetailsParserTest {
    private static final int BED_TIME = 1_700_000_000;
    private static final int WAKEUP_TIME = BED_TIME + 375 * 60;

    @Test
    public void headerSizes_coverExistingVersionsAndV6() {
        assertEquals(1, SleepDetailsParser.headerSizeForVersion(1));
        assertEquals(1, SleepDetailsParser.headerSizeForVersion(4));
        assertEquals(2, SleepDetailsParser.headerSizeForVersion(5));
        assertEquals(3, SleepDetailsParser.headerSizeForVersion(6));
        assertEquals(-1, SleepDetailsParser.headerSizeForVersion(7));
    }

    @Test
    public void v6ControlPackets_arePayloadless() {
        assertTrue(SleepDetailsParser.isPayloadlessPacketType(0x12));
        assertTrue(SleepDetailsParser.isPayloadlessPacketType(0x13));

        assertFalse(SleepDetailsParser.isPayloadlessPacketType(0x10));
        assertFalse(SleepDetailsParser.isPayloadlessPacketType(0x11));
        assertFalse(SleepDetailsParser.isPayloadlessPacketType(0x14));
    }

    @Test
    public void v6AdditionalData_decodesHrvSummaryAndSparseSeries() {
        final byte[] validity = {(byte) 0xdf, (byte) 0xff, (byte) 0xf4};
        final ByteBuffer buf = ByteBuffer.allocate(41 + 8 + 8 + 12)
                .order(ByteOrder.LITTLE_ENDIAN);

        buf.put((byte) 90);                 // synthetic sleep efficiency
        buf.putInt(420);                    // synthetic asleep duration, seconds
        buf.putInt(22_500);                 // synthetic in-bed duration, seconds
        buf.putInt(BED_TIME - 420);         // go-to-bed
        buf.putInt(WAKEUP_TIME);            // leave-bed
        buf.putShort((short) 55);            // synthetic HRV average
        buf.putShort((short) 15);            // standard deviation
        buf.putShort((short) 54);            // median
        buf.putShort((short) 40);            // lower percentile
        buf.putShort((short) 70);            // upper percentile
        buf.putShort((short) 55);            // middle percentile
        buf.putInt(WAKEUP_TIME - 180);       // summary timestamp
        buf.putShort((short) 85);            // max
        buf.putShort((short) 30);            // min
        buf.putShort((short) 68);            // baseline max
        buf.putShort((short) 42);            // baseline min

        buf.putShort((short) 60);            // heart-rate interval
        buf.putShort((short) 0);             // no heart-rate values

        buf.putShort((short) 600);           // HRV interval
        buf.putShort((short) 6);             // six slots
        buf.putInt(BED_TIME);
        buf.putShort((short) 64);
        buf.putShort((short) 0);             // missing estimate
        buf.putShort((short) 49);
        buf.putShort((short) 0);             // missing estimate
        buf.putShort((short) 58);
        buf.putShort((short) 41);
        buf.flip();

        final SleepDetailsParser.V6AdditionalData data =
                SleepDetailsParser.decodeV6AdditionalData(buf, validity);

        assertEquals(90, data.sleepEfficiency);
        assertEquals(420L, data.entrySleepDuration);
        assertEquals(22_500L, data.inBedDuration);
        assertEquals(55, data.hrvAverage);
        assertEquals(85, data.hrvMax);
        assertEquals(30, data.hrvMin);
        assertEquals(4, data.hrvPoints.size());
        assertEquals(BED_TIME * 1000L, data.hrvPoints.get(0).timestamp);
        assertEquals(64, data.hrvPoints.get(0).value);
        assertEquals((BED_TIME + 2 * 600L) * 1000L, data.hrvPoints.get(1).timestamp);
        assertEquals(49, data.hrvPoints.get(1).value);
        assertEquals(0, buf.remaining());
    }

    @Test(expected = IllegalArgumentException.class)
    public void v6AdditionalData_rejectsTruncatedHrvSeries() {
        final byte[] validity = {(byte) 0xdf, (byte) 0xff, (byte) 0xf4};
        final ByteBuffer buf = ByteBuffer.allocate(41 + 4 + 8)
                .order(ByteOrder.LITTLE_ENDIAN);

        buf.position(41);
        buf.putShort((short) 60);            // empty heart-rate series
        buf.putShort((short) 0);
        buf.putShort((short) 600);           // HRV interval
        buf.putShort((short) 4);             // claims four values
        buf.putInt(BED_TIME);                // but none follow
        buf.flip();

        SleepDetailsParser.decodeV6AdditionalData(buf, validity);
    }

    @Test
    public void v6SummaryPacket_decodesKnownDurations() {
        // Synthetic payload matching the observed v6 type-0x10 layout:
        // index/wake count, sleep, awake, light, REM, deep, flags, unknown.
        final ByteBuffer buf = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0x12); // sleep index 1, two wake periods
        buf.putShort((short) 360);
        buf.putShort((short) 15);
        buf.putShort((short) 210);
        buf.putShort((short) 90);
        buf.putShort((short) 60);
        buf.put((byte) 0x16);
        buf.put((byte) 0);

        final XiaomiSleepTimeSample sample = SleepDetailsParser.decodeSummaryPacket(
                buf.array(),
                BED_TIME,
                WAKEUP_TIME,
                null
        );

        assertEquals(BED_TIME * 1000L, sample.getTimestamp());
        assertEquals(Long.valueOf(WAKEUP_TIME * 1000L), sample.getWakeupTime());
        assertEquals(Integer.valueOf(360), sample.getTotalDuration());
        assertEquals(Integer.valueOf(15), sample.getAwakeDuration());
        assertEquals(Integer.valueOf(210), sample.getLightSleepDuration());
        assertEquals(Integer.valueOf(90), sample.getRemSleepDuration());
        assertEquals(Integer.valueOf(60), sample.getDeepSleepDuration());
    }

    @Test
    public void v6StagePacket_decodesStagesAndOffsets() {
        final ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) ((1 << 12) | 210)); // light
        buf.putShort((short) ((2 << 12) | 60));  // deep
        buf.putShort((short) ((3 << 12) | 90));  // REM
        buf.putShort((short) 15);                // awake

        final List<XiaomiSleepStageSample> stages = SleepDetailsParser.decodeStagePacket(
                buf.array(),
                BED_TIME
        );

        assertEquals(4, stages.size());

        assertStage(stages.get(0), BED_TIME, 3);
        assertStage(stages.get(1), BED_TIME + 210 * 60, 2);
        assertStage(stages.get(2), BED_TIME + (210 + 60) * 60, 4);
        assertStage(stages.get(3), BED_TIME + (210 + 60 + 90) * 60, 5);

        final int totalMinutes = 210 + 60 + 90 + 15;
        assertEquals(WAKEUP_TIME, BED_TIME + totalMinutes * 60);
    }

    private static void assertStage(final XiaomiSleepStageSample sample,
                                    final int expectedTimestampSeconds,
                                    final int expectedStage) {
        assertEquals(expectedTimestampSeconds * 1000L, sample.getTimestamp());
        assertEquals(Integer.valueOf(expectedStage), sample.getStage());
    }
}
