/*  Copyright (C) 2023-2024 José Rebelo

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

import android.content.Context;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.ComputedHrvSummarySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.HeartPulseSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.XiaomiSleepStageSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.XiaomiSleepTimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HeartPulseSample;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepTimeSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileId;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityParser;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class SleepDetailsParser extends XiaomiActivityParser {
    private static final Logger LOG = LoggerFactory.getLogger(SleepDetailsParser.class);

    @Override
    public boolean parse(final Context context, final GBDevice gbDevice, final XiaomiActivityFileId fileId, final byte[] bytes) {
        // Seems to come both as DetailType.DETAILS (version 2) and DetailType.SUMMARY (version 4, 5, 6)
        final int version = fileId.getVersion();
        final int headerSize = headerSizeForVersion(version);
        if (headerSize < 0) {
            LOG.warn("Unknown sleep details version {}", fileId.getVersion());
            return false;
        }

        // Current offset in the header, which only advances if we process a field available in the version
        int headerIdx = 0;

        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.get(new byte[7]); // skip fileId bytes
        final byte fileIdPadding = buf.get();
        if (fileIdPadding != 0) {
            LOG.warn("Expected 0 padding after fileId, got {} - parsing might fail", fileIdPadding);
        }

        final byte[] header = new byte[headerSize];
        buf.get(header);

        final int isAwake = buf.get() & 0xff; // 0/1 - more correctly this would be !isSleepFinish
        headerIdx++;

        final int bedTime = buf.getInt();
        headerIdx++;

        final int wakeupTime = buf.getInt();
        headerIdx++;

        int sleepQuality = -1;
        if (fileId.getVersion() >= 4) {
            if (version == 6) {
                // V6 fixed-width report values occupy bytes even when their validity flag is
                // clear, and sleepFull is no longer represented in the validity bitmap.
                final int rawSleepQuality = buf.get() & 0xff;
                if (validData(header, 2)) {
                    sleepQuality = rawSleepQuality;
                }
            } else if (validData(header, headerIdx)) {
                // Preserve the established behavior for older sleep formats.
                sleepQuality = buf.get() & 0xff;
            }
            headerIdx++;
        }

        // note on RR-intervals (msg type 1) on Xiaomi band 9, FW 2.3.93, HW M2345B1:
        //
        // the band collects RR interval in groups of ~10 minutes, which are then sent as msg type 1
        // along with an associated RTC timestamp. RTC timestamp jitter and drift of up to
        // +/-several seconds was observed, compared to the sum of intervals in a given msg.
        //
        // In order to preserve RR-interval continuity throughout the entire duration of sleep, as
        // is necessary for breath-detection algorithm, and to avoid negative or impossibly large
        // (false) RR intervals, we maintain an running timestamp of last heart pulse.
        // While processing msgs, in case the detected jitter is too large, the running timestamp
        // is reset to the current msg timestamp.
        long lastHeartPulseTimestamp = 0;  // tracks the true timestamp of last heart pulse across the jittery 10-minute segments

        LOG.debug("Sleep sample: bedTime: {}, wakeupTime: {}, isAwake: {}", bedTime, wakeupTime, isAwake);

        final List<XiaomiSleepTimeSample> summaries = new ArrayList<>();
        final List<HrvPoint> hrvPoints = new ArrayList<>();

        XiaomiSleepTimeSample sample = new XiaomiSleepTimeSample();
        sample.setTimestamp(bedTime * 1000L);
        sample.setWakeupTime(wakeupTime * 1000L);
        sample.setIsAwake(isAwake == 1);

        if (fileId.getVersion() == 6) {
            try {
                final V6AdditionalData v6Data = decodeV6AdditionalData(buf, header);
                hrvPoints.addAll(v6Data.hrvPoints);
                LOG.debug(
                        "V6 sleep report: efficiency {}%, asleep in {}s, in bed {}s, HRV avg {}ms, {} valid HRV points",
                        v6Data.sleepEfficiency,
                        v6Data.entrySleepDuration,
                        v6Data.inBedDuration,
                        v6Data.hrvAverage,
                        v6Data.hrvPoints.size()
                );
            } catch (final IllegalArgumentException | BufferUnderflowException e) {
                // Packet scanning below is self-synchronizing, so a malformed optional report
                // section must not prevent us from recovering the sleep summary and stages.
                LOG.warn("Malformed v6 sleep report fields at offset {}", buf.position(), e);
            }
        } else if (fileId.getVersion() >= 5) {
            buf.get(new byte[9]); // ?
            final int bedTime2 = buf.getInt(); // around ~30 min before bedTime, is previous one fall asleep time?
            final int wakeupTime2 = buf.getInt(); // == wakeupTime
            headerIdx += 5;
        }

        // V6 assist series are decoded above using their expanded 24-bit validity map.
        if (version != 6 && validData(header, headerIdx)) {
            LOG.debug("Heart rate samples from offset {}", Integer.toHexString(buf.position()));
            final int unit = buf.getShort(); // Time unit (i.e sample rate)
            final int count = buf.getShort();

            if (count > 0) {
                // If version is less than 2 firstRecordTime is bedTime
                if (fileId.getVersion() >= 2) {
                    final int firstRecordTime = buf.getInt();
                }

                // Skip count samples - each sample is a u8
                //   timestamp of each sample is firstRecordTime + (unit * index)
                buf.position(buf.position() + count);
            }
        }
        if (version != 6) {
            headerIdx++;
        }

        // SpO2 samples
        if (version != 6 && validData(header, headerIdx)) {
            LOG.debug("SpO₂ samples from offset {}", Integer.toHexString(buf.position()));
            final int unit = buf.getShort(); // Time unit (i.e sample rate)
            final int count = buf.getShort();

            if (count > 0) {
                // If version is less than 2 firstRecordTime is bedTime
                if (fileId.getVersion() >= 2) {
                    final int firstRecordTime = buf.getInt();
                }

                // Skip count samples - each sample is a u8
                //   timestamp of each sample is firstRecordTime + (unit * index)
                buf.position(buf.position() + count);
            }
        }
        if (version != 6) {
            headerIdx++;
        }

        // snore samples
        if (version != 6 && fileId.getVersion() >= 3) {
            if (validData(header, headerIdx)) {
                LOG.debug("Snore level samples from offset {}", Integer.toHexString(buf.position()));
                final int unit = buf.getShort(); // Time unit (i.e sample rate)
                final int count = buf.getShort();

                if (count > 0) {
                    // If version is less than 2 firstRecordTime is bedTime
                    if (fileId.getVersion() >= 2) {
                        final int firstRecordTime = buf.getInt();
                    }

                    // Skip count samples - each sample is a float
                    //   timestamp of each sample is firstRecordTime + (unit * index)
                    buf.position(buf.position() + count * 4);
                }
            }
            headerIdx++;
        }

        final List<XiaomiSleepStageSample> stages = new ArrayList<>();
        final List<HeartPulseSample> heartPulseSamples = new ArrayList<>();
        LOG.debug("Sleep stage packets from offset {}", Integer.toHexString(buf.position()));

        // Do not crash if we face a buffer underflow, as the next parsing is not 100% fool-proof,
        // and we still want to persist whatever we got so far
        boolean stagesParseFailed = false;
        try {
            while (buf.remaining() >= 17) {
                if (!readStagePacketHeader(buf)) {
                    break;
                }

                final int headerLen = buf.get() & 0xFF; // this seems to always be 17
                if (headerLen != 17) {
                    LOG.warn("Unexpected sleep packet header length {} at offset {}", headerLen, buf.position() - 5);
                }

                // This timestamp is kind of weird, is seems to sometimes be in seconds
                // and other times in nanoseconds. Message types 16 and 17 are in seconds
                final long ts = buf.getLong();
                final int parity = buf.get() & 0xFF; // sum of stage bit count should be uneven
                final int type = buf.get() & 0xFF;
                final int dataLen = ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);

                // Known types:
                //  - acc_unk = 0,
                //  - ppg_unk = 1,
                //  - fall_asleep = 2,
                //  - wake_up = 3,
                //  - switch_ts_unk1 = 12,
                //  - switch_ts_unk2 = 13,
                //  - Summary = 16,
                //  - Stages = 17

                if (isPayloadlessPacketType(type)) {
                    // the bytes reserved for the data length are believed to be flags, as they
                    // do not actually have any data following the headers
                    continue;
                }

                if (dataLen > buf.remaining()) {
                    LOG.warn(
                            "Sleep packet type 0x{} declares {} data bytes, but only {} remain",
                            Integer.toHexString(type),
                            dataLen,
                            buf.remaining()
                    );
                    stagesParseFailed = true;
                    break;
                }

                final byte[] data = new byte[dataLen];
                buf.get(data);

                final ByteBuffer dataBuf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

                if (type == 1) {
                    // RR intervals: https://en.wikipedia.org/wiki/RR_interval
                    // add first heartbeat before intervals, if necessary
                    if (Math.abs(lastHeartPulseTimestamp - ts) > 30000) {  // 30 seconds
                        // we drifted too far from RTC timestamp, or this is the very first sample:
                        lastHeartPulseTimestamp = ts;  // resync
                        final HeartPulseSample heartPulseSample = new HeartPulseSample();
                        heartPulseSample.setTimestamp(lastHeartPulseTimestamp);
                        heartPulseSamples.add(heartPulseSample);
                    }
                    // add heartbeats after intervals
                    while (dataBuf.position() < dataBuf.limit()) {
                        final int delta = dataBuf.get() & 0xff;  // delta is in 10msec units
                        lastHeartPulseTimestamp += 10 * delta;  // convert to 1msec units for timestamps
                        final HeartPulseSample heartPulseSample = new HeartPulseSample();
                        heartPulseSample.setTimestamp(lastHeartPulseTimestamp);
                        heartPulseSamples.add(heartPulseSample);
                    }
                } else if (type == 16) {
                    sample = decodeSummaryPacket(data, bedTime, wakeupTime, sample);

                    // FIXME: This is an array, but we end up persisting only the last sample, since
                    //        the timestamp is the primary key
                    summaries.add(sample);
                    sample = null;
                } else if (type == 17) { // Stages
                    stages.addAll(decodeStagePacket(data, ts));
                } else {
                    LOG.debug(
                            "Ignoring unsupported sleep packet type 0x{} with {} data bytes",
                            Integer.toHexString(type),
                            dataLen
                    );
                }
            }
        } catch (final BufferUnderflowException e) {
            LOG.warn("Buffer underflow while parsing sleep stages...", e);
            stagesParseFailed = true;
        }

        if (summaries.isEmpty()) {
            // We did not manage to find sleep stage samples - ensure we at least persist the base one
            summaries.add(sample);
        }

        boolean persistSuccess = !stagesParseFailed;

        // save all the samples that we got
        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();

            final XiaomiSleepTimeSampleProvider sampleProvider = new XiaomiSleepTimeSampleProvider(gbDevice, session);

            for (final XiaomiSleepTimeSample summary : summaries) {
                summary.setDevice(DBHelper.getDevice(gbDevice, session));
                summary.setUser(DBHelper.getUser(session));

                // Check if there is already a later sleep sample - if so, ignore this one
                // Samples for the same sleep will always have the same bedtime (timestamp), but we might get
                // multiple bedtimes until the user wakes up
                final List<XiaomiSleepTimeSample> existingSamples = sampleProvider.getAllSamples(summary.getTimestamp(), summary.getTimestamp());
                if (!existingSamples.isEmpty()) {
                    final XiaomiSleepTimeSample existingSample = existingSamples.get(0);
                    if (existingSample.getWakeupTime() > summary.getWakeupTime()) {
                        LOG.warn("Ignoring sleep sample - existing sample is more recent ({})", existingSample.getWakeupTime());
                        continue;
                    }
                }

                sampleProvider.addSample(summary);
            }
        } catch (final Exception e) {
            GB.toast(context, "Error saving sleep sample", Toast.LENGTH_LONG, GB.ERROR);
            LOG.error("Error saving sleep sample", e);
            persistSuccess = false;
        }

        if (!hrvPoints.isEmpty()) {
            try (DBHandler handler = GBApplication.acquireDB()) {
                final DaoSession session = handler.getDaoSession();
                final Device device = DBHelper.getDevice(gbDevice, session);
                final User user = DBHelper.getUser(session);
                final List<GenericHrvValueSample> hrvSamples = new ArrayList<>(hrvPoints.size());

                for (final HrvPoint point : hrvPoints) {
                    final GenericHrvValueSample hrvSample = new GenericHrvValueSample();
                    hrvSample.setTimestamp(point.timestamp);
                    hrvSample.setValue(point.value);
                    hrvSample.setDevice(device);
                    hrvSample.setUser(user);
                    hrvSamples.add(hrvSample);
                }

                new GenericHrvValueSampleProvider(gbDevice, session).addSamples(hrvSamples);
                ComputedHrvSummarySampleProvider.clearCache(gbDevice.getAddress());
                LOG.debug("Persisted {} v6 sleep HRV samples", hrvSamples.size());
            } catch (final Exception e) {
                GB.toast(context, "Error saving sleep HRV samples", Toast.LENGTH_LONG, GB.ERROR);
                LOG.error("Error saving sleep HRV samples", e);
                persistSuccess = false;
            }
        }

        if (!stagesParseFailed && !stages.isEmpty()) {
            LOG.debug("Persisting {} sleep stage samples", stages.size());

            // Save the sleep stage samples
            try (DBHandler handler = GBApplication.acquireDB()) {
                final DaoSession session = handler.getDaoSession();
                final Device device = DBHelper.getDevice(gbDevice, session);
                final User user = DBHelper.getUser(session);

                final XiaomiSleepStageSampleProvider sampleProvider = new XiaomiSleepStageSampleProvider(gbDevice, session);

                for (final XiaomiSleepStageSample stageSample : stages) {
                    stageSample.setDevice(device);
                    stageSample.setUser(user);
                }

                sampleProvider.addSamples(stages);
            } catch (final Exception e) {
                GB.toast(context, "Error saving sleep stage samples", Toast.LENGTH_LONG, GB.ERROR);
                LOG.error("Error saving sleep stage samples", e);
                persistSuccess = false;
            }
        }

        // Save the heart pulse samples
        try (DBHandler handler = GBApplication.acquireDB()) {
            final DaoSession session = handler.getDaoSession();
            final Device device = DBHelper.getDevice(gbDevice, session);
            final User user = DBHelper.getUser(session);

            final HeartPulseSampleProvider sampleProvider = new HeartPulseSampleProvider(gbDevice, session);

            for (final HeartPulseSample stageSample : heartPulseSamples) {
                stageSample.setDevice(device);
                stageSample.setUser(user);
            }

            sampleProvider.addSamples(heartPulseSamples);
        } catch (final Exception e) {
            GB.toast(context, "Error saving heart pulse samples", Toast.LENGTH_LONG, GB.ERROR);
            LOG.error("Error saving heart pulse samples", e);
            persistSuccess = false;
        }

        return persistSuccess;
    }

    private static boolean readStagePacketHeader(final ByteBuffer buffer) {
        while (buffer.remaining() >= 17) {
            if (buffer.getInt() != 0xfffcfafb) {
                // rollback to second byte of header
                buffer.position(buffer.position() - 3);
                continue;
            }

            return true;
        }
        return false;
    }

    static int headerSizeForVersion(final int version) {
        switch (version) {
            case 1:
            case 2:
            case 3:
            case 4:
                return 1;
            case 5:
                return 2;
            case 6:
                return 3;
            default:
                return -1;
        }
    }

    static boolean isPayloadlessPacketType(final int type) {
        return type == 0x2 ||
                type == 0x3 ||
                type == 0x9 ||
                type == 0xc ||
                type == 0xd ||
                type == 0xe ||
                type == 0xf ||
                type == 0x12 ||
                type == 0x13;
    }

    static V6AdditionalData decodeV6AdditionalData(final ByteBuffer buf, final byte[] validity) {
        // V6 report schema after sleepFull/start/end/quality:
        // efficiency, asleep duration, in-bed duration, go/leave-bed timestamps,
        // ten HRV summary values, then optional HR/SpO2/HRV/snore series.
        if (validity.length != 3) {
            throw new IllegalArgumentException("Expected 3 v6 validity bytes, got " + validity.length);
        }
        if (buf.remaining() < 41) {
            throw new IllegalArgumentException("V6 fixed report requires 41 bytes, got " + buf.remaining());
        }

        final V6AdditionalData result = new V6AdditionalData();
        result.sleepEfficiency = buf.get() & 0xff;
        result.entrySleepDuration = Integer.toUnsignedLong(buf.getInt());
        result.inBedDuration = Integer.toUnsignedLong(buf.getInt());
        result.goBedTime = Integer.toUnsignedLong(buf.getInt());
        result.leaveBedTime = Integer.toUnsignedLong(buf.getInt());
        result.hrvAverage = Short.toUnsignedInt(buf.getShort());
        result.hrvStandardDeviation = Short.toUnsignedInt(buf.getShort());
        result.hrvMedian = Short.toUnsignedInt(buf.getShort());
        result.hrvLowerPercentile = Short.toUnsignedInt(buf.getShort());
        result.hrvUpperPercentile = Short.toUnsignedInt(buf.getShort());
        result.hrvMiddlePercentile = Short.toUnsignedInt(buf.getShort());
        result.hrvTimestamp = Integer.toUnsignedLong(buf.getInt());
        result.hrvMax = Short.toUnsignedInt(buf.getShort());
        result.hrvMin = Short.toUnsignedInt(buf.getShort());
        result.hrvBaselineMax = Short.toUnsignedInt(buf.getShort());
        result.hrvBaselineMin = Short.toUnsignedInt(buf.getShort());

        if (validData(validity, 19)) {
            skipAssistSeries(buf, 1, "heart rate");
        }
        if (validData(validity, 20)) {
            skipAssistSeries(buf, 1, "SpO2");
        }
        if (validData(validity, 21)) {
            result.hrvPoints.addAll(readHrvAssistSeries(buf));
        }
        if (validData(validity, 22)) {
            skipAssistSeries(buf, 4, "snore");
        }

        return result;
    }

    private static void skipAssistSeries(final ByteBuffer buf, final int sampleWidth, final String name) {
        final AssistSeriesHeader header = readAssistSeriesHeader(buf, sampleWidth, name);
        buf.position(buf.position() + header.count * sampleWidth);
    }

    private static List<HrvPoint> readHrvAssistSeries(final ByteBuffer buf) {
        final AssistSeriesHeader header = readAssistSeriesHeader(buf, 2, "HRV");
        final List<HrvPoint> points = new ArrayList<>(header.count);
        for (int i = 0; i < header.count; i++) {
            final int value = Short.toUnsignedInt(buf.getShort());
            // Xiaomi uses zero-filled slots when no valid HRV estimate was produced.
            if (value > 0) {
                points.add(new HrvPoint(
                        (header.firstRecordTime + (long) header.sampleUnit * i) * 1000L,
                        value
                ));
            }
        }
        return points;
    }

    private static AssistSeriesHeader readAssistSeriesHeader(final ByteBuffer buf,
                                                             final int sampleWidth,
                                                             final String name) {
        if (buf.remaining() < 4) {
            throw new IllegalArgumentException(name + " series is missing interval/count");
        }

        final int sampleUnit = Short.toUnsignedInt(buf.getShort());
        final int count = Short.toUnsignedInt(buf.getShort());
        if (count == 0) {
            return new AssistSeriesHeader(sampleUnit, 0, 0);
        }
        if (buf.remaining() < 4) {
            throw new IllegalArgumentException(name + " series is missing its first timestamp");
        }

        final long firstRecordTime = Integer.toUnsignedLong(buf.getInt());
        final long requiredBytes = (long) count * sampleWidth;
        if (requiredBytes > buf.remaining()) {
            throw new IllegalArgumentException(
                    name + " series declares " + count + " samples (" + requiredBytes +
                            " bytes), but only " + buf.remaining() + " remain"
            );
        }

        return new AssistSeriesHeader(sampleUnit, count, firstRecordTime);
    }

    static final class V6AdditionalData {
        int sleepEfficiency;
        long entrySleepDuration;
        long inBedDuration;
        long goBedTime;
        long leaveBedTime;
        int hrvAverage;
        int hrvStandardDeviation;
        int hrvMedian;
        int hrvLowerPercentile;
        int hrvUpperPercentile;
        int hrvMiddlePercentile;
        long hrvTimestamp;
        int hrvMax;
        int hrvMin;
        int hrvBaselineMax;
        int hrvBaselineMin;
        final List<HrvPoint> hrvPoints = new ArrayList<>();
    }

    static final class HrvPoint {
        final long timestamp;
        final int value;

        HrvPoint(final long timestamp, final int value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }

    private static final class AssistSeriesHeader {
        final int sampleUnit;
        final int count;
        final long firstRecordTime;

        private AssistSeriesHeader(final int sampleUnit, final int count, final long firstRecordTime) {
            this.sampleUnit = sampleUnit;
            this.count = count;
            this.firstRecordTime = firstRecordTime;
        }
    }

    static XiaomiSleepTimeSample decodeSummaryPacket(final byte[] data,
                                                     final int bedTime,
                                                     final int wakeupTime,
                                                     XiaomiSleepTimeSample sample) {
        if (data.length != 13) {
            LOG.warn("Unexpected sleep summary packet length {}, expected 13", data.length);
        }

        final ByteBuffer dataBuf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        final int data0 = dataBuf.get() & 0xFF;
        final int sleepIndex = data0 >> 4;
        final int wakeCount = data0 & 0x0F;

        final int sleepDuration = dataBuf.getShort() & 0xFFFF;
        final int wakeDuration = dataBuf.getShort() & 0xFFFF;
        final int lightDuration = dataBuf.getShort() & 0xFFFF;
        final int remDuration = dataBuf.getShort() & 0xFFFF;
        final int deepDuration = dataBuf.getShort() & 0xFFFF;

        final int data1 = dataBuf.get() & 0xFF;
        final boolean hasRem = (data1 >> 4) == 1;
        final boolean hasStage = (data1 >> 2) == 1;

        // Could probably be an "awake" duration after sleep
        final int unknownDurationMinutes = dataBuf.get() & 0xFF;

        if (sample == null) {
            sample = new XiaomiSleepTimeSample();
        }

        sample.setTimestamp(bedTime * 1000L);
        sample.setWakeupTime(wakeupTime * 1000L);
        sample.setTotalDuration(sleepDuration);
        sample.setDeepSleepDuration(deepDuration);
        sample.setLightSleepDuration(lightDuration);
        sample.setRemSleepDuration(remDuration);
        sample.setAwakeDuration(wakeDuration);
        return sample;
    }

    static List<XiaomiSleepStageSample> decodeStagePacket(final byte[] data, final long timestampSeconds) {
        if ((data.length & 1) != 0) {
            LOG.warn("Unexpected odd sleep stage packet length {}", data.length);
        }

        final ByteBuffer dataBuf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        final List<XiaomiSleepStageSample> stages = new ArrayList<>();
        long currentTime = timestampSeconds * 1000;

        for (int i = 0; i < data.length / 2; i++) {
            // when the change to the phase occurs
            final int val = dataBuf.getShort() & 0xFFFF;

            final int stage = val >> 12;
            final int offsetMinutes = val & 0xFFF;

            final XiaomiSleepStageSample stageSample = new XiaomiSleepStageSample();
            stageSample.setTimestamp(currentTime);
            stageSample.setStage(decodeStage(stage));
            stages.add(stageSample);

            currentTime += offsetMinutes * 60000;
        }

        return stages;
    }

    private static int decodeStage(int rawStage) {
        switch (rawStage) {
            case 0:
                return 5; // AWAKE
            case 1:
                return 3; // LIGHT_SLEEP
            case 2:
                return 2; // DEEP_SLEEP
            case 3:
                return 4; // REM_SLEEP
            case 4:
                return 0; // NOT_SLEEP
            default:
                return 1; // N/A
        }
    }
}
