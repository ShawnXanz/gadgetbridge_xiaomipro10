# Gadgetbridge Xiaomi Smart Band 10 Pro build

This repository contains a personal Gadgetbridge build based on the authoritative
[Freeyourgadget/Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge)
repository on Codeberg.

## Local changes

- Parse Xiaomi sleep details format version 6.
- Persist sleep stages and summary data from version-6 reports.
- Decode and persist version-6 sleep HRV samples.
- Expose HRV providers for the Xiaomi Smart Band 10 Pro coordinator.

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
