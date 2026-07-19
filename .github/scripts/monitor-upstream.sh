#!/usr/bin/env bash
set -euo pipefail

UPSTREAM_REMOTE="${UPSTREAM_REMOTE:-upstream}"
UPSTREAM_BRANCH="${UPSTREAM_BRANCH:-master}"
SNAPSHOT_REF="refs/remotes/origin/upstream-snapshot"
UPSTREAM_REF="${UPSTREAM_REMOTE}/${UPSTREAM_BRANCH}"
REPORT_FILE="${RUNNER_TEMP:-.}/gadgetbridge-upstream-report.md"

if git show-ref --verify --quiet "${SNAPSHOT_REF}"; then
    base_ref="${SNAPSHOT_REF}"
else
    base_ref="$(git merge-base HEAD "${UPSTREAM_REF}")"
fi

if [[ "$(git rev-parse "${base_ref}")" == "$(git rev-parse "${UPSTREAM_REF}")" ]]; then
    {
        echo "relevant=false"
        echo "report=${REPORT_FILE}"
    } >> "${GITHUB_OUTPUT}"
    exit 0
fi

xiaomi_regex='(^|/)(xiaomi|miband)(/|$)|MiBand10Pro|Xiaomi|Mi Band|Smart Band'
health_regex='sleep|hrv|spo2|blood.?oxygen|weather'
shared_health_regex='app/src/main/java/nodomain/freeyourgadget/gadgetbridge/(database|entities|activities/dashboard|activities/charts|service|impl)/'
possible_regex='(^|/)(build\.gradle(\.kts)?|settings\.gradle(\.kts)?|gradle\.properties|AndroidManifest\.xml)$|(^|/)gradle/|app/src/main/java/nodomain/freeyourgadget/gadgetbridge/(database|entities|activities/dashboard|activities/charts|service/Device|GBApplication)'
other_device_regex='app/src/(main|test)/java/nodomain/freeyourgadget/gadgetbridge/(devices|service/devices)/'

direct_entries=()
possible_entries=()

while IFS= read -r commit; do
    subject="$(git show -s --format=%s "${commit}")"
    files="$(git diff-tree --no-commit-id --name-only -r "${commit}")"
    combined="${subject}"$'\n'"${files}"

    if grep -Eiq "${xiaomi_regex}" <<< "${combined}"; then
        direct_entries+=("${commit}")
        continue
    fi

    if grep -Eiq "${health_regex}" <<< "${subject}" && grep -Eiq "${shared_health_regex}" <<< "${files}"; then
        direct_entries+=("${commit}")
        continue
    fi

    if grep -Eiq "${possible_regex}" <<< "${files}"; then
        possible_entries+=("${commit}")
        continue
    fi

    non_device_files="$(grep -Ev "${other_device_regex}" <<< "${files}" || true)"
    if [[ -n "${non_device_files}" ]] && grep -Eiq '(database|schema|android|gradle|dashboard|chart|sync|activity|health|permission)' <<< "${combined}"; then
        possible_entries+=("${commit}")
    fi
done < <(git rev-list --reverse "${base_ref}..${UPSTREAM_REF}")

if (( ${#direct_entries[@]} == 0 && ${#possible_entries[@]} == 0 )); then
    {
        echo "relevant=false"
        echo "report=${REPORT_FILE}"
    } >> "${GITHUB_OUTPUT}"
    exit 0
fi

{
    echo "# Gadgetbridge upstream updates requiring review"
    echo
    echo "Compared Codeberg commits after \`$(git rev-parse --short "${base_ref}")\` through \`$(git rev-parse --short "${UPSTREAM_REF}")\`."
    echo

    if (( ${#direct_entries[@]} > 0 )); then
        echo "## Directly relevant"
        echo
        for commit in "${direct_entries[@]}"; do
            short="$(git rev-parse --short "${commit}")"
            subject="$(git show -s --format=%s "${commit}")"
            echo "- [\`${short}\`](https://codeberg.org/Freeyourgadget/Gadgetbridge/commit/${commit}) ${subject}"
            git diff-tree --no-commit-id --name-only -r "${commit}" | sed 's/^/  - `/' | sed 's/$/`/'
        done
        echo
    fi

    if (( ${#possible_entries[@]} > 0 )); then
        echo "## Potentially relevant"
        echo
        echo "These commits touch shared Android, database, health, chart, or build code and deserve a quick manual check."
        echo
        for commit in "${possible_entries[@]}"; do
            short="$(git rev-parse --short "${commit}")"
            subject="$(git show -s --format=%s "${commit}")"
            echo "- [\`${short}\`](https://codeberg.org/Freeyourgadget/Gadgetbridge/commit/${commit}) ${subject}"
            git diff-tree --no-commit-id --name-only -r "${commit}" | sed 's/^/  - `/' | sed 's/$/`/'
        done
        echo
    fi

    echo "No changes were merged automatically. Close this issue to ignore the update, or request a review and merge into the personal build."
} > "${REPORT_FILE}"

{
    echo "relevant=true"
    echo "report=${REPORT_FILE}"
    echo "direct_count=${#direct_entries[@]}"
    echo "possible_count=${#possible_entries[@]}"
} >> "${GITHUB_OUTPUT}"
