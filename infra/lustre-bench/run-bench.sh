#!/usr/bin/env bash
# Wrapper: terraform apply → wait for results → collect → terraform destroy
# Usage: ./run-bench.sh [--no-destroy] [--timeout MINUTES]
set -euo pipefail
cd "$(dirname "$0")"

# ---------- flags -------------------------------------------------------------
AUTO_DESTROY=true
TIMEOUT_MIN=90  # total wallclock timeout (default: 90 min)
POLL_SEC=30

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-destroy)  AUTO_DESTROY=false; shift ;;
    --timeout)     TIMEOUT_MIN="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 [--no-destroy] [--timeout MINUTES]"
      echo "  --no-destroy   Skip terraform destroy (leave infra running)"
      echo "  --timeout N    Abort after N minutes (default: 90)"
      exit 0 ;;
    *) echo "Unknown flag: $1" >&2; exit 1 ;;
  esac
done

log() { echo "[run-bench $(date -Iseconds)] $*"; }

# ---------- preflight ---------------------------------------------------------
if [ ! -f terraform.tfvars ]; then
  echo "ERROR: terraform.tfvars not found. Copy terraform.tfvars.example and fill in your project_id." >&2
  exit 1
fi

command -v terraform >/dev/null || { echo "ERROR: terraform not found" >&2; exit 1; }
command -v gcloud >/dev/null    || { echo "ERROR: gcloud not found" >&2; exit 1; }

# Read key variables for later use
PROJECT=$(grep -oP 'project_id\s*=\s*"\K[^"]+' terraform.tfvars)
ZONE=$(grep -oP 'zone\s*=\s*"\K[^"]+' terraform.tfvars 2>/dev/null || echo "us-central1-a")
VM_COUNT=$(grep -oP 'vm_count\s*=\s*\K[0-9]+' terraform.tfvars 2>/dev/null || echo "3")
DURATION_MIN=$(grep -oP 'bench_duration_minutes\s*=\s*\K[0-9]+' terraform.tfvars 2>/dev/null || echo "10")
RESULTS_BUCKET=$(grep -oP 'results_bucket\s*=\s*"\K[^"]+' terraform.tfvars 2>/dev/null || echo "")

# Estimated benchmark runtime: 4 phases × duration + 10 min overhead
BENCH_EST=$(( DURATION_MIN * 4 + 10 ))

log "Project: ${PROJECT}"
log "Zone: ${ZONE}"
log "VMs: ${VM_COUNT}, Duration per phase: ${DURATION_MIN}m"
log "Estimated benchmark time: ~${BENCH_EST}m"
log "Auto-destroy: ${AUTO_DESTROY}"

# ---------- trap for cleanup on failure ---------------------------------------
cleanup() {
  local exit_code=$?
  if [ "${AUTO_DESTROY}" = true ] && [ $exit_code -ne 0 ]; then
    log "ERROR detected (exit code ${exit_code}). Running terraform destroy to avoid orphaned resources..."
    terraform destroy -auto-approve 2>&1 | tail -5
  fi
}
trap cleanup EXIT

# ---------- terraform apply ---------------------------------------------------
log "Running terraform init..."
terraform init -input=false

log "Running terraform apply..."
terraform apply -auto-approve -input=false
log "Infrastructure created."

# ---------- wait for benchmarks to finish -------------------------------------
log "Waiting for benchmarks to complete on ${VM_COUNT} VMs (polling every ${POLL_SEC}s, timeout ${TIMEOUT_MIN}m)..."

DEADLINE=$(( $(date +%s) + TIMEOUT_MIN * 60 ))
COMPLETED=0

check_vm_done() {
  local idx=$1
  local vm_name="lustre-bench-vm-${idx}"
  # Check if the startup script logged "All benchmarks complete"
  gcloud compute instances get-serial-port-output "${vm_name}" \
    --zone="${ZONE}" --project="${PROJECT}" 2>/dev/null \
    | grep -q "All benchmarks complete" && return 0
  return 1
}

