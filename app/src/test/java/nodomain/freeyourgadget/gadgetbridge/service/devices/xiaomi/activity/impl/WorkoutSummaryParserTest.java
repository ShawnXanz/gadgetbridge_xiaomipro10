/*  Copyright (C) 2026 Dany Mestas

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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.Date;

import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileId;
import nodomain.freeyourgadget.gadgetbridge.util.CheckSums;

/**
 * Tests for {@link WorkoutSummaryParser} using captured device data. Each base64 blob
 * is the verbatim contents of a SUMMARY .bin file pulled from a real workout, so the
 * tests pin the parser against actual byte layouts rather than fabricated examples.
 */
public class WorkoutSummaryParserTest {

    /** Treadmill v11, 24 Apr 2026, 4.48 km run (calibrated to 4.5 km), goal 4 km. */
    private static final String TREADMILL_V11_24APR =
            "ErHraQgLjQD7/+/vkf/AAD8SsetpMLjraRgHAAB/EQAAiQCVAQAAWAEAABMDAAAnEwAAWACj"
                    + "AKgAp7NeZmaGQAAAAAAoAOkBAADHBAAAPQAAAA4AAAAQAAAAugAXBwAAZmbmPwADAAAA"
                    + "AAAAAAAAoA8AAAAAAAAAAAAAAACUEQAAaAADAAAAAAAAAAAAAAAAAAAMAAAAAAAAAAAA"
                    + "AAAAAAAAAAAAAAAAAAAAAAAAAADyuEua";

    /** Treadmill v9, 21 Jun 2026 — 192 cal, 135 avg HR, 28 training load, +15 vitality. */
    private static final String TREADMILL_V9_21JUN =
            "WCA4aggJjQD//H+QDiBYIDhqhyY4aigGAACgCgAAwAByAQAAlAUAAJAMAAC0AIelZDMz8z8C"
                    + "AAAAIAAAAAAAcAAAAPwCAAB0AQAAOgEAAOgAKAYAAAAAAAABAwAAAFgCAAAsAYgTAABK"
                    + "AQAAAADIQQAAoAoAABwAAQAAAAAAAA8AAAAAAAAAAAAAAAAAAwAAh6VkA1B6ALQAhdLG"
                    + "QAisHEFYIDhqhyY4agAAAAAoBgAAKAYAAAAAAAAAAAAAwAAAAKAKAACQDAAAQwIAAHIB"
                    + "AAAAAAAAAAAAAAAAAAADAAAAAAAAAAAAAAAAAAAAAAAAAHAAAAD8AgAAdAEAADoBAAAA"
                    + "AAIBMzPzPwAAAAAAAAAADwAcACAAAAAAAAAAAAAAAAAAAAAAAaiMNkSR";

    /** Rowing v7, 20 Apr 2026 — 0.4 anaerobic effect, 71 max stroke rate. */
    private static final String ROWING_V7_20APR =
            "+l3maQgHtQD/v/N4//pd5mnwZ+Zp9QkAANYAjqRcmplZQAAAFwAAAAAAoQIAAGoFAACwAAA"
                    + "AFAEAABsBuAQAABwAAABHAAAAAAAAAAAAAABnAQAABfUJAADNzMw+AAAAAAAAAAAAAABF"
                    + "AAIAAJGg1DA=";

    /** Rowing v7, 15 Apr 2026 — 1.1 anaerobic effect. */
    private static final String ROWING_V7_15APR =
            "8srfaQgHtQD/v/N4//LK32nQ1N9p3QkAANQAkapfAABgQAAAHAAAAAAAGwUAAKwCAAD+AAA"
                    + "AAwEAABkBTAQAABoAAAApAAAAAAAAAAAAAAAAAAAAAd0JAADNzIw/AAAAAAAAAAAAAABN"
                    + "AAMAAAusxH0=";

    /** Freestyle v10, 17 Mar 2026 — frisbee workout, 105/93/7 throws low/medium/high. */
    private static final String FREESTYLE_V10_FRISBEE =
            "HIK5aQQKoQD+Bv7/wHccgrlpcJO5aVMRAABwAZG8WgAAAAAAAAAAgEAAACMAWgEAAEIEAAA"
                    + "FBwAAtAMAANUAAADoAVMRAABmZiZAACcDAAAAAAAAAACIAAMAAAAAAAAAAAAAAAAAAAAA"
                    + "aQBdAAcAAEblwNo=";

    private static ActivitySummaryData parse(final String base64) {
        final byte[] bytes = Base64.getDecoder().decode(base64);
        final BaseActivitySummary summary = new BaseActivitySummary();
        summary.setRawSummaryData(bytes);
        new WorkoutSummaryParser().parseBinaryData(summary, true);
        final String json = summary.getSummaryData();
        assertNotNull("parser should populate summaryData", json);
        return ActivitySummaryData.fromJson(json);
    }

