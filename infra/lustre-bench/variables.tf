variable "project_id" {
  description = "GCP project ID"
  type        = string
}

variable "region" {
  description = "GCP region"
  type        = string
  default     = "us-central1"
}

variable "zone" {
  description = "GCP zone (Lustre instance and VMs must share a zone)"
  type        = string
  default     = "us-central1-a"
}

variable "lustre_capacity_gib" {
  description = "Managed Lustre capacity in GiB (min 18000, increments of 9000)"
  type        = number
  default     = 18000
}

variable "lustre_throughput_tier" {
  description = "MB/s per TiB throughput tier: 125, 250, 500, or 1000"
  type        = number
  default     = 250
}

variable "vm_count" {
  description = "Number of benchmark VM instances"
  type        = number
  default     = 3
}

variable "vm_machine_type" {
  description = "Machine type for benchmark VMs"
  type        = string
  default     = "c3-standard-8"
}

variable "vm_image" {
  description = "OS image for benchmark VMs (must support Lustre client)"
  type        = string
  default     = "rocky-linux-optimized-gcp-9-v20250101"
}

variable "vm_image_project" {
  description = "Project hosting the VM image"
  type        = string
  default     = "rocky-linux-cloud"
}

variable "bench_duration_minutes" {
  description = "How long each benchmark phase runs (minutes)"
  type        = number
  default     = 10
}

variable "results_bucket" {
  description = "GCS bucket name for collecting results (created if non-empty)"
  type        = string
  default     = ""
}

variable "labels" {
  description = "Labels to apply to all resources"
  type        = map(string)
  default = {
    purpose = "lustre-bench"
    managed = "terraform"
  }
}