while [ "${COMPLETED}" -lt "${VM_COUNT}" ]; do
  if [ "$(date +%s)" -gt "${DEADLINE}" ]; then
    log "TIMEOUT after ${TIMEOUT_MIN}m. Only ${COMPLETED}/${VM_COUNT} VMs finished."
    break
  fi

  COMPLETED=0
  for i in $(seq 0 $((VM_COUNT - 1))); do
    if check_vm_done "$i"; then
      COMPLETED=$((COMPLETED + 1))
    fi
  done

  if [ "${COMPLETED}" -lt "${VM_COUNT}" ]; then
    log "  ${COMPLETED}/${VM_COUNT} VMs done. Waiting ${POLL_SEC}s..."
    sleep "${POLL_SEC}"
  fi
done

log "${COMPLETED}/${VM_COUNT} VMs completed benchmarks."

# ---------- collect results ---------------------------------------------------
RESULTS_LOCAL="./results-$(date +%Y%m%d-%H%M%S)"
mkdir -p "${RESULTS_LOCAL}"

if [ -n "${RESULTS_BUCKET}" ]; then
  log "Downloading results from gs://${RESULTS_BUCKET}/results/..."
  gsutil -m cp -r "gs://${RESULTS_BUCKET}/results/" "${RESULTS_LOCAL}/" 2>/dev/null || true
else
  log "Downloading results from VMs via SSH..."
  for i in $(seq 0 $((VM_COUNT - 1))); do
    vm_name="lustre-bench-vm-${i}"
    log "  Collecting from ${vm_name}..."
    mkdir -p "${RESULTS_LOCAL}/${vm_name}"
    gcloud compute ssh "${vm_name}" \
      --zone="${ZONE}" --project="${PROJECT}" \
      --tunnel-through-iap --command="cat /mnt/lustre/results/*/summary.json" \
      2>/dev/null > "${RESULTS_LOCAL}/${vm_name}/summary.json" || true
    # Also grab raw fio output
    for f in 01-seq-write 02-seq-read 03-rand-rw 04-transform 05-metadata; do
      gcloud compute ssh "${vm_name}" \
        --zone="${ZONE}" --project="${PROJECT}" \
        --tunnel-through-iap --command="cat /mnt/lustre/results/*/${f}.json" \
        2>/dev/null > "${RESULTS_LOCAL}/${vm_name}/${f}.json" || true
    done
  done
fi

log "Results saved to ${RESULTS_LOCAL}/"

# ---------- print summary -----------------------------------------------------
log "=== BENCHMARK SUMMARY ==="
for summary in "${RESULTS_LOCAL}"/*/summary.json; do
  [ -f "$summary" ] || continue
  echo "--- $(dirname "$summary" | xargs basename) ---"
  if command -v jq &>/dev/null; then
    jq -r '
      "  Seq Write: \(.swap_performance.sequential_write_kbps // "N/A") KB/s",
      "  Seq Read:  \(.swap_performance.sequential_read_kbps // "N/A") KB/s",
      "  Rand R/W:  \(.swap_performance.random_rw_read_kbps // "N/A") / \(.swap_performance.random_rw_write_kbps // "N/A") KB/s",
      "  Transform: \(.data_transform_performance.chunks_per_second // "N/A") chunks/s",
      "  Metadata:  \(.metadata_performance.creates_per_second // "N/A") creates/s"
    ' "$summary" 2>/dev/null || cat "$summary"
  else
    cat "$summary"
  fi
done

# ---------- terraform destroy -------------------------------------------------
if [ "${AUTO_DESTROY}" = true ]; then
  log "Tearing down infrastructure..."
  terraform destroy -auto-approve -input=false
  log "All resources destroyed."
else
  log "Skipping destroy (--no-destroy). Remember to run: terraform destroy"
fi

log "Done. Results in ${RESULTS_LOCAL}/"