    private static double num(final ActivitySummaryData data, final String key) {
        final Number n = data.getNumber(key, null);
        assertNotNull("missing entry: " + key, n);
        return n.doubleValue();
    }

    private static byte[] buildAnonymizedTreadmillV13() {
        final ByteBuffer buf = ByteBuffer.allocate(245).order(ByteOrder.LITTLE_ENDIAN);
        final XiaomiActivityFileId fileId = new XiaomiActivityFileId(
                new Date(1700000000000L), 0, 1, 3, 1, 13);
        buf.put(fileId.toBytes());
        buf.put((byte) 0);
        buf.put(new byte[]{
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff
        });

        buf.putInt(1700000000); // start
        buf.putInt(1700002600); // end
        buf.putInt(2600);       // elapsed
        buf.putInt(3150);       // distance
        buf.putShort((short) 340);
        buf.putInt(819);        // average pace
        buf.putInt(424);        // best pace
        buf.putInt(1113);       // worst pace
        buf.putInt(4526);
        buf.putShort((short) 68);
        buf.putShort((short) 107);
        buf.putShort((short) 163);
        buf.put((byte) 129);
        buf.put((byte) 162);
        buf.put((byte) 101);
        buf.putFloat(2.5f);
        buf.put((byte) 3);      // aerobic effect level
        buf.put((byte) 0);      // VO2 max
        buf.put((byte) 0);      // VO2 max level
        buf.put((byte) 0);      // body energy consumption
        buf.putShort((short) 14);
        buf.putInt(0);
        buf.putInt(154);
        buf.putInt(1250);
        buf.putInt(767);
        buf.putInt(406);
        buf.put(new byte[]{(byte) 170, (byte) 153, (byte) 136, 119, 102});
        buf.put(new byte[20]);  // pace zones
        buf.putShort((short) 403);
        buf.putInt(2577);       // effective duration
        buf.putFloat(0f);
        buf.put((byte) 0);
        buf.putFloat(0f);       // peak training effect
        buf.put((byte) 0);
        buf.putShort((short) 3);// indoor-running sub-sport type
        buf.put((byte) 0);      // selected course; cloudCourseId omitted
        buf.put((byte) 0);      // HR zone method
        buf.putInt(0);          // target duration
        buf.putShort((short) 0);
        buf.putInt(0);          // target distance
        buf.putInt(0);          // target pace
        buf.putFloat(0f);       // target speed
        buf.putShort((short) 0);// target cadence
        buf.putInt(0);          // repaired distance
        buf.putShort((short) 45);
        buf.put((byte) 1);      // load level
        buf.putFloat(0f);       // running power index
        buf.put((byte) 0);      // running power level
        buf.put((byte) 0);      // training status
        buf.put(new byte[8]);   // predicted race times
        buf.put((byte) 10);     // vitality
        buf.put(new byte[12]);  // landing durations
        buf.put(new byte[4]);   // landing type / percentages
        buf.put((byte) 0);      // average impact
        buf.put((byte) 0);      // maximum impact
        buf.putShort((short) 531);
        buf.putShort((short) 278);
        buf.putShort((short) 0);// average flight time
        buf.putShort((short) 0);// maximum flight time
        buf.put((byte) 0);      // average contact ratio
        buf.put((byte) 0);      // minimum contact ratio
        buf.putShort((short) 69);
        buf.putShort((short) 48);
        buf.putShort((short) 118);
        buf.putShort((short) 48);
        buf.putShort((short) 31);
        buf.putShort((short) 91);
        buf.putShort((short) 618);
        buf.put((byte) 0);      // training feeling
        buf.putShort((short) 0);// TSS
        buf.putShort((short) 0);// average power
        buf.putShort((short) 0);// maximum power
        buf.putLong(0L);
        buf.putInt(0);
        assertEquals("v13 body must end immediately before CRC", 241, buf.position());

        final byte[] arr = buf.array();
        buf.putInt(CheckSums.getCRC32(arr, 0, arr.length - 4));
        return arr;
    }

