package dev.jellystructure.media

/**
 * Phase 317 (FR-317-21 / FR-317-26) — three `mkvmerge -J` documents, captured verbatim.
 *
 * Provenance, and why none of them comes from the library: all three are reproduced on demand in a
 * throwaway container from `jellystructure-backend:latest` (`mkvmerge v74.0.0`), with no mounts and no
 * network, on one-second files `ffmpeg` generates in `/tmp` — phase 318's §Acceptance 1 recipe:
 *
 * ```
 * NAME=$(printf "F\303\270royskt tal"); PATHX=$(printf "f\303\270.mkv")
 * ffmpeg -f lavfi -i testsrc=d=1:s=160x120:r=25 -f lavfi -i sine=d=1 -c:v libx264 -c:a aac \
 *   -metadata:s:a:0 title="$NAME" name.mkv
 * ffmpeg -f lavfi -i testsrc=d=1:s=160x120:r=25 -f lavfi -i sine=d=1 -c:v libx264 -c:a aac "$PATHX"
 * LC_ALL=C mkvmerge -J name.mkv   # exit 0, 2110 bytes, empty stderr, cut mid-string
 * LC_ALL=C mkvmerge -J "$PATHX"   # exit 2,  111 bytes, a valid JSON error document
 * ```
 *
 * The repository is public, so this is deliberately NOT trimmed out of a real library file: a truncated
 * document is reproducible from a synthetic one, and these carry no library string at all.
 */
object MkvIdentificationFixtures {

    /** `LC_ALL=C`, ASCII path, non-ASCII `track_name` on the file's ONLY audio track: 2 110 bytes, exit 0,
     *  cut inside that track's object. Nothing can be read of it, so the map is empty — and no id is
     *  guessed from the video track that precedes it. */
    val CUT_INSIDE_THE_FIRST_AUDIO_TRACK = """{
  "attachments": [],
  "chapters": [],
  "container": {
    "properties": {
      "container_type": 17,
      "duration": 1023000000,
      "is_providing_timestamps": true,
      "muxing_application": "Lavf59.27.100",
      "segment_uid": "c6f2b089e90007a3c32f2553f8fdd7ab",
      "writing_application": "Lavf59.27.100"
    },
    "recognized": true,
    "supported": true,
    "type": "Matroska"
  },
  "errors": [],
  "file_name": "name.mkv",
  "global_tags": [
    {
      "num_entries": 1
    }
  ],
  "identification_format_version": 17,
  "track_tags": [
    {
      "num_entries": 2,
      "track_id": 0
    },
    {
      "num_entries": 2,
      "track_id": 1
    }
  ],
  "tracks": [
    {
      "codec": "AVC/H.264/MPEG-4p10",
      "id": 0,
      "properties": {
        "codec_id": "V_MPEG4/ISO/AVC",
        "codec_private_data": "01f4000bffe1001a67f4000b919b28508fc4e022000003000200000300641e28532c01000668ebe3c44844fff8f800",
        "codec_private_length": 47,
        "color_range": 1,
        "default_duration": 40000000,
        "default_track": false,
        "display_dimensions": "160x120",
        "display_unit": 0,
        "enabled_track": true,
        "forced_track": false,
        "language": "und",
        "minimum_timestamp": 23000000,
        "number": 1,
        "packetizer": "mpeg4_p10_video",
        "pixel_dimensions": "160x120",
        "tag_duration": "00:00:01.023000000",
        "tag_encoder": "Lavc59.37.100 libx264",
        "uid": 13104579217973736603
      },
      "type": "video"
    },
    {
      "codec": "AAC",
      "id": 1,
      "properties": {
        "audio_bits_per_sample": 32,
        "audio_channels": 1,
        "audio_sampling_frequency": 44100,
        "codec_id": "A_AAC",
        "codec_private_data": "120856e500",
        "codec_private_length": 5,
        "default_track": false,
        "enabled_track": true,
        "forced_track": false,
        "language": "und",
        "minimum_timestamp": 0,
        "number": 2,
        "tag_duration": "00:00:01.023000000",
        "tag_encoder": "Lavc59.37.100 aac",
        "track_name": "F"""

