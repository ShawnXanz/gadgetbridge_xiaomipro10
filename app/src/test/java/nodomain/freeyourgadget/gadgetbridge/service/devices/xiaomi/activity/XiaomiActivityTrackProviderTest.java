/*  Copyright (C) 2026 Thomas Kuehne

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiActivityFile;

import static org.junit.Assert.assertArrayEquals;

public class XiaomiActivityTrackProviderTest {
    @Test
    public void usesDatabaseCopyWhenExternalFileIsMissing() {
        final XiaomiActivityFile entry = new XiaomiActivityFile();
        entry.setFilePath(new File("definitely-missing", "activity.bin").getAbsolutePath());
        entry.setRawData(new byte[]{0x01, 0x23, (byte) 0xFF});

        assertArrayEquals(
                new byte[]{0x01, 0x23, (byte) 0xFF},
                XiaomiActivityTrackProvider.readBytes(entry));
    }

    @Test
    public void externalFileRemainsPreferredForDeveloperReprocessing() throws Exception {
        final File file = Files.createTempFile("xiaomi-activity-", ".bin").toFile();
        try {
            Files.write(file.toPath(), new byte[]{0x45, 0x67});

            final XiaomiActivityFile entry = new XiaomiActivityFile();
            entry.setFilePath(file.getAbsolutePath());
            entry.setRawData(new byte[]{0x01, 0x23});

            assertArrayEquals(
                    new byte[]{0x45, 0x67},
                    XiaomiActivityTrackProvider.readBytes(entry));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
