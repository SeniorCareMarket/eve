/* eve/eve_obj.hpp -- the jank seat's one object (Eve-in-cohost unit 6, option 3), defined once here and included by
   eve.host-completion's cpp/raw and by any host built on it (cohost's jank seat): every jank command that loads the
   completion passes -I <eve>/adapters/completion/jank/include. All inline; the hooks are inline variables. */
#pragma once
#include <jank/runtime/object.hpp>
#include <jank/runtime/core.hpp>
#include <jank/runtime/core/make_box.hpp>
#include <jank/runtime/core/seq.hpp>
#include <jank/runtime/core/call.hpp>
#include <jank/runtime/core/truthy.hpp>
#include <jank/runtime/obj/nil.hpp>
#include <jank/runtime/obj/persistent_string.hpp>
#include <jank/runtime/obj/persistent_vector.hpp>
#include <jank/runtime/obj/cons.hpp>
#include <jank/runtime/obj/exception_info.hpp>
#include <jank/runtime/obj/number.hpp>
#include <gc.h>
namespace eve_host {
  using namespace jank::runtime;
  /* The one object of the jank seat (Eve-in-cohost unit 6, option 3): every deftype and reify instance of eve's, and
     every type instance of a host built on eve (cohost's jank seat), is this struct. To jank's own dispatch it is an
     opaque_box, a type jank knows (no unknown type id to panic on); its identity is its descriptor (tag): an eve type's
     tag (a symbol) or a host's own descriptor (cohost's: a map). It carries per-instance method slots (methods: nil or
     a vector; 0 equiv, 1 hasheq, 2 toString, 3 toCodeString, 4 invoke, 5 deref, 6 get, 7 seq, 8 compare, 9 find,
     10 contains?, 11 nth, 12 first, 13 next), an
     extmap and a meta -- the shape and behaviors of cohost's former cohost_object (cohost.cljc
     src/cohost/hosts/jank_host.jank), moved here. An unfilled slot 0-3 answers through the hooks below (eve's =, hash,
     print); any other unfilled slot throws a jank value (an ex-info thrown as object_ref), which a catch takes: a gap
     never ends the process. Everything here is inline, the hooks inline variables: one definition however many
     modules' code (a host's included) sees this block. */
  inline object_ref printer{ jank_nil };
  inline object_ref equiv{ jank_nil };
  inline object_ref hasher{ jank_nil };
  inline bool rooted{ false };
  /* registered as roots: statics of JIT-compiled code are not scanned (the printer was collected under the object
     and a print crashed, at random: measured, J1) */
  inline void root() { if(!rooted) { rooted = true; GC_add_roots(&printer, &printer + 1); GC_add_roots(&equiv, &equiv + 1); GC_add_roots(&hasher, &hasher + 1); } }
  inline bool equiv_of(object_ref a, object_ref b) {
    if(a.ptr() == b.ptr()) return true;
    if(equiv == jank_nil) return false;
    return truthy(equiv.call(a, b));
  }
  inline long hash_of(object_ref a) {
    if(hasher == jank_nil) return (long)reinterpret_cast<uintptr_t>(a.ptr());
    return (long)to_int(hasher.call(a));
  }
  inline jtl::immutable_string str_of(object_ref a) {
    if(printer == jank_nil) return "#eve/object";
    return jank::runtime::to_string(printer.call(a));
  }
  /* a jank value thrown as object_ref: (catch jank.runtime.object_ref e ...) takes it, as it takes (throw (ex-info ..));
     an oref<obj::exception_info> is a C++ type no jank catch arm matches (the process ended: measured, unit 6) */
  [[noreturn]] inline void unsupported(char const *what) {
    throw object_ref{ make_box<obj::exception_info>(jtl::immutable_string{ what }, make_box<obj::nil>()) };
  }
  struct eve_obj : object {
    static constexpr object_type obj_type{ object_type::opaque_box };
    static constexpr object_behavior obj_behaviors{
      object_behavior::call | object_behavior::get | object_behavior::find
      | object_behavior::compare | object_behavior::seqable | object_behavior::indexable
      | object_behavior::deref };
    static constexpr bool pointer_free{ false };
    object_ref tag;
    object_ref methods;
    object_ref fields;
    object_ref extmap;
    object_ref meta_;
    eve_obj(object_ref t, object_ref m, object_ref f, object_ref e, object_ref mt)
      : object{ obj_type, obj_behaviors }, tag{ t }, methods{ m }, fields{ f }, extmap{ e }, meta_{ mt } {}
    /* a raw this is shifted into NaN space by the untagged_ptr constructor (oref.hpp: a ref built straight from
       this read as a small_real, measured) */
    object_ref self() const { return object_ref{ jank::runtime::detail::untagged(static_cast<object const *>(this)) }; }
    object_ref other(object const &o) const { return object_ref{ jank::runtime::detail::untagged(&o) }; }
    object_ref slot(jank::i64 i) const {
      if(methods.is_nil() || methods.get_type() != object_type::persistent_vector) { return jank_nil; }
      auto const &d = expect_object<obj::persistent_vector>(methods)->data;
      if(i < 0 || static_cast<jank::usize>(i) >= d.size()) { return jank_nil; }
      return d[static_cast<jank::usize>(i)];
    }
    bool has_slot(jank::i64 i) const { return !slot(i).is_nil(); }
    bool equal(object const &o) const override {
      if(!has_slot(0)) { return equiv_of(self(), other(o)); }
      return truthy(slot(0).call(self(), other(o)));
    }
    jank::uhash to_hash() const override {
      if(!has_slot(1)) { return (jank::uhash)hash_of(self()); }
      return static_cast<jank::uhash>(slot(1).call(self()).to_integer());
    }
    jtl::immutable_string to_string() const override {
      if(!has_slot(2)) { return str_of(self()); }
      return jank::runtime::to_string(slot(2).call(self()));
    }
    void to_string(jtl::string_builder &sb) const override { sb(to_string()); }
    jtl::immutable_string to_code_string() const override {
      if(!has_slot(3)) { return to_string(); }
      return jank::runtime::to_string(slot(3).call(self()));
    }
    object_ref deref() override {
      if(!has_slot(5)) { unsupported("eve: this type does not implement IDeref"); }
      return slot(5).call(self());
    }
    object_ref get(object_ref key) const override {
      if(!has_slot(6)) { return jank_nil; }
      return slot(6).call(self(), key);
    }
    object_ref get(object_ref key, object_ref fb) const override {
      if(!has_slot(6)) { return fb; }
      return slot(6).call(self(), key, fb);
    }
    bool contains(object_ref key) const override {
      if(has_slot(10)) { return truthy(slot(10).call(self(), key)); }
      if(has_slot(9)) { return !slot(9).call(self(), key).is_nil(); }
      return !get(key).is_nil();
    }
    object_ref find(object_ref key) const override {
      if(has_slot(9)) { return slot(9).call(self(), key); }
      auto v = get(key);
      if(v.is_nil()) { return v; }
      return make_box<obj::persistent_vector>(std::in_place, key, v);
    }
    object_ref seq() const override {
      if(!has_slot(7)) { unsupported("eve: this type is not seqable"); }
      return slot(7).call(self());
    }
    object_ref fresh_seq() const override { return seq(); }
    jank::i64 compare(object const &o) const override {
      if(!has_slot(8)) { unsupported("eve: this type is not Comparable"); }
      return slot(8).call(self(), other(o)).to_integer();
    }
    object_ref nth(object_ref index) const override {
      if(!has_slot(11)) { unsupported("eve: nth is not jank's on this object"); }
      return slot(11).call(self(), index);
    }
    object_ref nth(object_ref index, object_ref fb) const override {
      if(!has_slot(11)) { return fb; }
      return slot(11).call(self(), index, fb);
    }
    object_ref call() const override { return invoke0(); }
    object_ref call(object_ref a1) const override { if(!has_slot(4)) { unsupported("eve: this type is not callable"); } return slot(4).call(self(), a1); }
    object_ref call(object_ref a1, object_ref a2) const override { if(!has_slot(4)) { unsupported("eve: this type is not callable"); } return slot(4).call(self(), a1, a2); }
    object_ref call(object_ref a1, object_ref a2, object_ref a3) const override { if(!has_slot(4)) { unsupported("eve: this type is not callable"); } return slot(4).call(self(), a1, a2, a3); }
    object_ref call(object_ref a1, object_ref a2, object_ref a3, object_ref a4) const override { if(!has_slot(4)) { unsupported("eve: this type is not callable"); } return slot(4).call(self(), a1, a2, a3, a4); }
    object_ref call(object_ref a1, object_ref a2, object_ref a3, object_ref a4, object_ref a5) const override { if(!has_slot(4)) { unsupported("eve: this type is not callable"); } return slot(4).call(self(), a1, a2, a3, a4, a5); }
    object_ref call(object_ref a1, object_ref a2, object_ref a3, object_ref a4, object_ref a5, object_ref a6) const override { if(!has_slot(4)) { unsupported("eve: this type is not callable"); } return slot(4).call(self(), a1, a2, a3, a4, a5, a6); }
    object_ref call(object_ref a1, object_ref a2, object_ref a3, object_ref a4, object_ref a5, object_ref a6, object_ref a7) const override { if(!has_slot(4)) { unsupported("eve: this type is not callable"); } return slot(4).call(self(), a1, a2, a3, a4, a5, a6, a7); }
    object_ref call(object_ref a1, object_ref a2, object_ref a3, object_ref a4, object_ref a5, object_ref a6, object_ref a7, object_ref a8) const override { if(!has_slot(4)) { unsupported("eve: this type is not callable"); } return slot(4).call(self(), a1, a2, a3, a4, a5, a6, a7, a8); }
    object_ref call(object_ref a1, object_ref a2, object_ref a3, object_ref a4, object_ref a5, object_ref a6, object_ref a7, object_ref a8, object_ref a9) const override { if(!has_slot(4)) { unsupported("eve: this type is not callable"); } return slot(4).call(self(), a1, a2, a3, a4, a5, a6, a7, a8, a9); }
    object_ref call(object_ref a1, object_ref a2, object_ref a3, object_ref a4, object_ref a5, object_ref a6, object_ref a7, object_ref a8, object_ref a9, object_ref a10) const override { if(!has_slot(4)) { unsupported("eve: this type is not callable"); } return slot(4).call(self(), a1, a2, a3, a4, a5, a6, a7, a8, a9, a10); }
    object_ref call(object_ref a1, object_ref a2, object_ref a3, object_ref a4, object_ref a5, object_ref a6, object_ref a7, object_ref a8, object_ref a9, object_ref a10, object_ref more) const override {
      if(!has_slot(4)) { unsupported("eve: this type is not callable"); }
      object_ref args = more;
      args = make_box<obj::cons>(a10, args);
      args = make_box<obj::cons>(a9, args);
      args = make_box<obj::cons>(a8, args);
      args = make_box<obj::cons>(a7, args);
      args = make_box<obj::cons>(a6, args);
      args = make_box<obj::cons>(a5, args);
      args = make_box<obj::cons>(a4, args);
      args = make_box<obj::cons>(a3, args);
      args = make_box<obj::cons>(a2, args);
      args = make_box<obj::cons>(a1, args);
      args = make_box<obj::cons>(self(), args);
      return apply_to(slot(4), args);
    }
    object_ref invoke0() const {
      if(!has_slot(4)) { unsupported("eve: this type is not callable"); }
      return slot(4).call(self());
    }
    void set_validator(object_ref const) override { unsupported("eve: no validator on this object"); }
    object_ref get_validator() const override { return jank_nil; }
    void add_watch(object_ref const, object_ref const) override { unsupported("eve: no watches on this object"); }
    void remove_watch(object_ref const) override { unsupported("eve: no watches on this object"); }
    jank::i64 to_integer() const override { unsupported("eve: not a number"); }
    jank::f64 to_real() const override { unsupported("eve: not a number"); }
    /* first and next: jank 0.1 walks an object through these virtuals whatever its behavior bits (measured on the
       release line: eve/probes/jank-seqlike) -- an eve type that is its own seq answers them by its own ISeq / INext
       (slots 12, 13: the completion's readers, Eve 0.9.3's dispatch ops 9 and 10); a host's instance without them
       is not a sequence */
    object_ref first() const override {
      if(!has_slot(12)) { unsupported("eve: not a sequence"); }
      return slot(12).call(self());
    }
    object_ref next() const override {
      if(!has_slot(13)) { unsupported("eve: not a sequence"); }
      return slot(13).call(self());
    }
    object_ref next_in_place() override { return next(); }
    array_element_type get_element_type() const override { unsupported("eve: not an array"); }
    object_ref aset(jank::i64 const, object_ref const) override { unsupported("eve: not an array"); }
    object_ref aclone() const override { unsupported("eve: not an array"); }
  };
  inline object_ref make5(object_ref t, object_ref m, object_ref f, object_ref e, object_ref mt) {
    return make_box<eve_obj>(t, m, f, e, mt);
  }
  inline bool is(object_ref o) { return o.get_type() == object_type::opaque_box && dynamic_cast<eve_obj *>(o.ptr()) != nullptr; }
  inline object_ref tag(object_ref o) { return static_cast<eve_obj *>(o.ptr())->tag; }
  inline object_ref methods_of(object_ref o) { return static_cast<eve_obj *>(o.ptr())->methods; }
  inline object_ref fields(object_ref o) { return static_cast<eve_obj *>(o.ptr())->fields; }
  inline object_ref extmap_of(object_ref o) { return static_cast<eve_obj *>(o.ptr())->extmap; }
  inline object_ref meta_of(object_ref o) { return static_cast<eve_obj *>(o.ptr())->meta_; }
  /* field i: the fields vector indexed directly (cohost's fast path) */
  inline object_ref field(object_ref x, jank::i64 i) {
    auto const &f = static_cast<eve_obj *>(x.ptr())->fields;
    if(f.get_type() == object_type::persistent_vector) {
      auto const &d = expect_object<obj::persistent_vector>(f)->data;
      if(0 <= i && static_cast<jank::usize>(i) < d.size()) { return d[static_cast<jank::usize>(i)]; }
    }
    return f.nth(make_box(i));
  }
  inline void set_printer(object_ref f) { root(); printer = f; }
  inline void set_equiv(object_ref f) { root(); equiv = f; }
  inline void set_hasher(object_ref f) { root(); hasher = f; }
  inline long addr(object_ref o) { return (long)reinterpret_cast<uintptr_t>(o.ptr()); }
  /* a byte array of eve's is signed (HOST-CONTRACT §3: a byte reads back signed, as the JVM's): jank's own
     byte-array is u8, so eve's are obj::array<i8> made here */
  inline object_ref i8_array(long n) { return make_box<obj::array<jank::i8>>((jank::i64)n); }
}
