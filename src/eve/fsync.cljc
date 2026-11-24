(ns eve.fsync
  "Forcing a set of files with one device flush where that is safe (doc/jlog-redo-spec.md §9, option 1).

   A whole-domain force (a checkpoint, a dead writer's resolution, recovery, close, a fallback commit's classes) used
   to msync every file of the domain: each msync is an fdatasync of its range, and each fdatasync ends in a device
   cache flush — 10 to 40 flushes where one would do. Here each file's dirty pages are written back with
   sync_file_range (WAIT_BEFORE | WRITE | WAIT_AFTER: written to the device, no flush), then ONE fdatasync makes them
   durable with its flush (ext4 and xfs end every fdatasync with a device cache flush).

   That is enough only for a file whose metadata needs no forcing: no block to allocate (no hole — a page written into
   a hole is a new block the file's extent tree must record) and no size change since its last fdatasync. So a file is
   trusted only when it is hole-free (lseek SEEK_HOLE answers its size), on ext4 or xfs, and its inode and size are the
   ones THIS process saw hole-free when it last fdatasync'd it; every other file gets its own fdatasync (and is trusted
   the next time). A new domain's slab files are written in full by their creator, before any other process can open
   them (eve.deftype-proto.alloc, fill-zero!), so they start hole-free. A class that grows has holes in its new half
   (filling it then would race other processes allocating there) and its files are forced one by one until their
   pages have all been written; files of a domain made before this change, likewise.

   A host supplies the system calls where it has them — its eve.region namespace registers them (register!: open,
   fstat, lseek, fstatfs, sync_file_range, fdatasync, close) on Linux under jolt, Node, babashka and the JVM (FFM); a
   host that registers none (or answers nil: not Linux, an old libc, no addon) is forced region by region by its
   caller, as before. The rule above is this namespace's, once, for every host. This namespace requires nothing, so
   every host compiles it.")

(def ^:const EXT4_SUPER_MAGIC 0xEF53)
(def ^:const XFS_SUPER_MAGIC 0x58465342)

(defonce ^:private trusted
  ;; path -> [inode size]: the file as this process last fdatasync'd it, hole-free on ext4/xfs
  (atom {}))

(defonce ^:private provider
  ;; (fn [] syscalls-map-or-nil), registered by the host's region namespace
  (atom nil))

(defn register!
  "The host's file syscalls: F, a fn of no arguments answering {:open-ro (fn [path] fd, negative when it cannot
   open) :fstat (fn [fd] [inode size]) :seek-hole (fn [fd] the offset of the first hole) :fs-type (fn [fd] statfs
   f_type) :sync-range (fn [fd] sync_file_range of the whole file, WAIT_BEFORE|WRITE|WAIT_AFTER) :fdatasync (fn [fd])
   :close (fn [fd])} — or nil when they are not available here. Errors throw (a force must never report success over a
   write that did not happen)."
  [f]
  (reset! provider f)
  nil)

(defn- file-syscalls [] (when-let [f @provider] (f)))

(defn available?
  "This host can force a set of files with one flush."
  []
  (some? (file-syscalls)))

(defn sync-files!
  "Force the files at PATHS (those that exist) to durable storage: trusted files written back with sync_file_range
   and made durable by one fdatasync, every other file by its own fdatasync. Answers the fdatasync calls made (the
   device flushes), or nil when the host has no file syscalls (the caller forces its regions instead)."
  [paths]
  (when-let [{:keys [open-ro fstat seek-hole fs-type sync-range fdatasync close]} (file-syscalls)]
    (let [opened (into [] (keep (fn [p] (let [fd (open-ro p)] (when (>= fd 0) [p fd])))) (distinct paths))]
      (try
        (let [[barrier flushes]
              (reduce (fn [[barrier flushes] [p fd]]
                        (let [[ino size] (fstat fd)
                              clean? (and (contains? #{EXT4_SUPER_MAGIC XFS_SUPER_MAGIC} (fs-type fd))
                                          (== size (seek-hole fd)))]
                          (if (and clean? (= [ino size] (get @trusted p)))
                            (do (sync-range fd) [(conj barrier fd) flushes])
                            (do (fdatasync fd)
                                (if clean? (swap! trusted assoc p [ino size]) (swap! trusted dissoc p))
                                [barrier (inc flushes)]))))
                      [[] 0]
                      opened)]
          (if (seq barrier)
            (do (fdatasync (first barrier)) (inc flushes))
            flushes))
        (finally
          (doseq [[_ fd] opened] (close fd)))))))