    /** The same run against a file with TWO audio tracks, the non-ASCII `track_name` on the second:
     *  2 817 bytes, exit 0, cut inside the second audio track's object. The first audio track is whole,
     *  so its id is read; the second does not exist as far as the walk is concerned. */
    val CUT_INSIDE_THE_SECOND_AUDIO_TRACK = """{
  "attachments": [],
  "chapters": [],
  "container": {
    "properties": {
      "container_type": 17,
      "duration": 1023000000,
      "is_providing_timestamps": true,
      "muxing_application": "Lavf59.27.100",
      "segment_uid": "6f54b227582116a1be548dc17d21cda0",
      "writing_application": "Lavf59.27.100"
    },
    "recognized": true,
    "supported": true,
    "type": "Matroska"
  },
  "errors": [],
  "file_name": "two.mkv",
  "global_tags": [
    {
      "num_entries": 1
    }
  ],
  "identification_format_version": 17,
  "track_tags": [
    {
      "num_entries": 2,
      "track_id": 0
    },
    {
      "num_entries": 2,
      "track_id": 1
    },
    {
      "num_entries": 2,
      "track_id": 2
    }
  ],
  "tracks": [
    {
      "codec": "AVC/H.264/MPEG-4p10",
      "id": 0,
      "properties": {
        "codec_id": "V_MPEG4/ISO/AVC",
        "codec_private_data": "01f4000bffe1001a67f4000b919b28508fc4e022000003000200000300641e28532c01000668ebe3c44844fff8f800",
        "codec_private_length": 47,
        "color_range": 1,
        "default_duration": 40000000,
        "default_track": false,
        "display_dimensions": "160x120",
        "display_unit": 0,
        "enabled_track": true,
        "forced_track": false,
        "language": "und",
        "minimum_timestamp": 23000000,
        "number": 1,
        "packetizer": "mpeg4_p10_video",
        "pixel_dimensions": "160x120",
        "tag_duration": "00:00:01.023000000",
        "tag_encoder": "Lavc59.37.100 libx264",
        "uid": 10655524906154250224
      },
      "type": "video"
    },
    {
      "codec": "AAC",
      "id": 1,
      "properties": {
        "audio_bits_per_sample": 32,
        "audio_channels": 1,
        "audio_sampling_frequency": 44100,
        "codec_id": "A_AAC",
        "codec_private_data": "120856e500",
        "codec_private_length": 5,
        "default_track": true,
        "enabled_track": true,
        "forced_track": false,
        "language": "und",
        "minimum_timestamp": 0,
        "number": 2,
        "tag_duration": "00:00:01.023000000",
        "tag_encoder": "Lavc59.37.100 aac",
        "track_name": "English",
        "uid": 5499018459392494661
      },
      "type": "audio"
    },
    {
      "codec": "AAC",
      "id": 2,
      "properties": {
        "audio_bits_per_sample": 32,
        "audio_channels": 1,
        "audio_sampling_frequency": 44100,
        "codec_id": "A_AAC",
        "codec_private_data": "120856e500",
        "codec_private_length": 5,
        "default_track": false,
        "enabled_track": true,
        "forced_track": false,
        "language": "und",
        "minimum_timestamp": 0,
        "number": 3,
        "tag_duration": "00:00:01.023000000",
        "tag_encoder": "Lavc59.37.100 aac",
        "track_name": "F"""

    /** `LC_ALL=C`, non-ASCII path: exit 2 and 111 bytes of valid JSON — an identification that did not
     *  happen, with no `tracks` array at all. Note the path in mkvmerge's own message is itself cut at the
     *  first non-ASCII byte, which is how `/tmp/fø.mkv` reads as `f`. */
    val ERROR_DOCUMENT_FROM_A_NON_ASCII_PATH = """{
  "errors": [
    "The file 'f' could not be opened for reading: open file error.\n"
  ],
  "warnings": []
}
"""
}
