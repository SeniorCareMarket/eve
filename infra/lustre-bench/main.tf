terraform {
  required_version = ">= 1.5.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 7.5.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}

locals {
  prefix         = "lustre-bench"
  lustre_fs_name = "benchfs"
  mount_point    = "/mnt/lustre"
}

# ---------------------------------------------------------------------------
# Networking — VPC with Private Service Access for Managed Lustre
# ---------------------------------------------------------------------------

resource "google_compute_network" "bench" {
  name                    = "${local.prefix}-vpc"
  auto_create_subnetworks = false
  mtu                     = 8896 # jumbo frames — up to 10% perf boost
}

resource "google_compute_subnetwork" "bench" {
  name          = "${local.prefix}-subnet"
  network       = google_compute_network.bench.id
  ip_cidr_range = "10.0.0.0/24"
  region        = var.region
}

# Internal IP range for VPC peering (Lustre control plane)
resource "google_compute_global_address" "lustre_peering" {
  name          = "${local.prefix}-lustre-range"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 24
  network       = google_compute_network.bench.id
}

resource "google_service_networking_connection" "lustre" {
  network                 = google_compute_network.bench.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.lustre_peering.name]
}

# Allow TCP from Lustre peering range into VMs
resource "google_compute_firewall" "lustre_ingress" {
  name    = "${local.prefix}-lustre-allow"
  network = google_compute_network.bench.id

  allow {
    protocol = "tcp"
  }

  source_ranges = [google_compute_global_address.lustre_peering.address]
  direction     = "INGRESS"
}

# Allow SSH from IAP for debugging
resource "google_compute_firewall" "iap_ssh" {
  name    = "${local.prefix}-iap-ssh"
  network = google_compute_network.bench.id

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = ["35.235.240.0/20"] # IAP range
  direction     = "INGRESS"
}

# NAT for outbound (package installs)
resource "google_compute_router" "bench" {
  name    = "${local.prefix}-router"
  network = google_compute_network.bench.id
  region  = var.region
}

resource "google_compute_router_nat" "bench" {
  name                               = "${local.prefix}-nat"
  router                             = google_compute_router.bench.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}

# ---------------------------------------------------------------------------
# Managed Lustre instance
# ---------------------------------------------------------------------------

resource "google_lustre_instance" "bench" {
  instance_id  = "${local.prefix}-fs"
  location     = var.zone
  filesystem   = local.lustre_fs_name
  capacity_gib = var.lustre_capacity_gib
  network      = "projects/${var.project_id}/global/networks/${google_compute_network.bench.name}"

  per_unit_storage_throughput = var.lustre_throughput_tier

  labels = var.labels

  depends_on = [google_service_networking_connection.lustre]
}

# ---------------------------------------------------------------------------
# Optional GCS bucket for results collection
# ---------------------------------------------------------------------------

resource "google_storage_bucket" "results" {
  count         = var.results_bucket != "" ? 1 : 0
  name          = var.results_bucket
  location      = var.region
  force_destroy = true
  labels        = var.labels

  lifecycle_rule {
    condition { age = 30 }
    action { type = "Delete" }
  }
}

# ---------------------------------------------------------------------------
# Service account for benchmark VMs
# ---------------------------------------------------------------------------

resource "google_service_account" "bench" {
  account_id   = "${local.prefix}-vm"
  display_name = "Lustre benchmark VM"
}

resource "google_project_iam_member" "bench_log_writer" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.bench.email}"
}

resource "google_project_iam_member" "bench_metric_writer" {
  project = var.project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.bench.email}"
}

resource "google_storage_bucket_iam_member" "bench_writer" {
  count  = var.results_bucket != "" ? 1 : 0
  bucket = google_storage_bucket.results[0].name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${google_service_account.bench.email}"
}

# ---------------------------------------------------------------------------
# Benchmark VM instances
# ---------------------------------------------------------------------------

resource "google_compute_instance" "bench" {
  count        = var.vm_count
  name         = "${local.prefix}-vm-${count.index}"
  machine_type = var.vm_machine_type
  zone         = var.zone
  labels       = var.labels

  boot_disk {
    initialize_params {
      image = "${var.vm_image_project}/${var.vm_image}"
      size  = 50
      type  = "pd-ssd"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.bench.id
    # No external IP — egress via Cloud NAT
  }

  service_account {
    email  = google_service_account.bench.email
    scopes = ["cloud-platform"]
  }

  metadata = {
    lustre-mount-name  = google_lustre_instance.bench.mount_point
    lustre-mount-path  = local.mount_point
    results-bucket     = var.results_bucket
    bench-duration-min = var.bench_duration_minutes
    vm-index           = count.index
    vm-count           = var.vm_count
  }

  metadata_startup_script = file("${path.module}/scripts/bench-startup.sh")

  tags = ["lustre-client"]

  depends_on = [
    google_lustre_instance.bench,
    google_compute_router_nat.bench,
  ]
}
