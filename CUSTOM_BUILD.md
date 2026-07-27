# Gadgetbridge Xiaomi Smart Band 10 Pro build

This repository contains a personal Gadgetbridge build based on the authoritative
[Freeyourgadget/Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)
repository on Codeberg.

## Local changes

- Parse Xiaomi sleep details format version 6.
- Persist sleep stages and summary data from version-6 reports.
- Decode and persist version-6 sleep HRV samples.
- Expose HRV providers for the Xiaomi Smart Band 10 Pro coordinator.
- Parse Xiaomi treadmill summary version 13 and workout details version 9.
- Preserve every Xiaomi activity file as both an external raw file and an exact
  database BLOB, including fields that are not understood or displayed yet.

## Data-preservation principle

Lossless preservation of data received from the band is a primary requirement
of this project.

- Unknown, unsupported, or currently unused fields must not be silently
  discarded when their original bytes can be retained.
- Parsing and presentation are allowed to improve later; the source bytes must
  remain available for reprocessing.
- A database migration or parser change must not replace or destroy the only
  copy of raw data.
- Existing external raw files remain supported, while database copies provide
  an independent recovery path and are included in database exports.
- Tests for new file-backed features should cover recovery when the external
  raw file is unavailable.

## Privacy

The tests use synthetic timestamps, sleep durations, and HRV values. This
repository intentionally contains no raw health-data files, Bluetooth MAC
addresses, authentication keys, application databases, or proprietary Mi
Fitness APKs.

## Upstream monitoring

The scheduled GitHub Actions workflow fetches the default branch directly from
the authoritative Codeberg repository once per day. It classifies new commits
as directly relevant, potentially relevant, or unrelated to this Xiaomi build.
Only relevant updates create a GitHub issue for manual review. The workflow
never merges upstream changes automatically.

The `upstream-snapshot` branch records the last inspected upstream state.
