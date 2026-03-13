# Lustre Benchmark — GCP Managed Lustre

Terraform config that stands up a GCP Managed Lustre filesystem with multiple
compute instances running I/O stress tests. Produces JSON results measuring
swap performance and data transformation throughput.

## Architecture

```
┌──────────────┐
│  Cloud NAT   │ (outbound for package installs)
└──────┬───────┘
       │
┌──────┴───────────────────────────────────────┐
│              VPC (MTU 8896)                   │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐        │
│  │  VM-0   │ │  VM-1   │ │  VM-2   │        │
│  │ c3-std-8│ │ c3-std-8│ │ c3-std-8│        │
│  └────┬────┘ └────┬────┘ └────┬────┘        │
│       │           │           │              │
│  ┌────┴───────────┴───────────┴────┐         │
│  │    Managed Lustre (18+ TiB)     │         │
│  │    DDN EXAScaler / benchfs      │         │
│  └─────────────────────────────────┘         │
└──────────────────────────────────────────────┘
```

## Benchmarks run

| Phase | What it measures | Tool |
|-------|-----------------|------|
| Sequential R/W | Swap-like large block throughput (1M blocks) | fio |
| Random R/W | Page-fault swap simulation (4K blocks, 70/30 mix) | fio |
| Transform pipeline | Read 64MB → md5 → write result (throughput) | dd + md5sum |
| Metadata storm | create/stat/delete thousands of files | touch/stat/rm |

## Quick start (recommended)

```bash
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your project_id

./run-bench.sh
# Creates infra → waits for benchmarks → collects results → destroys everything
# Results saved to ./results-YYYYMMDD-HHMMSS/
```

Flags:
- `--no-destroy` — leave infrastructure running after collecting results
- `--timeout 120` — abort after 120 minutes (default: 90)

## Manual usage

```bash
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your project_id

terraform init
terraform apply

# Wait for VMs to finish (~4x bench_duration_minutes)

# Read results from Lustre via SSH:
gcloud compute ssh lustre-bench-vm-0 --zone=us-central1-a --tunnel-through-iap \
  -- cat /mnt/lustre/results/*/summary.json

# Or from GCS if results_bucket was set:
gsutil cat gs://BUCKET/results/*/summary.json

# Tear down when done
terraform destroy
```

## Cost estimate

At minimum config (18 TiB Lustre, 3x c3-standard-8, 30min run):
- Lustre: ~$0.14/GiB/month → ~$2,520/month (prorated per-second)
- VMs: 3x c3-standard-8 → ~$1.10/hr total
- For a 1-hour experiment: ~$5 compute + ~$3.50 Lustre ≈ **~$9**
