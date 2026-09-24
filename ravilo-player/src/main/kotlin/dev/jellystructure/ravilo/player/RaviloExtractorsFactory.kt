package dev.jellystructure.ravilo.player

import android.net.Uri
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mkv.RaviloMatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * R294 — Media3's own extractor set with one substitution: [RaviloMatroskaExtractor] in place of
 * [MatroskaExtractor], so a Matroska file whose `Tracks` element sits after its first Cluster starts
 * at once instead of being read to the end. Every other extractor, and the order they are tried in,
 * is [DefaultExtractorsFactory]'s. Settings the media source pushes in are forwarded to the delegate
 * and applied to the substitute the same way the delegate would apply them to a stock one.
 */
class RaviloExtractorsFactory : ExtractorsFactory {
    private val delegate = DefaultExtractorsFactory()
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var textTrackTranscodingEnabled = true

    @Synchronized
    override fun createExtractors(): Array<Extractor> = swap(delegate.createExtractors())

    @Synchronized
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        swap(delegate.createExtractors(uri, responseHeaders))

    @Synchronized
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        this.textTrackTranscodingEnabled = textTrackTranscodingEnabled
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    @Synchronized
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        this.subtitleParserFactory = subtitleParserFactory
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    @Synchronized
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    private fun swap(extractors: Array<Extractor>): Array<Extractor> {
        for (i in extractors.indices) {
            if (extractors[i] is MatroskaExtractor) {
                extractors[i] = RaviloMatroskaExtractor(
                    subtitleParserFactory,
                    if (textTrackTranscodingEnabled) 0 else RaviloMatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
                )
            }
        }
        return extractors
    }
}
