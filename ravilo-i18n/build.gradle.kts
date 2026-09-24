@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import groovy.json.JsonSlurper

// R279 — the one string table, for every Ravilo client. Deliberately Compose-free and
// dependency-free: :ravilo-cast and :ravilo-screen are plain Kotlin/JS receivers with no Compose
// runtime to hang a CompositionLocal on, and :ravilo-screen ships to 2016-2018 Tizen sets whose
// engine predates WasmGC — so this module carries js(IR) alongside wasmJs, the same four targets
// :shared carries.
//
// The table itself is NOT in this source tree. It is generated at build time from the repo-root
// `i18n/*.json` files by `generateRaviloStrings` below; those JSON files are the source of truth and
// the only thing a translator is ever handed. Reading the JSON at runtime instead was rejected: the
// four targets load resources four different ways, and a sideloaded Tizen .wgt is a local file with
// no origin to fetch from at all.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
}

android {
    namespace = "dev.jellystructure.ravilo.i18n"
    compileSdk = 36
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    linuxX64()

    wasmJs {
        browser()
    }

    js(IR) {
        browser()
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// ─── The generator ────────────────────────────────────────────────────────────────────────────────

/** `en.json` defines the key set; every other language falls back to it, key by key. */
val baseLanguage = "en"

val i18nDir: File = rootProject.file("i18n")
val stringsOutDir: Provider<Directory> = layout.buildDirectory.dir("generated/i18n/commonMain/kotlin")
val driftReport: Provider<RegularFile> = layout.buildDirectory.file("reports/ravilo-i18n/drift.txt")

/** R279 — the web app's pre-boot shell strings, copied into :ravilo-web's resources. */
val shellStringsDir: Provider<Directory> = layout.buildDirectory.dir("generated/i18n/shell")

/** One language file, already flattened: `_meta` split out, `{text,note}` values reduced to text. */
data class LangFile(val code: String, val name: String, val englishName: String, val strings: Map<String, String>)

fun parseLangFile(file: File): LangFile {
    @Suppress("UNCHECKED_CAST")
    val raw = JsonSlurper().parse(file, "UTF-8") as? Map<String, Any?>
        ?: throw GradleException("${file.name}: expected a JSON object at the top level")

    @Suppress("UNCHECKED_CAST")
    val meta = raw["_meta"] as? Map<String, Any?>
        ?: throw GradleException("${file.name}: missing the required `_meta` object")
    fun metaString(field: String) = (meta[field] as? String)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("${file.name}: `_meta.$field` is required and must be a non-empty string")

    val code = metaString("code")
    val expected = file.name.removeSuffix(".json")
    if (code != expected) throw GradleException("${file.name}: `_meta.code` is \"$code\" but the file is named \"$expected.json\" — they must agree")

    val strings = LinkedHashMap<String, String>()
    for ((key, value) in raw) {
        if (key == "_meta") continue
        if (key.startsWith("_")) throw GradleException("${file.name}: \"$key\" — `_meta` is the only key allowed to start with an underscore")
        val text = when (value) {
            is String -> value
            is Map<*, *> -> value["text"] as? String
                ?: throw GradleException("${file.name}: \"$key\" is an object but has no `text` string")
            else -> throw GradleException("${file.name}: \"$key\" must be a string, or an object with `text` (and optionally `note`)")
        }
        strings[key] = text
    }
    return LangFile(code, metaString("name"), metaString("englishName"), strings)
}

fun languageFiles(dir: File): List<File> =
    (dir.listFiles { f: File -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
        .sortedBy { it.name }
        .ifEmpty { throw GradleException("no i18n/*.json files found in $dir") }

/** `{device}`, `{n}` … — the set a translation must reproduce exactly (FR-R279-4). */
val placeholderPattern = Regex("""\{([A-Za-z_][A-Za-z0-9_]*)}""")
fun placeholdersOf(text: String): Set<String> = placeholderPattern.findAll(text).map { it.groupValues[1] }.toSet()

/** JSON is a subset of JS object syntax, and these strings carry no control characters. */
fun jsLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

fun kotlinLiteral(value: String): String {
    val sb = StringBuilder(value.length + 2).append('"')
    for (c in value) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '$' -> sb.append("\\$")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    return sb.append('"').toString()
}

val generateRaviloStrings by tasks.registering {
    description = "Generate the Ravilo string table from i18n/*.json (R279)"
    group = "build"

    val sourceDir = i18nDir
    val outDir = stringsOutDir
    val shellOut = shellStringsDir
    val base = baseLanguage
    inputs.dir(sourceDir).withPropertyName("i18n")
    outputs.dir(outDir).withPropertyName("generatedSource")
    outputs.dir(shellOut).withPropertyName("shellStrings")

    doLast {
        val langs = languageFiles(sourceDir).map(::parseLangFile).associateBy { it.code }
        val baseLang = langs[base] ?: throw GradleException("i18n/$base.json is the base language and must exist")

        // FR-R279-4 — what fails the build, and what only warns.
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        for (lang in langs.values.sortedBy { it.code }) {
            if (lang.code == base) continue
            val unknown = lang.strings.keys - baseLang.strings.keys
            if (unknown.isNotEmpty()) {
                problems += "${lang.code}.json has ${unknown.size} key(s) that $base.json does not: " +
                    unknown.sorted().joinToString(", ")
            }
            for ((key, text) in lang.strings) {
                val expected = baseLang.strings[key] ?: continue
                val want = placeholdersOf(expected)
                val got = placeholdersOf(text)
                if (want != got) {
                    problems += "${lang.code}.json \"$key\": placeholders are ${got.sorted()} but $base.json has ${want.sorted()} " +
                        "— a placeholder the code does not substitute is rendered to the viewer verbatim"
                }
            }
            val missing = baseLang.strings.keys - lang.strings.keys
            if (missing.isNotEmpty()) {
                warnings += "${lang.code}.json is missing ${missing.size} of ${baseLang.strings.size} key(s); " +
                    "$base is rendered for them: " + missing.sorted().take(12).joinToString(", ") +
                    if (missing.size > 12) ", … (+${missing.size - 12} more)" else ""
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Ravilo translations (R279 FR-R279-4):\n  - " + problems.joinToString("\n  - "))
        }
        warnings.forEach { logger.warn("w: Ravilo translations — $it") }

        // Base first, then the rest by code, so a picker's first entry is stable (FR-R279-5).
        val ordered = listOf(baseLang) + langs.values.filter { it.code != base }.sortedBy { it.code }

        val out = StringBuilder()
        out.append(
            """
            |// Generated by :ravilo-i18n:generateRaviloStrings (R279) — DO NOT EDIT, DO NOT COMMIT.
            |// Source of truth: i18n/*.json at the repo root. Add a language by adding a file there.
            |@file:Suppress("ObjectPropertyName", "SpellCheckingInspection")
            |
            |package dev.jellystructure.ravilo.i18n
            |
            |/** Every language `i18n/` declares, base language first. Drives every interface-language picker. */
            |public val SUPPORTED_LANGUAGES: List<RaviloLanguage> = listOf(
            |
            """.trimMargin(),
        )
        for (l in ordered) {
            out.append("    RaviloLanguage(${kotlinLiteral(l.code)}, ${kotlinLiteral(l.name)}, ${kotlinLiteral(l.englishName)}),\n")
        }
        out.append(")\n")

        for (l in ordered) {
            out.append("\nprivate val _${l.code.replace('-', '_')}: Map<String, String> = mapOf(\n")
            for ((key, text) in l.strings) {
                out.append("    ${kotlinLiteral(key)} to ${kotlinLiteral(text)},\n")
            }
            out.append(")\n")
        }

        out.append("\ninternal val LOCALES: Map<String, Map<String, String>> = mapOf(\n")
        for (l in ordered) out.append("    ${kotlinLiteral(l.code)} to _${l.code.replace('-', '_')},\n")
        out.append(")\n")

        val target = outDir.get().file("dev/jellystructure/ravilo/i18n/RaviloStringsTable.kt").asFile
        target.parentFile.mkdirs()
        target.writeText(out.toString())

        // R279 — the web app's HTML shell draws a fullscreen button before the Wasm bundle exists,
        // so it cannot call t(). It gets the handful of strings it needs as a plain JS object,
        // generated from the same files, keyed the same way — never a second hand-written table.
        // boot.js picks a language from localStorage["ravilo.lang"], which LastLanguage writes.
        val shellKeys = listOf("web.fullscreen", "web.fullscreen_title")
        val js = StringBuilder()
        js.append("// Generated by :ravilo-i18n:generateRaviloStrings (R279) — DO NOT EDIT, DO NOT COMMIT.\n")
        js.append("// The shell strings the web app draws before its bundle loads. Source: i18n/*.json.\n")
        js.append("window.raviloShellStrings = {\n")
        for (l in ordered) {
            val pairs = shellKeys.joinToString(", ") { k ->
                val text = l.strings[k] ?: baseLang.strings[k] ?: k
                "${jsLiteral(k)}: ${jsLiteral(text)}"
            }
            js.append("  ${jsLiteral(l.code)}: { $pairs },\n")
        }
        js.append("};\n")
        val jsTarget = shellOut.get().file("ravilo-shell-strings.js").asFile
        jsTarget.parentFile.mkdirs()
        jsTarget.writeText(js.toString())
        logger.lifecycle(
            "Ravilo strings: ${baseLang.strings.size} keys × ${ordered.size} languages " +
                "(${ordered.joinToString(", ") { it.code }}) -> ${target.name}",
        )
    }
}

// ─── The drift report (FR-R279-11) — reports, never fails ─────────────────────────────────────────

val checkRaviloStrings by tasks.registering {
    description = "Report drift between the Ravilo translations (R279 FR-R279-11) — never fails the build"
    group = "verification"

    val sourceDir = i18nDir
    val report = driftReport
    val base = baseLanguage
    inputs.dir(sourceDir).withPropertyName("i18n")
    outputs.file(report).withPropertyName("driftReport")

    doLast {
        val langs = languageFiles(sourceDir).map(::parseLangFile).associateBy { it.code }
        val baseLang = langs.getValue(base)

        // Fold the diacritics a hurried writer drops, so `loyniord` and `loyniorð` land on one bucket.
        fun fold(word: String): String = word.lowercase()
            .replace("æ", "ae").replace("ø", "oe").replace("å", "aa").replace("ð", "d")
            .replace("á", "a").replace("í", "i").replace("ó", "o").replace("ú", "u").replace("ý", "y")
            .replace("é", "e").replace("ö", "oe")

        val wordPattern = Regex("""[\p{L}]+""")
        val lines = StringBuilder()
        lines.append("Ravilo translation drift — generated by :ravilo-i18n:checkRaviloStrings (R279 FR-R279-11)\n")
        lines.append("Nothing here fails the build. Every line is something a human has to judge.\n")

        for (lang in langs.values.sortedBy { it.code }) {
            lines.append("\n===== ${lang.code} (${lang.englishName}) =====\n")

            if (lang.code != base) {
                val identical = lang.strings.filter { (k, v) -> baseLang.strings[k] == v && v.isNotBlank() }
                lines.append("\n-- byte-identical to $base (${identical.size}): untranslated, or correctly the same --\n")
                identical.keys.sorted().forEach { lines.append("   $it = ${lang.strings[it]}\n") }
            }

            val ellipsis = lang.strings.filter { (k, v) -> "..." in v && baseLang.strings[k]?.contains("…") != false }
            lines.append("\n-- \"...\" where an ellipsis character would match the base (${ellipsis.size}) --\n")
            ellipsis.forEach { (k, v) -> lines.append("   $k = $v\n") }

            val spellings = LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>()
            for ((key, text) in lang.strings) {
                for (m in wordPattern.findAll(text)) {
                    val w = m.value
                    spellings.getOrPut(fold(w)) { LinkedHashMap() }.getOrPut(w) { mutableListOf() }.add(key)
                }
            }
            // Case alone is not drift — a sentence-initial capital is not a misspelling.
            val split = spellings.values.filter { forms -> forms.keys.map { it.lowercase() }.toSet().size > 1 }
            lines.append("\n-- the same word spelled two ways, diacritics folded (${split.size}) --\n")
            for (forms in split.sortedBy { it.keys.first().lowercase() }) {
                lines.append("   " + forms.entries.sortedByDescending { it.value.size }
                    .joinToString("  |  ") { "${it.key} (${it.value.size}×: ${it.value.take(3).joinToString(", ")})" } + "\n")
            }
        }

        val file = report.get().asFile
        file.parentFile.mkdirs()
        file.writeText(lines.toString())
        logger.lifecycle("Ravilo translation drift report: ${file.absolutePath}")
    }
}

kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generateRaviloStrings.map { stringsOutDir.get() })
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }
    .configureEach { dependsOn(generateRaviloStrings) }
tasks.named("check") { dependsOn(checkRaviloStrings) }

// The generated shell strings, for :ravilo-web to fold into its own resources. Published through
// `extra` so the consumer gets the task dependency with the provider, not just a path.
extra["raviloShellStrings"] = generateRaviloStrings.map { shellStringsDir.get() }
