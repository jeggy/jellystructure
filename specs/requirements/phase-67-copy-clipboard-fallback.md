# Phase 67 — Copy command: clipboard fallback for non-HTTPS (FR-CC1)

**Status:** Planned

## Goal

The "Copy" buttons inside the permission-fix panel (shown when Jellystructure cannot write to a media directory) do nothing when the app is served over plain HTTP. Clicking them silently fails.

## Root cause

`permCopyBlock()` (`MediaDetail.kt` line 2440) generates an inline `onclick` that calls `navigator.clipboard.writeText(...)`. The Clipboard API is only available in **secure contexts** (HTTPS or `localhost`). On HTTP, `navigator.clipboard` is `undefined`. Calling `undefined.writeText(...)` throws a `TypeError` synchronously — the rest of the onclick handler (the "Copied!" feedback) never runs. There is no `try/catch` and no fallback.

```javascript
// current — fails silently on HTTP:
navigator.clipboard.writeText(this.dataset.copy);
```

## Fix

Add `copyToClipboard(text: String)` to `JsInterop.kt` — tries the modern API first, falls back to the `execCommand` pattern which works on HTTP:

```kotlin
internal fun copyToClipboard(text: String): Unit = js("""(function(){
    try { navigator.clipboard.writeText(text); }
    catch(e) {
        var ta = document.createElement('textarea');
        ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
        document.body.appendChild(ta); ta.focus(); ta.select();
        try { document.execCommand('copy'); } catch(_) {}
        document.body.removeChild(ta);
    }
})()""")
```

Update `permCopyBlock()` to call the Kotlin function instead of an inline onclick. Since the block is a pure HTML string, the cleanest approach is to wire the click event via DOM after insertion (the same pattern used for other dynamic buttons in `MediaDetail.kt`) rather than an inline `onclick` attribute.

Alternatively: replace the inline onclick string to include the try/catch + fallback directly, keeping the self-contained HTML block pattern.

## Files

| File | Change |
|------|--------|
| `src/wasmJsMain/kotlin/dev/jellystructure/JsInterop.kt` | Add `copyToClipboard(text: String): Unit` |
| `src/wasmJsMain/kotlin/dev/jellystructure/ui/MediaDetail.kt` | Update `permCopyBlock()` to use the new interop function or inline the fallback onclick |

## Non-goals

- No visual redesign of the copy button.
- No change to the permission check logic or banner trigger.
