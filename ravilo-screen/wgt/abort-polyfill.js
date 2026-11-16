// DEB-4727 (FR-13) — AbortController arrived in Chromium 66; Tizen 5.0's engine is 63. The bundled Ktor
// fetch engine does `new AbortController` unconditionally, so on a 5.0 set the ReferenceError was swallowed
// by the callers and no request ever left the page. This file runs before ravilo-screen.js and defines
// the two classes only where they are missing, so every newer engine (Tizen 10.0 included) keeps its own.
//
// Chromium 63's fetch has no `signal` support, so a bare stub would let abort() change nothing. The wrapper
// below makes it real for the caller: an aborted fetch rejects with AbortError at once. The request itself
// is not cancelled on the wire (the engine cannot), and Ktor's timeouts and cancellation are coroutine
// based, so a late response is simply dropped. Written as ES2015 and without EventTarget (constructable
// only from 64): scripts/check-tizen-abort.sh runs it on a context that lacks both.
(function (global) {
  if (typeof global.AbortController !== 'undefined') return;

  function abortError() {
    return new DOMException('The operation was aborted.', 'AbortError');
  }

  function AbortSignal() {
    this.aborted = false;
    this.reason = undefined;
    this.onabort = null;
    this._listeners = [];
  }
  AbortSignal.prototype.addEventListener = function (type, listener) {
    if (type === 'abort' && typeof listener === 'function' && this._listeners.indexOf(listener) < 0) {
      this._listeners.push(listener);
    }
  };
  AbortSignal.prototype.removeEventListener = function (type, listener) {
    var i = this._listeners.indexOf(listener);
    if (type === 'abort' && i >= 0) this._listeners.splice(i, 1);
  };
  AbortSignal.prototype.throwIfAborted = function () {
    if (this.aborted) throw this.reason;
  };
  AbortSignal.prototype._abort = function (reason) {
    if (this.aborted) return;
    this.aborted = true;
    this.reason = reason === undefined ? abortError() : reason;
    var event = new Event('abort');
    var handlers = this._listeners.slice();
    if (typeof this.onabort === 'function') handlers.unshift(this.onabort);
    for (var i = 0; i < handlers.length; i++) {
      try { handlers[i].call(this, event); } catch (e) { setTimeout(function () { throw e; }); }
    }
  };

  function AbortController() {
    this.signal = new AbortSignal();
  }
  AbortController.prototype.abort = function (reason) {
    this.signal._abort(reason);
  };

  global.AbortSignal = AbortSignal;
  global.AbortController = AbortController;

  var nativeFetch = global.fetch;
  if (typeof nativeFetch !== 'function') return;
  global.fetch = function (input, init) {
    var signal = init && init.signal;
    if (!(signal instanceof AbortSignal)) return nativeFetch.apply(this, arguments);
    var rest = Object.assign({}, init);
    delete rest.signal;
    return new Promise(function (resolve, reject) {
      if (signal.aborted) { reject(signal.reason); return; }
      var onAbort = function () { reject(signal.reason); };
      signal.addEventListener('abort', onAbort);
      nativeFetch.call(global, input, rest).then(
        function (response) { signal.removeEventListener('abort', onAbort); resolve(response); },
        function (error) { signal.removeEventListener('abort', onAbort); reject(error); }
      );
    });
  };
})(window);
