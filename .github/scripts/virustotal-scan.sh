#!/usr/bin/env bash
set -euo pipefail

apk="${1:?usage: virustotal-scan.sh <apk>}"
api_key="${VIRUSTOTAL_API_KEY:?VIRUSTOTAL_API_KEY is not set}"

if [ ! -f "$apk" ]; then
  echo "::error::APK not found: $apk" >&2
  exit 1
fi

# The public API rejects uploads larger than 32 MiB. The release APK is well
# below that; the debug APK is not, which is why only the release is scanned.
size=$(stat -c '%s' "$apk")
if [ "$size" -gt $((32 * 1024 * 1024)) ]; then
  echo "::error::$(basename "$apk") is larger than VirusTotal's 32 MiB upload limit" >&2
  exit 1
fi

sha256=$(sha256sum "$apk" | awk '{print $1}')
echo "Uploading $(basename "$apk") ($sha256) to VirusTotal"

response=$(curl --silent --show-error --fail-with-body \
  --retry 3 --retry-delay 10 \
  --header "x-apikey: $api_key" \
  --form "file=@$apk" \
  "https://www.virustotal.com/api/v3/files")

analysis_id=$(printf '%s' "$response" | jq -r '.data.id // empty')
if [ -z "$analysis_id" ]; then
  echo "::error::VirusTotal did not return an analysis id: $response" >&2
  exit 1
fi

echo "VirusTotal analysis id: $analysis_id"
status="queued"
stats=""
for attempt in $(seq 1 30); do
  # The public API allows four requests a minute. One upload and then one poll
  # every twenty seconds stays under that.
  sleep 20
  response=$(curl --silent --show-error --fail-with-body \
    --retry 3 --retry-delay 10 \
    --header "x-apikey: $api_key" \
    "https://www.virustotal.com/api/v3/analyses/$analysis_id")
  status=$(printf '%s' "$response" | jq -r '.data.attributes.status // empty')
  echo "VirusTotal analysis ${status:-unknown} (poll $attempt/30)"
  case "$status" in
    completed)
      stats=$(printf '%s' "$response" | jq -r '
        .data.attributes.stats as $stats
        | if $stats then
            "\($stats.malicious) malicious, \($stats.suspicious) suspicious, \($stats.undetected) undetected"
          else
            "No result statistics"
          end')
      break
      ;;
    queued|in-progress)
      ;;
    *)
      echo "::error::VirusTotal analysis ended with status ${status:-unknown}" >&2
      exit 1
      ;;
  esac
done

if [ "$status" != "completed" ]; then
  echo "::error::VirusTotal analysis did not finish within ten minutes" >&2
  exit 1
fi

report_url="https://www.virustotal.com/gui/file/$sha256"
echo "VirusTotal report: $report_url"
echo "VirusTotal result: $stats"

{
  echo "report_url=$report_url"
  echo "sha256=$sha256"
  echo "analysis_id=$analysis_id"
  echo "stats=$stats"
} >> "${GITHUB_OUTPUT:?GITHUB_OUTPUT is not set}"
