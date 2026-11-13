#!/usr/bin/env bash
# Downloads PaddleOCR PP-OCRv6 text-recognition models into the app's assets, one folder per size:
#   app/src/main/assets/ppocr/small/   app/src/main/assets/ppocr/medium/   ...
# The app lets you choose between the installed sizes in Settings.
#
#   scripts/fetch_models.sh                 # small + medium  <- default (about 95 MB in the APK)
#   scripts/fetch_models.sh small           # only small  (~21 MB, faster)
#   scripts/fetch_models.sh medium          # only medium (~73 MB, best handwriting accuracy)
#   scripts/fetch_models.sh tiny small ...  # any list of: tiny small medium
#
# Needs: bash, curl, python3 and internet access (huggingface.co). Safe to re-run: downloads resume,
# finished models are skipped. An older single-model layout (assets/ppocr/rec.onnx) is moved into its folder.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$HERE/../app/src/main/assets/ppocr"
mkdir -p "$ROOT"

TIERS=("$@")
[ ${#TIERS[@]} -eq 0 ] && TIERS=(small medium)
for T in "${TIERS[@]}"; do
  case "$T" in tiny|small|medium) ;; *) echo "usage: $0 [tiny|small|medium ...]" >&2; exit 1 ;; esac
done

# Old layout: files directly in ppocr/. MODEL.txt says which size they are.
if [ -s "$ROOT/rec.onnx" ] && [ -s "$ROOT/dict.json" ] && [ -s "$ROOT/MODEL.txt" ]; then
  OLD="$(sed 's/^PP-OCRv6 //' "$ROOT/MODEL.txt" | tr -d '[:space:]')"
  case "$OLD" in tiny|small|medium)
    echo "moving the existing $OLD model into $ROOT/$OLD/"
    mkdir -p "$ROOT/$OLD"
    mv "$ROOT/rec.onnx" "$ROOT/dict.json" "$ROOT/MODEL.txt" "$ROOT/$OLD/" ;;
  esac
fi

for TIER in "${TIERS[@]}"; do
  OUT="$ROOT/$TIER"
  BASE="https://huggingface.co/PaddlePaddle/PP-OCRv6_${TIER}_rec_onnx/resolve/main"
  mkdir -p "$OUT"
  if [ -s "$OUT/rec.onnx" ] && [ -s "$OUT/dict.json" ] && [ "$(cat "$OUT/MODEL.txt" 2>/dev/null)" = "PP-OCRv6 $TIER" ]; then
    echo "PP-OCRv6 $TIER is already in $OUT (delete that folder to download again)."
    continue
  fi

  TMP="$(mktemp -d)"
  echo "== $TIER =="
  echo "1/3  dictionary (inference.yml)"
  curl -fL --retry 5 --retry-delay 3 -o "$TMP/inference.yml" "$BASE/inference.yml"

  echo "2/3  model (inference.onnx), resumable"
  curl -fL --retry 5 --retry-delay 3 -C - -o "$OUT/rec.onnx.part" "$BASE/inference.onnx"
  SIZE="$(stat -c %s "$OUT/rec.onnx.part")"
  if [ "$SIZE" -lt 1000000 ]; then
    echo "error: downloaded file is only $SIZE bytes; not a model. Check the connection and re-run." >&2
    exit 1
  fi
  mv "$OUT/rec.onnx.part" "$OUT/rec.onnx"

  echo "3/3  converting the dictionary"
  python3 "$HERE/yml_to_dict_json.py" "$TMP/inference.yml" "$OUT/dict.json"
  echo "PP-OCRv6 $TIER" > "$OUT/MODEL.txt"
  rm -rf "$TMP"
done

echo
echo "Done. Installed models:"
ls -lh "$ROOT"/*/rec.onnx
echo "Now build the app (./gradlew --no-daemon assembleRelease). Choose the model in Settings."
