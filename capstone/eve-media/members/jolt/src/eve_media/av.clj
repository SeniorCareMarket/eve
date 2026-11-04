(ns eve-media.av
  "FFmpeg's libraries — libavformat, libavcodec, libswscale, libswresample, libavutil — called from jolt through its
   FFI (Chez Scheme's foreign procedures; no binding library, no C of eve-media's): the native transcoder (a web-ready
   MP4, H.264 by libx264 and AAC, the same settings as the CLI's: preset medium, CRF 23, yuv420p, 128 kb/s stereo
   48 kHz, single-threaded and bit-exact, so a rerun writes the same bytes) and the native PCM decoder (f32le, stereo,
   48 kHz, into a window file — the data shape the loudness pool reads in place).

   The codec contexts are reached through AVOptions (av_opt_set / av_opt_get by name, an option's field offset from
   its AVOption) but for one field, the AAC encoder's sample_fmt; the few structs read here (AVFormatContext's and
   AVStream's leading fields, AVPacket, AVFrame) are declared as layouts. FFmpeg 6.1, 7.x and 8.x (libavutil 58, 59
   and 60) are read, on Linux and macOS: what moves between them — three AVFrame fields and the AAC encoder's
   sample_fmt — is in `release`, each offset measured with offsetof against that release's own headers (LP64:
   x86-64 and arm64 alike); everything else read here sits at the same offset in all three. Checked when first used:
   the libraries' majors against each other, a fresh frame's and a fresh packet's defaults, a decoded frame's channel
   count and rate against its decoder's, a stream's index against its position, the encoder's sample_fmt by its
   neighbours' offsets — a library of another ABI raises instead of misreading.

   The libraries are found by their major (libavutil.so.59, libavutil.59.dylib) on the loader's path, in
   EVE_FFMPEG_LIB when it names a directory, and on macOS in Homebrew's and MacPorts' lib directories."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]))

(def ^:private macos? (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "mac"))

(def ^:private releases
  "What eve-media.av reads that moves between FFmpeg releases, by libavutil's major: the other libraries' majors;
   AVFrame's pts, sample_rate, best_effort_timestamp and ch_layout offsets; and AVCodecContext's sample_fmt and
   frame_size, bytes after its sample_rate (FFmpeg 7 removed the channels field between them). Measured with offsetof
   against FFmpeg n6.1's, n7.1's and n8.0's headers (2026-10-22)."
  {58 {:name "6.1" :libs {"avcodec" 60 "avformat" 60 "swscale" 7 "swresample" 4}
       :pts 136 :sample-rate 208 :best-effort 344 :ch-layout 448 :sample-fmt 8 :frame-size 12}
   59 {:name "7.x" :libs {"avcodec" 61 "avformat" 61 "swscale" 8 "swresample" 5}
       :pts 136 :sample-rate 192 :best-effort 320 :ch-layout 408 :sample-fmt 4 :frame-size 32}
   60 {:name "8.x" :libs {"avcodec" 62 "avformat" 62 "swscale" 9 "swresample" 6}
       :pts 136 :sample-rate 180 :best-effort 304 :ch-layout 384 :sample-fmt 4 :frame-size 32}})

