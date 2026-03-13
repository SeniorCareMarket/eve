output "lustre_instance_name" {
  description = "Managed Lustre instance name"
  value       = google_lustre_instance.bench.name
}

output "lustre_mount_point" {
  description = "Lustre mount point reported by the instance (use in mount command)"
  value       = google_lustre_instance.bench.mount_point
}

output "lustre_capacity_gib" {
  value = google_lustre_instance.bench.capacity_gib
}

output "vm_names" {
  description = "Names of benchmark VM instances"
  value       = google_compute_instance.bench[*].name
}

output "vm_internal_ips" {
  description = "Internal IPs of benchmark VMs"
  value       = google_compute_instance.bench[*].network_interface[0].network_ip
}

output "results_bucket" {
  description = "GCS bucket holding benchmark results (empty if not configured)"
  value       = var.results_bucket
}

output "read_results_cmd" {
  description = "Command to download all benchmark results"
  value       = var.results_bucket != "" ? "gsutil -m cp -r gs://${var.results_bucket}/results/ ./results/" : "Results stored on VMs at /mnt/lustre/results/"
}

output "ssh_to_vm_cmd" {
  description = "SSH to first VM via IAP tunnel"
  value       = "gcloud compute ssh ${local.prefix}-vm-0 --zone=${var.zone} --tunnel-through-iap"
}
