#!/usr/bin/env bash
# Lustre benchmark startup script.
# Runs on each VM at boot. Installs tools, mounts Lustre, runs benchmarks,
# writes JSON results to Lustre (and optionally uploads to GCS).
set -euo pipefail

# ---------- metadata helpers ------------------------------------------------
meta() { curl -sf -H "Metadata-Flavor: Google" "http://metadata.google.internal/computeMetadata/v1/instance/attributes/$1"; }

LUSTRE_MOUNT_NAME=$(meta lustre-mount-name)
LUSTRE_MOUNT_PATH=$(meta lustre-mount-path)
RESULTS_BUCKET=$(meta results-bucket)
DURATION_MIN=$(meta bench-duration-min)
VM_INDEX=$(meta vm-index)
VM_COUNT=$(meta vm-count)
HOSTNAME=$(hostname)

RESULTS_DIR="${LUSTRE_MOUNT_PATH}/results/${HOSTNAME}"
DURATION_SEC=$((DURATION_MIN * 60))

log() { echo "[bench $(date -Iseconds)] $*"; }

# ---------- install dependencies --------------------------------------------
log "Installing Lustre client and benchmark tools..."

if command -v dnf &>/dev/null; then
  dnf install -y lustre-client fio jq bc
elif command -v yum &>/dev/null; then
  yum install -y lustre-client fio jq bc
elif command -v apt-get &>/dev/null; then
  apt-get update -qq
  apt-get install -y -qq lustre-client-modules-$(uname -r) lustre-client-utils fio jq bc
fi

# ---------- mount lustre ----------------------------------------------------
log "Mounting Lustre at ${LUSTRE_MOUNT_PATH}..."
mkdir -p "${LUSTRE_MOUNT_PATH}"
if ! mountpoint -q "${LUSTRE_MOUNT_PATH}"; then
  mount -t lustre "${LUSTRE_MOUNT_NAME}" "${LUSTRE_MOUNT_PATH}"
fi

mkdir -p "${RESULTS_DIR}"
WORK="${LUSTRE_MOUNT_PATH}/work/${HOSTNAME}"
mkdir -p "${WORK}"

log "Lustre mounted. Starting benchmarks (duration=${DURATION_MIN}m per phase)..."

# ---------- phase 1: sequential throughput (swap-like large I/O) ------------
log "Phase 1/4: Sequential write throughput..."
fio --name=seq-write \
    --directory="${WORK}" \
    --ioengine=libaio \
    --direct=1 \
    --bs=1m \
    --size=4g \
    --numjobs=4 \
    --rw=write \
    --runtime="${DURATION_SEC}" \
    --time_based \
    --group_reporting \
    --output-format=json \
    --output="${RESULTS_DIR}/01-seq-write.json"

log "Phase 1b/4: Sequential read throughput..."
fio --name=seq-read \
    --directory="${WORK}" \
    --ioengine=libaio \
    --direct=1 \
    --bs=1m \
    --size=4g \
    --numjobs=4 \
    --rw=read \
    --runtime="${DURATION_SEC}" \
    --time_based \
    --group_reporting \
    --output-format=json \
    --output="${RESULTS_DIR}/02-seq-read.json"

# ---------- phase 2: random I/O (swap simulation) --------------------------
log "Phase 2/4: Random read/write (4K, swap simulation)..."
fio --name=rand-rw \
    --directory="${WORK}" \
    --ioengine=libaio \
    --direct=1 \
    --bs=4k \
    --size=2g \
    --numjobs=8 \
    --rw=randrw \
    --rwmixread=70 \
    --runtime="${DURATION_SEC}" \
    --time_based \
    --group_reporting \
    --output-format=json \
    --output="${RESULTS_DIR}/03-rand-rw.json"

# ---------- phase 3: data transformation (read-transform-write) -------------
log "Phase 3/4: Data transformation pipeline (read+checksum+write)..."

# Create source data if this is the first VM or it doesn't exist
SRC_FILE="${LUSTRE_MOUNT_PATH}/work/shared-source-${VM_INDEX}.dat"
dd if=/dev/urandom of="${SRC_FILE}" bs=1M count=1024 status=progress 2>/dev/null || true

TRANSFORM_START=$(date +%s%N)
CHUNKS_PROCESSED=0
TRANSFORM_END=$(($(date +%s) + DURATION_SEC))

while [ "$(date +%s)" -lt "${TRANSFORM_END}" ]; do
  # Read chunk, compute checksum, write transformed output
  dd if="${SRC_FILE}" bs=1M count=64 skip=$((CHUNKS_PROCESSED % 16)) 2>/dev/null \
    | md5sum \
    | dd of="${WORK}/transformed-${CHUNKS_PROCESSED}.dat" bs=4k 2>/dev/null
  CHUNKS_PROCESSED=$((CHUNKS_PROCESSED + 1))
done

TRANSFORM_ELAPSED=$(( ($(date +%s%N) - TRANSFORM_START) / 1000000 ))