(defn- lib-candidates
  "Where library lib (avutil, avcodec, ...) of major m may be: the platform's file name in each directory to look in,
   then the bare name (the loader's own path)."
  [lib m]
  (let [file (if macos? (str "lib" lib "." m ".dylib") (str "lib" lib ".so." m))
        dirs (concat (keep (fn [d] (when (seq d) d)) [(System/getenv "EVE_FFMPEG_LIB")])
                     (when macos? ["/opt/homebrew/lib" "/usr/local/lib" "/opt/local/lib"]))]
    (vec (concat (map (fn [d] (str d "/" file)) dirs) [file]))))

(def ^:private avutil-major
  "The major of the libavutil loaded: the newest of releases' found."
  (or (some (fn [m] (try (ffi/load-library (lib-candidates "avutil" m)) m (catch Throwable _ nil)))
            (sort > (keys releases)))
      (throw (ex-info (str "eve-media.av: no libavutil of FFmpeg " (str/join ", " (map :name (vals releases)))
                           " found (EVE_FFMPEG_LIB: the directory holding FFmpeg's libraries)")
                      {:looked-for (mapv (fn [m] (lib-candidates "avutil" m)) (keys releases))}))))

(def ^:private release (get releases avutil-major))

(doseq [[lib m] (:libs release)]
  (ffi/load-library (lib-candidates lib m)))
(when-not macos? (ffi/load-library "libc.so.6"))

;; ---------------------------------------------------------------------------
;; The functions
;; ---------------------------------------------------------------------------

(ffi/defcfn avutil-version "avutil_version" [] :uint32)
(ffi/defcfn avcodec-version "avcodec_version" [] :uint32)
(ffi/defcfn avformat-version "avformat_version" [] :uint32)
(ffi/defcfn av-log-set-level "av_log_set_level" [:int] :void)
(ffi/defcfn av-strerror "av_strerror" [:int :pointer :size_t] :int)
(ffi/defcfn avformat-open-input "avformat_open_input" [:pointer :string :pointer :pointer] :int)
(ffi/defcfn avformat-find-stream-info "avformat_find_stream_info" [:pointer :pointer] :int {:blocking true})
(ffi/defcfn av-find-best-stream "av_find_best_stream" [:pointer :int :int :int :pointer :int] :int)
(ffi/defcfn avformat-close-input "avformat_close_input" [:pointer] :void)
(ffi/defcfn av-read-frame "av_read_frame" [:pointer :pointer] :int {:blocking true})
(ffi/defcfn avformat-alloc-output-context2 "avformat_alloc_output_context2" [:pointer :pointer :string :string] :int)
(ffi/defcfn avformat-new-stream "avformat_new_stream" [:pointer :pointer] :pointer)
(ffi/defcfn avio-open "avio_open" [:pointer :string :int] :int)
(ffi/defcfn avio-closep "avio_closep" [:pointer] :int)
(ffi/defcfn avformat-write-header "avformat_write_header" [:pointer :pointer] :int)
(ffi/defcfn av-interleaved-write-frame "av_interleaved_write_frame" [:pointer :pointer] :int {:blocking true})
(ffi/defcfn av-write-trailer "av_write_trailer" [:pointer] :int {:blocking true})
(ffi/defcfn avformat-free-context "avformat_free_context" [:pointer] :void)

(ffi/defcfn avcodec-alloc-context3 "avcodec_alloc_context3" [:pointer] :pointer)
(ffi/defcfn avcodec-free-context "avcodec_free_context" [:pointer] :void)
(ffi/defcfn avcodec-parameters-to-context "avcodec_parameters_to_context" [:pointer :pointer] :int)
(ffi/defcfn avcodec-parameters-from-context "avcodec_parameters_from_context" [:pointer :pointer] :int)
(ffi/defcfn avcodec-open2 "avcodec_open2" [:pointer :pointer :pointer] :int {:blocking true})
(ffi/defcfn avcodec-find-encoder-by-name "avcodec_find_encoder_by_name" [:string] :pointer)
(ffi/defcfn avcodec-send-packet "avcodec_send_packet" [:pointer :pointer] :int {:blocking true})
(ffi/defcfn avcodec-receive-frame "avcodec_receive_frame" [:pointer :pointer] :int {:blocking true})
(ffi/defcfn avcodec-send-frame "avcodec_send_frame" [:pointer :pointer] :int {:blocking true})
(ffi/defcfn avcodec-receive-packet "avcodec_receive_packet" [:pointer :pointer] :int {:blocking true})

(ffi/defcfn av-packet-alloc "av_packet_alloc" [] :pointer)
(ffi/defcfn av-packet-free "av_packet_free" [:pointer] :void)
(ffi/defcfn av-packet-unref "av_packet_unref" [:pointer] :void)
(ffi/defcfn av-frame-alloc "av_frame_alloc" [] :pointer)
(ffi/defcfn av-frame-free "av_frame_free" [:pointer] :void)
(ffi/defcfn av-frame-unref "av_frame_unref" [:pointer] :void)
(ffi/defcfn av-frame-get-buffer "av_frame_get_buffer" [:pointer :int] :int)
(ffi/defcfn av-frame-make-writable "av_frame_make_writable" [:pointer] :int)

(ffi/defcfn av-dict-set "av_dict_set" [:pointer :string :string :int] :int)
(ffi/defcfn av-dict-free "av_dict_free" [:pointer] :void)
(ffi/defcfn av-opt-set "av_opt_set" [:pointer :string :string :int] :int)
(ffi/defcfn av-opt-set-int "av_opt_set_int" [:pointer :string :int64 :int] :int)
(ffi/defcfn av-opt-get "av_opt_get" [:pointer :string :int :pointer] :int)
(ffi/defcfn av-opt-get-int "av_opt_get_int" [:pointer :string :int :pointer] :int)
(ffi/defcfn av-opt-find "av_opt_find" [:pointer :string :string :int :int] :pointer)
(ffi/defcfn av-free "av_free" [:pointer] :void)
(ffi/defcfn av-freep "av_freep" [:pointer] :void)
(ffi/defcfn av-get-pix-fmt "av_get_pix_fmt" [:string] :int)
(ffi/defcfn av-rescale-rnd "av_rescale_rnd" [:int64 :int64 :int64 :int] :int64)

(ffi/defcfn sws-get-context "sws_getContext" [:int :int :int :int :int :int :int :pointer :pointer :pointer] :pointer)
(ffi/defcfn sws-scale "sws_scale" [:pointer :pointer :pointer :int :int :pointer :pointer] :int {:blocking true})
(ffi/defcfn sws-free-context "sws_freeContext" [:pointer] :void)

(ffi/defcfn swr-alloc-set-opts2 "swr_alloc_set_opts2" [:pointer :pointer :int :int :pointer :int :int :int :pointer] :int)
(ffi/defcfn swr-init "swr_init" [:pointer] :int)
(ffi/defcfn swr-convert "swr_convert" [:pointer :pointer :int :pointer :int] :int {:blocking true})
(ffi/defcfn swr-get-out-samples "swr_get_out_samples" [:pointer :int] :int)
(ffi/defcfn swr-free "swr_free" [:pointer] :void)
(ffi/defcfn av-channel-layout-default "av_channel_layout_default" [:pointer :int] :void)
(ffi/defcfn av-channel-layout-from-string "av_channel_layout_from_string" [:pointer :string] :int)
(ffi/defcfn av-channel-layout-copy "av_channel_layout_copy" [:pointer :pointer] :int)
(ffi/defcfn av-channel-layout-uninit "av_channel_layout_uninit" [:pointer] :void)
(ffi/defcfn av-samples-alloc "av_samples_alloc" [:pointer :pointer :int :int :int :int] :int)
(ffi/defcfn av-audio-fifo-alloc "av_audio_fifo_alloc" [:int :int :int] :pointer)
(ffi/defcfn av-audio-fifo-write "av_audio_fifo_write" [:pointer :pointer :int] :int)
(ffi/defcfn av-audio-fifo-read "av_audio_fifo_read" [:pointer :pointer :int] :int)
(ffi/defcfn av-audio-fifo-size "av_audio_fifo_size" [:pointer] :int)
(ffi/defcfn av-audio-fifo-free "av_audio_fifo_free" [:pointer] :void)

;; open(2) is variadic: the :& marker is what Apple arm64's calling convention needs for its mode
(ffi/defcfn c-open "open" [:string :int :& :int] :int)
(ffi/defcfn c-write "write" [:int :pointer :size_t] :ssize_t {:blocking true})
(ffi/defcfn c-close "close" [:int] :int)
(ffi/defcfn c-rename "rename" [:string :string] :int)
(ffi/defcfn c-getpid "getpid" [] :int)

;; ---------------------------------------------------------------------------
;; Constants (the same in FFmpeg 6.1, 7.x and 8.x; errno's and open(2)'s are the platform's)
;; ---------------------------------------------------------------------------

(def ^:private MEDIA-VIDEO 0)
(def ^:private MEDIA-AUDIO 1)
(def ^:private EAGAIN (if macos? -35 -11))      ; AVERROR(EAGAIN)
(def ^:private EOF -541478725)                    ; AVERROR_EOF, FFERRTAG('E','O','F',' ')
(def ^:private NOPTS -9223372036854775808)        ; AV_NOPTS_VALUE
(def ^:private ROUND (bit-or 5 8192))             ; AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX
(def ^:private SWS-BICUBIC 4)
(def ^:private FMT-FLT 3)
(def ^:private FMT-FLTP 8)
(def ^:private AVIO-WRITE 2)
(def ^:private SEARCH-CHILDREN 1)
(def ^:private O-WRONLY-CREAT-TRUNC (if macos? (bit-or 1 0x200 0x400) (bit-or 1 64 512)))

;; ---------------------------------------------------------------------------
;; The structs read here (their leading fields, the same in FFmpeg 6.1, 7.x and 8.x; AVFrame's later ones: release)
;; ---------------------------------------------------------------------------

(def ^:private format-context
  (ffi/layout [:struct [[:av-class :pointer] [:iformat :pointer] [:oformat :pointer] [:priv-data :pointer]
                        [:pb :pointer] [:ctx-flags :int] [:nb-streams :uint32] [:streams :pointer]]]))

(def ^:private stream
  (ffi/layout [:struct [[:av-class :pointer] [:index :int] [:id :int] [:codecpar :pointer] [:priv-data :pointer]
                        [:time-base [:struct [[:num :int] [:den :int]]]]
                        [:start-time :int64] [:duration :int64] [:nb-frames :int64] [:disposition :int] [:discard :int]
                        [:sample-aspect-ratio [:struct [[:num :int] [:den :int]]]] [:metadata :pointer]
                        [:avg-frame-rate [:struct [[:num :int] [:den :int]]]]]]))

(def ^:private packet
  (ffi/layout [:struct [[:buf :pointer] [:pts :int64] [:dts :int64] [:data :pointer] [:size :int] [:stream-index :int]
                        [:flags :int] [:side-data :pointer] [:side-data-elems :int] [:duration :int64] [:pos :int64]
                        [:opaque :pointer] [:opaque-ref :pointer] [:time-base [:struct [[:num :int] [:den :int]]]]]]))

(def ^:private frame
  ;; its leading fields, at the same offsets in every release (the rest moves: release)
  (ffi/layout [:struct [[:data [:array :pointer 8]] [:linesize [:array :int 8]] [:extended-data :pointer]
                        [:width :int] [:height :int] [:nb-samples :int] [:format :int]]]))

(def ^:private channel-layout
  (ffi/layout [:struct [[:order :int] [:nb-channels :int] [:mask :uint64] [:opaque :pointer]]]))

(defn- off [layout & path] (ffi/field-offset layout (vec path)))

(def ^:private F-DATA (off frame :data))
(def ^:private F-LINESIZE (off frame :linesize))
(def ^:private F-EXTENDED (off frame :extended-data))
(def ^:private F-WIDTH (off frame :width))
(def ^:private F-HEIGHT (off frame :height))
(def ^:private F-NB-SAMPLES (off frame :nb-samples))
(def ^:private F-FORMAT (off frame :format))
(def ^:private F-PTS (:pts release))
(def ^:private F-SAMPLE-RATE (:sample-rate release))
(def ^:private F-BEST-EFFORT (:best-effort release))
(def ^:private F-CH-LAYOUT (:ch-layout release))
(def ^:private F-CH-COUNT (+ (:ch-layout release) (off channel-layout :nb-channels)))
(def ^:private P-PTS (off packet :pts))
(def ^:private P-DTS (off packet :dts))
(def ^:private P-STREAM (off packet :stream-index))
(def ^:private P-DURATION (off packet :duration))
(def ^:private P-POS (off packet :pos))
(def ^:private S-INDEX (off stream :index))
(def ^:private S-CODECPAR (off stream :codecpar))
(def ^:private S-TB (off stream :time-base))
(def ^:private S-RATE (off stream :avg-frame-rate))
(def ^:private C-PB (off format-context :pb))
(def ^:private C-STREAMS (off format-context :streams))
(def ^:private C-NB-STREAMS (off format-context :nb-streams))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- rd [p t o] (ffi/read p t o))
(defn- wr! [p t o v] (ffi/write p t v o))

(defn- error-text [code]
  (let [buf (ffi/alloc 256)]
    (av-strerror code buf 256)
    (let [s (ffi/ptr->string buf)] (ffi/free buf) s)))

(defn- ok! [code what]
  (if (neg? code) (throw (ex-info (str "eve-media.av: " what ": " (error-text code)) {:code code :what what})) code))

(defn- abi! [ok? what]
  (when-not ok? (throw (ex-info (str "eve-media.av: FFmpeg's ABI is not " (:name release) "'s (" what ")") {:what what}))))

;; the libraries loaded are one release's: each one's major (a version's top 16 bits) as release says
(abi! (= [avutil-major (get-in release [:libs "avcodec"]) (get-in release [:libs "avformat"])]
         (mapv (fn [v] (bit-shift-right v 16)) [(avutil-version) (avcodec-version) (avformat-version)]))
      "the libraries' majors")

(defn- opt-str
  "An AVOption of obj as text (av_opt_get, freed here)."
  [a obj nm]
  (let [cell (ffi/alloc a :pointer)]
    (ok! (av-opt-get obj nm 0 cell) (str "av_opt_get " nm))
    (let [p (rd cell :pointer 0) s (ffi/ptr->string p)] (av-free p) s)))

(defn- opt-int [a obj nm]
  (let [cell (ffi/alloc a :int64)]
    (ok! (av-opt-get-int obj nm 0 cell) (str "av_opt_get_int " nm))
    (rd cell :int64 0)))

(defn- opt! [obj nm v flags]
  (ok! (av-opt-set obj nm (str v) flags) (str "av_opt_set " nm "=" v)))

(defn- opt-offset
  "The byte offset of an AVOption's field (AVOption.offset, an int at 16)."
  [obj nm]
  (let [o (av-opt-find obj nm nil 0 0)]
    (abi! (not (ffi/null? o)) (str "no option " nm))
    (rd o :int 16)))

(defn- rescale
  "a in time base [bn bd] rescaled to [cn cd] (av_rescale_q's rounding, NOPTS passed through)."
  [a [bn bd] [cn cd]]
  (if (= a NOPTS) a (av-rescale-rnd a (* bn cd) (* bd cn) ROUND)))

(defn- check-fresh-frame! [f]
  (abi! (and (= NOPTS (rd f :int64 F-PTS)) (= NOPTS (rd f :int64 F-BEST-EFFORT)) (= -1 (rd f :int F-FORMAT)))
        "a fresh AVFrame's defaults"))

(defn- check-fresh-packet! [p]
  (abi! (and (= NOPTS (rd p :int64 P-PTS)) (= NOPTS (rd p :int64 P-DTS)) (= -1 (rd p :int64 P-POS)))
        "a fresh AVPacket's defaults"))

(defn- new-frame [] (let [f (av-frame-alloc)] (check-fresh-frame! f) f))

(defn- free-frame! [a f] (let [c (ffi/alloc a :pointer)] (wr! c :pointer 0 f) (av-frame-free c)))

(defn- free-packet! [a p] (let [c (ffi/alloc a :pointer)] (wr! c :pointer 0 p) (av-packet-free c)))

;; ---------------------------------------------------------------------------
;; Input
;; ---------------------------------------------------------------------------

(defn- open-input [a path]
  (let [cell (ffi/alloc a :pointer)]
    (ok! (avformat-open-input cell path 0 0) (str "open " path))
    (let [ic (rd cell :pointer 0)]
      (ok! (avformat-find-stream-info ic 0) "find_stream_info")
      ic)))

(defn- stream-at [ic i]
  (let [s (rd (rd ic :pointer C-STREAMS) :pointer (* 8 i))]
    (abi! (= i (rd s :int S-INDEX)) "AVStream.index")
    s))

(defn- open-decoder
  "The best stream of media type t in ic, and its decoder opened: {:index :stream :ctx :tb [num den]} or nil."
  [a ic t]
  (let [dec (ffi/alloc a :pointer)
        i (av-find-best-stream ic t -1 -1 dec 0)]
    (when (>= i 0)
      (let [s (stream-at ic i)
            ctx (avcodec-alloc-context3 (rd dec :pointer 0))
            tb [(rd s :int S-TB) (rd s :int (+ S-TB 4))]]
        (ok! (avcodec-parameters-to-context ctx (rd s :pointer S-CODECPAR)) "parameters_to_context")
        (opt! ctx "pkt_timebase" (str (first tb) "/" (second tb)) 0)
        (opt! ctx "threads" 1 0)
        (ok! (avcodec-open2 ctx (rd dec :pointer 0) 0) "open decoder")
        {:index i :stream s :ctx ctx :tb tb
         :rate [(rd s :int S-RATE) (rd s :int (+ S-RATE 4))]}))))

(defn- decode!
  "Packet pkt (nil: the end) sent to decoder d; (on-frame f) for every frame it yields."
  [d pkt f on-frame]
  (let [r (avcodec-send-packet (:ctx d) (or pkt 0))]
    (when (and (neg? r) (not= r EOF) (not= r EAGAIN)) (ok! r "send_packet"))
    (loop []
      (let [r (avcodec-receive-frame (:ctx d) f)]
        (cond (or (= r EAGAIN) (= r EOF)) nil
              (neg? r) (ok! r "receive_frame")
              :else (do (on-frame f) (av-frame-unref f) (recur)))))))

(defn- channel-count [a ctx]
  (let [l (ffi/alloc a channel-layout)
        s (opt-str a ctx "ch_layout")]
    (if (neg? (av-channel-layout-from-string l s))
      (opt-int a ctx "ac")
      (let [n (rd l :int 4)] (av-channel-layout-uninit l) n))))

(defn- in-layout
  "The decoder's channel layout (a native one when it has none: by its channel count)."
  [a ctx]
  (let [l (ffi/alloc a channel-layout)
        s (opt-str a ctx "ch_layout")]
    (when (or (neg? (av-channel-layout-from-string l s)) (zero? (rd l :int 0)))
      (av-channel-layout-default l (channel-count a ctx)))
    l))

(defn- resampler
  "A resampler from the decoder's audio (its first frame's sample format) to out-fmt, stereo, 48 kHz."
  [a dctx first-frame out-fmt]
  (let [cell (ffi/alloc a :pointer)
        out (ffi/alloc a channel-layout)
        in (in-layout a dctx)
        in-rate (opt-int a dctx "ar")]
    (abi! (= in-rate (rd first-frame :int F-SAMPLE-RATE)) "AVFrame.sample_rate")
    (abi! (= (rd in :int 4) (rd first-frame :int F-CH-COUNT)) "AVFrame.ch_layout")
    (av-channel-layout-default out 2)
    (ok! (swr-alloc-set-opts2 cell out out-fmt 48000 in (rd first-frame :int F-FORMAT) in-rate 0 0) "swr_alloc")
    (let [swr (rd cell :pointer 0)]
      (ok! (swr-init swr) "swr_init")
      swr)))

(defn- convert!
  "Frame f (nil: flush) through swr: [planes n] — the output planes (av_samples_alloc'd, freed by the caller with
   free-planes!) and the samples written."
  [a swr f out-fmt]
  (let [in-n (if f (rd f :int F-NB-SAMPLES) 0)
        cap (+ 256 (swr-get-out-samples swr in-n))
        planes (ffi/alloc a 64)]
    (ok! (av-samples-alloc planes 0 2 cap out-fmt 0) "samples_alloc")
    [planes (ok! (swr-convert swr planes cap (if f (rd f :pointer F-EXTENDED) 0) in-n) "swr_convert")]))

(defn- free-planes! [planes] (av-freep planes))

;; ---------------------------------------------------------------------------
;; PCM
;; ---------------------------------------------------------------------------

(defn pcm!
  "The first audio stream of in decoded to f32le, stereo, 48 kHz into the file dest: {:frames n :bytes b}, or nil
   when in has no audio."
  [in dest]
  (with-open [a (ffi/confined-arena)]
    (let [ic (open-input a in)
          d (open-decoder a ic MEDIA-AUDIO)]
      (try
        (when d
          (let [fd (c-open (str dest ".tmp-" (c-getpid)) O-WRONLY-CREAT-TRUNC 420)
                _ (when (neg? fd) (throw (ex-info "eve-media.av: cannot write the window file" {:dest dest})))
                pkt (av-packet-alloc)
                f (new-frame)
                swr (atom nil)
                total (atom 0)
                write! (fn [[planes n]]
                         (when (pos? n)
                           (let [bytes (* n 2 4)]
                             (c-write fd (rd planes :pointer 0) bytes)
                             (swap! total + bytes)))
                         (free-planes! planes))
                on-frame (fn [fr]
                           (when-not @swr (reset! swr (resampler a (:ctx d) fr FMT-FLT)))
                           (write! (convert! a @swr fr FMT-FLT)))]
            (check-fresh-packet! pkt)
            (loop []
              (when (>= (av-read-frame ic pkt) 0)
                (when (= (:index d) (rd pkt :int P-STREAM)) (decode! d pkt f on-frame))
                (av-packet-unref pkt)
                (recur)))
            (decode! d nil f on-frame)
            (when @swr
              (write! (convert! a @swr nil FMT-FLT))
              (let [c (ffi/alloc a :pointer)] (wr! c :pointer 0 @swr) (swr-free c)))
            (c-close fd)
            (c-rename (str dest ".tmp-" (c-getpid)) dest)
            (free-frame! a f)
            (free-packet! a pkt)
            {:bytes @total}))
        (finally
          (when d (let [c (ffi/alloc a :pointer)] (wr! c :pointer 0 (:ctx d)) (avcodec-free-context c)))
          (let [c (ffi/alloc a :pointer)] (wr! c :pointer 0 ic) (avformat-close-input c)))))))

;; ---------------------------------------------------------------------------
;; The transcoder
;; ---------------------------------------------------------------------------

(defn- video-encoder
  "libx264 for decoder d's pictures: w×h yuv420p, time base 1/fps; the CLI's settings."
  [a d]
  (let [ctx (:ctx d)
        [w h] (map parse-long (str/split (opt-str a ctx "video_size") #"x"))
        [rn rd'] (:rate d)
        [num den] (if (and (pos? rn) (pos? rd')) [rn rd'] [25 1])
        enc (avcodec-find-encoder-by-name "libx264")
        e (avcodec-alloc-context3 enc)]
    (abi! (not (ffi/null? enc)) "no libx264 encoder")
    (opt! e "video_size" (str w "x" h) 0)
    (opt! e "pixel_format" "yuv420p" 0)
    (opt! e "time_base" (str den "/" num) 0)
    (opt! e "flags" "+global_header+bitexact" 0)
    (opt! e "threads" 1 0)
    (opt! e "preset" "medium" SEARCH-CHILDREN)
    (opt! e "crf" "23" SEARCH-CHILDREN)
    (opt! e "x264-params" "threads=1:sliced-threads=0" SEARCH-CHILDREN)
    (ok! (avcodec-open2 e enc 0) "open libx264")
    {:ctx e :tb [den num] :w w :h h :in-w w :in-h h :in-fmt (av-get-pix-fmt (opt-str a ctx "pixel_format"))}))

(defn- audio-encoder
  "AAC, 128 kb/s, stereo, 48 kHz, planar float; time base 1/48000."
  [a]
  (let [enc (avcodec-find-encoder-by-name "aac")
        e (avcodec-alloc-context3 enc)]
    (abi! (not (ffi/null? enc)) "no aac encoder")
    (opt! e "b" 128000 0)
    (opt! e "ar" 48000 0)
    (opt! e "ch_layout" "stereo" 0)
    (opt! e "time_base" "1/48000" 0)
    (opt! e "flags" "+global_header+bitexact" 0)
    ;; sample_fmt is no AVOption: release's bytes after sample_rate (6.1: sample_rate, channels, sample_fmt,
    ;; frame_size; 7.x and 8.x: sample_rate, sample_fmt, ... frame_size), checked by frame_size's offset
    (let [ar (opt-offset e "ar")]
      (abi! (= (opt-offset e "frame_size") (+ ar (:frame-size release))) "AVCodecContext.sample_fmt")
      (wr! e :int (+ ar (:sample-fmt release)) FMT-FLTP))
    (ok! (avcodec-open2 e enc 0) "open aac")
    {:ctx e :tb [1 48000] :frame-size (opt-int a e "frame_size")}))

(defn- add-stream! [oc enc]
  (let [s (avformat-new-stream oc 0)]
    (ok! (avcodec-parameters-from-context (rd s :pointer S-CODECPAR) (:ctx enc)) "parameters_from_context")
    (wr! s :int S-TB (first (:tb enc)))
    (wr! s :int (+ S-TB 4) (second (:tb enc)))
    s))

(defn- stream-tb [s] [(rd s :int S-TB) (rd s :int (+ S-TB 4))])

(defn- encode!
  "Frame f (nil: flush) sent to encoder enc; every packet written to stream s of oc, rescaled to its time base."
  [oc enc s f pkt]
  (let [r (avcodec-send-frame (:ctx enc) (or f 0))]
    (when (and (neg? r) (not= r EOF)) (ok! r "send_frame")))
  (loop []
    (let [r (avcodec-receive-packet (:ctx enc) pkt)]
      (cond (or (= r EAGAIN) (= r EOF)) nil
            (neg? r) (ok! r "receive_packet")
            :else (let [tb (stream-tb s)]
                    (wr! pkt :int P-STREAM (rd s :int S-INDEX))
                    (wr! pkt :int64 P-PTS (rescale (rd pkt :int64 P-PTS) (:tb enc) tb))
                    (wr! pkt :int64 P-DTS (rescale (rd pkt :int64 P-DTS) (:tb enc) tb))
                    (wr! pkt :int64 P-DURATION (rescale (rd pkt :int64 P-DURATION) (:tb enc) tb))
                    (ok! (av-interleaved-write-frame oc pkt) "write_frame")
                    (recur))))))

(defn transcode!
  "in to a web-ready MP4 at out (written to a temporary file, renamed when complete): H.264/AAC, or AAC alone when
   audio-only?. {:video-frames :audio-samples}."
  [in out audio-only?]
  (av-log-set-level 16)
  (with-open [a (ffi/confined-arena)]
    (let [ic (open-input a in)
          vd (when-not audio-only? (open-decoder a ic MEDIA-VIDEO))
          ad (open-decoder a ic MEDIA-AUDIO)
          tmp (str out ".tmp-" (c-getpid))
          ocell (ffi/alloc a :pointer)
          _ (ok! (avformat-alloc-output-context2 ocell 0 "mp4" tmp) "alloc_output_context")
          oc (rd ocell :pointer 0)
          _ (opt! oc "fflags" "+bitexact" 0)
          ve (when vd (video-encoder a vd))
          ae (when ad (audio-encoder a))
          vs (when ve (add-stream! oc ve))
          as (when ae (add-stream! oc ae))
          opts (ffi/alloc a :pointer)
          pkt (av-packet-alloc)
          opkt (av-packet-alloc)
          f (new-frame)
          vf (when ve (new-frame))
          af (when ae (new-frame))
          sws (atom nil)
          swr (atom nil)
          fifo (when ae (av-audio-fifo-alloc FMT-FLTP 2 (* 4 (:frame-size ae))))
          counts (atom {:video-frames 0 :audio-samples 0})]
      (check-fresh-packet! pkt)
      (ok! (avio-open (+ oc C-PB) tmp AVIO-WRITE) "avio_open")
      (av-dict-set opts "movflags" "+faststart" 0)
      (ok! (avformat-write-header oc opts) "write_header")
      (av-dict-free opts)
      (when ve
        (wr! vf :int F-WIDTH (:w ve))
        (wr! vf :int F-HEIGHT (:h ve))
        (wr! vf :int F-FORMAT 0)                     ; AV_PIX_FMT_YUV420P
        (ok! (av-frame-get-buffer vf 0) "frame_get_buffer (video)"))
      (when ae
        (wr! af :int F-NB-SAMPLES (:frame-size ae))
        (wr! af :int F-FORMAT FMT-FLTP)
        (wr! af :int F-SAMPLE-RATE 48000)
        (av-channel-layout-default (+ af F-CH-LAYOUT) 2)
        (ok! (av-frame-get-buffer af 0) "frame_get_buffer (audio)"))
      (let [on-video (fn [fr]
                       (when-not @sws
                         (abi! (and (= (:in-w ve) (rd fr :int F-WIDTH)) (= (:in-h ve) (rd fr :int F-HEIGHT)))
                               "AVFrame.width/height")
                         (reset! sws (sws-get-context (:in-w ve) (:in-h ve) (rd fr :int F-FORMAT)
                                                      (:w ve) (:h ve) 0 SWS-BICUBIC 0 0 0)))
                       (ok! (av-frame-make-writable vf) "make_writable")
                       (sws-scale @sws (+ fr F-DATA) (+ fr F-LINESIZE) 0 (:in-h ve) (+ vf F-DATA) (+ vf F-LINESIZE))
                       (let [ts (rd fr :int64 F-BEST-EFFORT)
                             ts (if (= ts NOPTS) (rd fr :int64 F-PTS) ts)]
                         (wr! vf :int64 F-PTS (if (= ts NOPTS) (:video-frames @counts) (rescale ts (:tb vd) (:tb ve)))))
                       (swap! counts update :video-frames inc)
                       (encode! oc ve vs vf opkt))
            drain-fifo! (fn [final?]
                          (loop []
                            (let [have (av-audio-fifo-size fifo)
                                  n (if (>= have (:frame-size ae)) (:frame-size ae) (if (and final? (pos? have)) have 0))]
                              (when (pos? n)
                                (ok! (av-frame-make-writable af) "make_writable (audio)")
                                (wr! af :int F-NB-SAMPLES n)
                                (av-audio-fifo-read fifo (+ af F-DATA) n)
                                (wr! af :int64 F-PTS (:audio-samples @counts))
                                (swap! counts update :audio-samples + n)
                                (encode! oc ae as af opkt)
                                (recur)))))
            push! (fn [[planes n]]
                    (when (pos? n) (av-audio-fifo-write fifo planes n))
                    (free-planes! planes)
                    (drain-fifo! false))
            on-audio (fn [fr]
                       (when-not @swr (reset! swr (resampler a (:ctx ad) fr FMT-FLTP)))
                       (push! (convert! a @swr fr FMT-FLTP)))]
        (loop []
          (when (>= (av-read-frame ic pkt) 0)
            (let [si (rd pkt :int P-STREAM)]
              (cond (and vd (= si (:index vd))) (decode! vd pkt f on-video)
                    (and ad (= si (:index ad))) (decode! ad pkt f on-audio)))
            (av-packet-unref pkt)
            (recur)))
        (when vd (decode! vd nil f on-video))
        (when ad
          (decode! ad nil f on-audio)
          (when @swr (push! (convert! a @swr nil FMT-FLTP)))
          (drain-fifo! true))
        (when ve (encode! oc ve vs nil opkt))
        (when ae (encode! oc ae as nil opkt)))
      (ok! (av-write-trailer oc) "write_trailer")
      (avio-closep (+ oc C-PB))
      ;; release
      (when @sws (sws-free-context @sws))
      (when @swr (let [c (ffi/alloc a :pointer)] (wr! c :pointer 0 @swr) (swr-free c)))
      (when fifo (av-audio-fifo-free fifo))
      (doseq [fr (remove nil? [f vf af])] (free-frame! a fr))
      (doseq [p [pkt opkt]] (free-packet! a p))
      (doseq [c (remove nil? [(:ctx vd) (:ctx ad) (:ctx ve) (:ctx ae)])]
        (let [cell (ffi/alloc a :pointer)] (wr! cell :pointer 0 c) (avcodec-free-context cell)))
      (avformat-free-context oc)
      (let [c (ffi/alloc a :pointer)] (wr! c :pointer 0 ic) (avformat-close-input c))
      (ok! (c-rename tmp out) "rename")
      @counts)))

(ffi/defcfn c-mkdir "mkdir" [:string :int] :int)

(ffi/defcfn c-lseek "lseek" [:int :int64 :int] :int64)

(defn parent
  "The directory part of path."
  [path]
  (subs path 0 (max 0 (str/last-index-of path "/"))))

(defn mkdirs!
  "The directory dir and its parents made where absent (mkdir(2); an existing one is left as it is)."
  [dir]
  (reduce (fn [acc part]
            (let [p (str acc "/" part)]
              (c-mkdir p 493)
              p)) "" (remove str/blank? (str/split dir #"/"))))

(defn file-size
  "The size of the file at path in bytes (lseek to its end), or nil when it cannot be opened."
  [path]
  (let [fd (c-open path 0 0)]
    (when-not (neg? fd)
      (let [n (c-lseek fd 0 2)]
        (c-close fd)
        n))))
