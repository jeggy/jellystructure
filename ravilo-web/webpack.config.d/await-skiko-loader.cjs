// Loader half of await-skiko.js's fix -- see that file's comment for the full root-cause writeup.
// Content-matched (not filename-matched): Kotlin/Wasm's generated module name is derived from
// Gradle project coordinates and isn't guaranteed stable. Only the actual bootstrap entry file
// has this exact shape (`exports._start();` right after instantiating the app's own wasm); every
// other .mjs webpack processes for this module (skiko.mjs itself, the import-object.mjs, any
// node_modules .mjs) is passed through untouched.
module.exports = function (source) {
    if (typeof source !== 'string') return source;
    if (!source.includes('exports._start();')) return source;
    if (source.includes('__ravilo_awaitSkiko__')) return source; // already patched (idempotency)

    const patched = source
        .replace(
            /^(import\s)/m,
            "import { awaitSkiko as __ravilo_awaitSkiko__ } from './skiko.mjs'\n$1",
        )
        .replace(
            'exports._start();',
            'await __ravilo_awaitSkiko__;\nexports._start();',
        );

    if (patched === source) {
        this.emitWarning(new Error(
            'await-skiko-loader: expected to patch the wasm bootstrap entry file but found no import line to anchor on -- fix not applied, RenderNodeContext crash will recur',
        ));
    }
    return patched;
};