    private static byte[] addCloudCourseId(final byte[] base) {
        // selectedCourse is body byte 116 (absolute byte 136). A course id follows it
        // only for selectedCourse 252/253/255.
        final byte[] extended = new byte[base.length + 8];
        System.arraycopy(base, 0, extended, 0, 137);
        extended[136] = (byte) 252;
        ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN).putLong(137, 123456L);
        System.arraycopy(base, 137, extended, 145, base.length - 137 - 4);
        ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(extended.length - 4, CheckSums.getCRC32(extended, 0, extended.length - 4));
        return extended;
    }

    @Test
    public void treadmillV11_extractsRecoveryDistanceGoalCalibratedLoadVitality() {
        final ActivitySummaryData data = parse(TREADMILL_V11_24APR);

        // Pre-existing fields (baseline sanity check)
        assertEquals(4479d, num(data, ActivitySummaryEntries.DISTANCE_METERS), 0.001);
        assertEquals(4.2d, num(data, ActivitySummaryEntries.TRAINING_EFFECT_AEROBIC), 0.01);
        assertEquals(167d, num(data, ActivitySummaryEntries.HR_AVG), 0.001);
        assertEquals(163d, num(data, ActivitySummaryEntries.CADENCE_AVG), 0.001);

        // New trailing-zone fields verified against the device UI.
        // The float at the prior "recoveryValue" offset is the anaerobic training effect.
        assertEquals(1.8d, num(data, ActivitySummaryEntries.TRAINING_EFFECT_ANAEROBIC), 0.01);
        assertEquals(4000d, num(data, ActivitySummaryEntries.DISTANCE_GOAL), 0.001);
        assertEquals(4500d, num(data, ActivitySummaryEntries.DISTANCE_METERS_CALIBRATED), 0.001);
        assertEquals(104d, num(data, ActivitySummaryEntries.WORKOUT_LOAD), 0.001);
        assertEquals(12d, num(data, ActivitySummaryEntries.VITALITY_GAIN), 0.001);
    }

    @Test
    public void treadmillV13_extractsReportAndRunningDynamics() {
        final BaseActivitySummary summary = new BaseActivitySummary();
        summary.setStartTime(new Date(1700000000000L));
        summary.setRawSummaryData(buildAnonymizedTreadmillV13());
        new WorkoutSummaryParser().parseBinaryData(summary, true);
        final ActivitySummaryData data = ActivitySummaryData.fromJson(summary.getSummaryData());

        assertEquals(ActivityKind.TREADMILL.getCode(), summary.getActivityKind());
        assertEquals(2577_000L, summary.getEndTime().getTime() - summary.getStartTime().getTime());
        assertEquals(2577d, num(data, ActivitySummaryEntries.ACTIVE_SECONDS), 0.001);
        assertEquals(3150d, num(data, ActivitySummaryEntries.DISTANCE_METERS), 0.001);
        assertEquals(340d, num(data, ActivitySummaryEntries.CALORIES_BURNT), 0.001);
        assertEquals(819d, num(data, ActivitySummaryEntries.PACE_AVG_SECONDS_KM), 0.001);
        assertEquals(4526d, num(data, ActivitySummaryEntries.STEPS), 0.001);
        assertEquals(68d, num(data, ActivitySummaryEntries.STEP_LENGTH_AVG), 0.001);
        assertEquals(107d, num(data, ActivitySummaryEntries.CADENCE_AVG), 0.001);
        assertEquals(129d, num(data, ActivitySummaryEntries.HR_AVG), 0.001);
        assertEquals(2.5d, num(data, ActivitySummaryEntries.TRAINING_EFFECT_AEROBIC), 0.001);
        assertEquals(14d, num(data, ActivitySummaryEntries.RECOVERY_TIME), 0.001);
        assertEquals(45d, num(data, ActivitySummaryEntries.WORKOUT_LOAD), 0.001);
        assertEquals(10d, num(data, ActivitySummaryEntries.VITALITY_GAIN), 0.001);
        assertEquals(531d, num(data, ActivitySummaryEntries.AVG_GROUND_CONTACT_TIME), 0.001);
        assertEquals(278d, num(data, ActivitySummaryEntries.MIN_GROUND_CONTACT_TIME), 0.001);
        assertEquals(6.9d, num(data, ActivitySummaryEntries.AVG_VERTICAL_RATIO), 0.001);
        assertEquals(4.8d, num(data, ActivitySummaryEntries.MIN_VERTICAL_RATIO), 0.001);
        assertEquals(48d, num(data, ActivitySummaryEntries.AVG_VERTICAL_OSCILLATION), 0.001);
        assertEquals(31d, num(data, ActivitySummaryEntries.MIN_VERTICAL_OSCILLATION), 0.001);
        assertEquals(91d, num(data, ActivitySummaryEntries.MAX_VERTICAL_OSCILLATION), 0.001);
    }

    @Test
    public void treadmillV13_optionalCloudCourseKeepsFollowingFieldsAligned() {
        final BaseActivitySummary summary = new BaseActivitySummary();
        summary.setStartTime(new Date(1700000000000L));
        summary.setRawSummaryData(addCloudCourseId(buildAnonymizedTreadmillV13()));
        new WorkoutSummaryParser().parseBinaryData(summary, true);
        final ActivitySummaryData data = ActivitySummaryData.fromJson(summary.getSummaryData());

        assertEquals(45d, num(data, ActivitySummaryEntries.WORKOUT_LOAD), 0.001);
        assertEquals(10d, num(data, ActivitySummaryEntries.VITALITY_GAIN), 0.001);
        assertEquals(531d, num(data, ActivitySummaryEntries.AVG_GROUND_CONTACT_TIME), 0.001);
        assertEquals(6.9d, num(data, ActivitySummaryEntries.AVG_VERTICAL_RATIO), 0.001);
    }

    @Test
    public void treadmillV9_extractsHrStepsRecoveryLoadVitality() {
        final ActivitySummaryData data = parse(TREADMILL_V9_21JUN);

        // All values verified against the on-watch UI for this workout.
        assertEquals(192d, num(data, ActivitySummaryEntries.CALORIES_BURNT), 0.001);
        assertEquals(2720d, num(data, ActivitySummaryEntries.DISTANCE_METERS), 0.001);
        assertEquals(3216d, num(data, ActivitySummaryEntries.STEPS), 0.001);
        assertEquals(135d, num(data, ActivitySummaryEntries.HR_AVG), 0.001);
        assertEquals(180d, num(data, ActivitySummaryEntries.CADENCE_MAX), 0.001);
        assertEquals(1.9d, num(data, ActivitySummaryEntries.TRAINING_EFFECT_AEROBIC), 0.01);
        assertEquals(32d, num(data, ActivitySummaryEntries.RECOVERY_TIME), 0.001);
        assertEquals(28d, num(data, ActivitySummaryEntries.WORKOUT_LOAD), 0.001);
        assertEquals(15d, num(data, ActivitySummaryEntries.VITALITY_GAIN), 0.001);
    }

    @Test
    public void rowingV7_extractsAerobicAnaerobicStrokeMaxLoad() {
        final ActivitySummaryData data = parse(ROWING_V7_20APR);

        // Sanity checks
        assertEquals(142d, num(data, ActivitySummaryEntries.HR_AVG), 0.001);
        assertEquals(2549d, num(data, ActivitySummaryEntries.ACTIVE_SECONDS), 0.001);
        assertEquals(1208d, num(data, ActivitySummaryEntries.STROKES), 0.001);
        assertEquals(28d, num(data, ActivitySummaryEntries.STROKE_RATE_AVG), 0.001);

        // New fields verified against the device UI
        assertEquals(3.4d, num(data, ActivitySummaryEntries.TRAINING_EFFECT_AEROBIC), 0.01);
        assertEquals(0.4d, num(data, ActivitySummaryEntries.TRAINING_EFFECT_ANAEROBIC), 0.01);
        assertEquals(71d, num(data, ActivitySummaryEntries.STROKE_RATE_MAX), 0.001);
        assertEquals(23d, num(data, ActivitySummaryEntries.RECOVERY_TIME), 0.001);
        assertEquals(69d, num(data, ActivitySummaryEntries.WORKOUT_LOAD), 0.001);
        // Vitality_gain = 0 in this workout; XiaomiSimpleActivityParser force-displays it.
        assertEquals(0d, num(data, ActivitySummaryEntries.VITALITY_GAIN), 0.001);
    }

    @Test
    public void rowingV7_15Apr_extractsAnaerobic() {
        final ActivitySummaryData data = parse(ROWING_V7_15APR);

        assertEquals(1.1d, num(data, ActivitySummaryEntries.TRAINING_EFFECT_ANAEROBIC), 0.01);
        assertEquals(3.5d, num(data, ActivitySummaryEntries.TRAINING_EFFECT_AEROBIC), 0.01);
        assertEquals(41d, num(data, ActivitySummaryEntries.STROKE_RATE_MAX), 0.001);
    }

    @Test
    public void freestyleV10_extractsFrisbeeThrows() {
        final ActivitySummaryData data = parse(FREESTYLE_V10_FRISBEE);

        // Sanity checks
        assertEquals(2.6d, num(data, ActivitySummaryEntries.TRAINING_EFFECT_ANAEROBIC), 0.01);
        assertEquals(4.0d, num(data, ActivitySummaryEntries.TRAINING_EFFECT_AEROBIC), 0.01);

        // New frisbee throw-force buckets
        assertEquals(105d, num(data, ActivitySummaryEntries.THROWS_LOW), 0.001);
        assertEquals(93d, num(data, ActivitySummaryEntries.THROWS_MEDIUM), 0.001);
        assertEquals(7d, num(data, ActivitySummaryEntries.THROWS_HIGH), 0.001);

        // ActivityKind override via XIAOMI_WORKOUT_TYPE = 807 → FRISBEE
        // (sample's BaseActivitySummary doesn't expose summary.getActivityKind() through
        // ActivitySummaryData; the override is verified indirectly by the workout-type
        // mapping in XiaomiWorkoutType.fromCode). Throws-force values are the parser-level
        // assertion.
    }
}