cat > "${RESULTS_DIR}/04-transform.json" <<XEOF
{
  "hostname": "${HOSTNAME}",
  "vm_index": ${VM_INDEX},
  "chunks_processed": ${CHUNKS_PROCESSED},
  "elapsed_ms": ${TRANSFORM_ELAPSED},
  "chunks_per_second": $(echo "scale=2; ${CHUNKS_PROCESSED} * 1000 / ${TRANSFORM_ELAPSED}" | bc),
  "source_size_mb": 1024,
  "chunk_size_mb": 64
}
XEOF

# ---------- phase 4: multi-client metadata storm ----------------------------
log "Phase 4/4: Metadata operations (create/stat/delete)..."
META_DIR="${LUSTRE_MOUNT_PATH}/work/meta-${HOSTNAME}"
mkdir -p "${META_DIR}"

META_START=$(date +%s%N)
FILES_CREATED=0
META_END=$(($(date +%s) + DURATION_SEC / 2))

while [ "$(date +%s)" -lt "${META_END}" ]; do
  touch "${META_DIR}/file-${FILES_CREATED}"
  FILES_CREATED=$((FILES_CREATED + 1))
done

STAT_START=$(date +%s%N)
find "${META_DIR}" -maxdepth 1 -type f | xargs -P8 stat >/dev/null 2>&1
STAT_ELAPSED=$(( ($(date +%s%N) - STAT_START) / 1000000 ))

RM_START=$(date +%s%N)
rm -rf "${META_DIR}"
RM_ELAPSED=$(( ($(date +%s%N) - RM_START) / 1000000 ))

META_ELAPSED=$(( ($(date +%s%N) - META_START) / 1000000 ))

cat > "${RESULTS_DIR}/05-metadata.json" <<XEOF
{
  "hostname": "${HOSTNAME}",
  "vm_index": ${VM_INDEX},
  "files_created": ${FILES_CREATED},
  "create_elapsed_ms": ${META_ELAPSED},
  "creates_per_second": $(echo "scale=2; ${FILES_CREATED} * 1000 / ${META_ELAPSED}" | bc),
  "stat_all_elapsed_ms": ${STAT_ELAPSED},
  "delete_all_elapsed_ms": ${RM_ELAPSED}
}
XEOF

# ---------- summary ---------------------------------------------------------
log "Generating summary..."

# Extract key metrics from fio JSON
extract_fio() {
  local file="$1" direction="$2"
  jq -r ".jobs[0].${direction}.bw_mean // .jobs[0].${direction}.bw" "$file" 2>/dev/null || echo "0"
}

SEQ_WRITE_BW=$(extract_fio "${RESULTS_DIR}/01-seq-write.json" "write")
SEQ_READ_BW=$(extract_fio "${RESULTS_DIR}/02-seq-read.json" "read")
RAND_READ_BW=$(extract_fio "${RESULTS_DIR}/03-rand-rw.json" "read")
RAND_WRITE_BW=$(extract_fio "${RESULTS_DIR}/03-rand-rw.json" "write")

cat > "${RESULTS_DIR}/summary.json" <<XEOF
{
  "hostname": "${HOSTNAME}",
  "vm_index": ${VM_INDEX},
  "vm_count": ${VM_COUNT},
  "machine_type": "$(meta ../machine-type 2>/dev/null | awk -F/ '{print $NF}' || echo unknown)",
  "duration_minutes": ${DURATION_MIN},
  "swap_performance": {
    "sequential_write_kbps": ${SEQ_WRITE_BW},
    "sequential_read_kbps": ${SEQ_READ_BW},
    "random_rw_read_kbps": ${RAND_READ_BW},
    "random_rw_write_kbps": ${RAND_WRITE_BW},
    "description": "Sequential 1M I/O simulates swap-out/in; random 4K simulates page faults"
  },
  "data_transform_performance": {
    "chunks_processed": ${CHUNKS_PROCESSED},
    "chunks_per_second": $(echo "scale=2; ${CHUNKS_PROCESSED} * 1000 / ${TRANSFORM_ELAPSED}" | bc),
    "elapsed_ms": ${TRANSFORM_ELAPSED},
    "description": "Read 64MB chunks, compute md5, write result — measures read-transform-write pipeline"
  },
  "metadata_performance": {
    "files_created": ${FILES_CREATED},
    "creates_per_second": $(echo "scale=2; ${FILES_CREATED} * 1000 / ${META_ELAPSED}" | bc),
    "stat_all_ms": ${STAT_ELAPSED},
    "delete_all_ms": ${RM_ELAPSED}
  }
}
XEOF

log "Results written to ${RESULTS_DIR}/summary.json"

# ---------- upload to GCS if configured -------------------------------------
if [ -n "${RESULTS_BUCKET}" ]; then
  log "Uploading results to gs://${RESULTS_BUCKET}/results/${HOSTNAME}/..."
  gsutil -m cp -r "${RESULTS_DIR}/" "gs://${RESULTS_BUCKET}/results/${HOSTNAME}/"
  log "Upload complete."
fi

log "All benchmarks complete for ${HOSTNAME}."
