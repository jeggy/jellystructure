#!/usr/bin/env python3
"""Phase 284 — the file is the record: jellystructure's one tag reader/writer, in Picard's vocabulary.

    tagwrite.py read  <path> [<path>…]      → one JSON line per file: what the file says
    tagwrite.py write                        → a JSON plan on stdin, one JSON line per file with the outcome

A plan: {"keep_id3_version": true, "keep_unmanaged": true, "files": [{"path": "…", "tags": {logical: value|null},
"drop_unmanaged": false, "cover": "/path/to/cover.jpg" | null}]}. A null value removes the tag.

Every write is copy → save → verify → rename (254's shape): the file is copied beside itself, the copy is tagged, ffprobe
reads the copy back (same codec, same duration, the tags present), and only then does the copy replace the original —
which also breaks a hardlink a torrent client may still seed. Nothing is ever saved in place.

Logical keys (Picard's mapping): title · artist · album · albumartist · tracknumber · tracktotal · discnumber · disctotal
· date · originaldate · genre · composer · comment · publisher · mb_recording · mb_track · mb_release · mb_releasegroup
· mb_artist · mb_albumartist · rg_track_gain · rg_album_gain.
"""
import json, os, shutil, subprocess, sys, tempfile

try:
    import mutagen
    from mutagen.id3 import ID3, ID3NoHeaderError, TXXX, APIC, Frames
    from mutagen.mp4 import MP4, MP4Cover
    from mutagen.asf import ASF, ASFUnicodeAttribute, ASFByteArrayAttribute
    from mutagen.flac import FLAC, Picture
    from mutagen.oggvorbis import OggVorbis
    from mutagen.oggopus import OggOpus
except Exception as e:  # pragma: no cover
    print(json.dumps({"error": "mutagen is not installed: %s" % e}))
    sys.exit(2)

# ── the mapping, per family ────────────────────────────────────────────────────────────────────────────────────
ID3_MAP = {  # logical → ID3 frame id, or ('TXXX', desc)
    "title": "TIT2", "artist": "TPE1", "album": "TALB", "albumartist": "TPE2", "tracknumber": "TRCK", "discnumber": "TPOS",
    "date": "TDRC", "originaldate": "TDOR", "genre": "TCON", "composer": "TCOM", "comment": "COMM", "publisher": "TPUB",
    "mb_recording": ("UFID", "http://musicbrainz.org"), "mb_track": ("TXXX", "MusicBrainz Release Track Id"),
    "mb_release": ("TXXX", "MusicBrainz Album Id"), "mb_releasegroup": ("TXXX", "MusicBrainz Release Group Id"),
    "mb_artist": ("TXXX", "MusicBrainz Artist Id"), "mb_albumartist": ("TXXX", "MusicBrainz Album Artist Id"),
    "rg_track_gain": ("TXXX", "REPLAYGAIN_TRACK_GAIN"), "rg_album_gain": ("TXXX", "REPLAYGAIN_ALBUM_GAIN"),
}
VORBIS_MAP = {
    "title": "TITLE", "artist": "ARTIST", "album": "ALBUM", "albumartist": "ALBUMARTIST", "tracknumber": "TRACKNUMBER",
    "tracktotal": "TRACKTOTAL", "discnumber": "DISCNUMBER", "disctotal": "DISCTOTAL", "date": "DATE", "originaldate": "ORIGINALDATE",
    "genre": "GENRE", "composer": "COMPOSER", "comment": "COMMENT", "publisher": "ORGANIZATION",
    "mb_recording": "MUSICBRAINZ_TRACKID", "mb_track": "MUSICBRAINZ_RELEASETRACKID", "mb_release": "MUSICBRAINZ_ALBUMID",
    "mb_releasegroup": "MUSICBRAINZ_RELEASEGROUPID", "mb_artist": "MUSICBRAINZ_ARTISTID", "mb_albumartist": "MUSICBRAINZ_ALBUMARTISTID",
    "rg_track_gain": "REPLAYGAIN_TRACK_GAIN", "rg_album_gain": "REPLAYGAIN_ALBUM_GAIN",
}
MP4_MAP = {
    "title": "\xa9nam", "artist": "\xa9ART", "album": "\xa9alb", "albumartist": "aART", "date": "\xa9day", "originaldate": "----:com.apple.iTunes:ORIGINALDATE",
    "genre": "\xa9gen", "composer": "\xa9wrt", "comment": "\xa9cmt", "publisher": "----:com.apple.iTunes:LABEL",
    "mb_recording": "----:com.apple.iTunes:MusicBrainz Track Id", "mb_track": "----:com.apple.iTunes:MusicBrainz Release Track Id",
    "mb_release": "----:com.apple.iTunes:MusicBrainz Album Id", "mb_releasegroup": "----:com.apple.iTunes:MusicBrainz Release Group Id",
    "mb_artist": "----:com.apple.iTunes:MusicBrainz Artist Id", "mb_albumartist": "----:com.apple.iTunes:MusicBrainz Album Artist Id",
    "rg_track_gain": "----:com.apple.iTunes:REPLAYGAIN_TRACK_GAIN", "rg_album_gain": "----:com.apple.iTunes:REPLAYGAIN_ALBUM_GAIN",
}
ASF_MAP = {
    "title": "Title", "artist": "Author", "album": "WM/AlbumTitle", "albumartist": "WM/AlbumArtist", "tracknumber": "WM/TrackNumber",
    "discnumber": "WM/PartOfSet", "date": "WM/Year", "originaldate": "WM/OriginalReleaseYear", "genre": "WM/Genre", "composer": "WM/Composer",
    "comment": "Description", "publisher": "WM/Publisher",
    "mb_recording": "MusicBrainz/Track Id", "mb_track": "MusicBrainz/Release Track Id", "mb_release": "MusicBrainz/Album Id",
    "mb_releasegroup": "MusicBrainz/Release Group Id", "mb_artist": "MusicBrainz/Artist Id", "mb_albumartist": "MusicBrainz/Album Artist Id",
    "rg_track_gain": "REPLAYGAIN_TRACK_GAIN", "rg_album_gain": "REPLAYGAIN_ALBUM_GAIN",
}
# frames/keys that hold a picture, lyrics or a managed value are never "junk"
ID3_KEPT = {"APIC", "USLT", "SYLT", "TDRC", "TYER", "TDAT", "TLEN", "TSSE", "TENC", "TSRC", "TSOP", "TSOA", "TSOT", "TPE3", "TIT1", "TIT3", "TCOP", "TBPM", "TKEY", "TLAN", "TMED", "TMOO", "TOPE", "TEXT", "TOLY", "TPE4", "TSO2", "TSOC", "TRSN", "TRSO", "TSST", "TXXX", "UFID", "WOAF", "WOAR", "WOAS", "WORS", "WPUB", "WXXX", "POPM", "PCNT", "TCMP", "IPLS", "TIPL", "TMCL"}


def family(path):
    ext = os.path.splitext(path)[1].lower()
    if ext == ".mp3": return "id3"
    if ext in (".flac",): return "flac"
    if ext in (".ogg", ".oga"): return "vorbis"
    if ext in (".opus",): return "opus"
    if ext in (".m4a", ".m4b", ".mp4", ".aac"): return "mp4"
    if ext in (".wma", ".asf"): return "asf"
    return None


def open_file(path):
    fam = family(path)
    if fam == "id3":
        try:
            return fam, ID3(path)
        except ID3NoHeaderError:
            t = ID3(); t.filename = path; return fam, t
    if fam == "flac": return fam, FLAC(path)
    if fam == "vorbis": return fam, OggVorbis(path)
    if fam == "opus": return fam, OggOpus(path)
    if fam == "mp4": return fam, MP4(path)
    if fam == "asf": return fam, ASF(path)
    return None, None


def first(v):
    if v is None: return None
    if isinstance(v, (list, tuple)):
        return first(v[0]) if v else None
    if isinstance(v, bytes):
        try: return v.decode("utf-8", "replace")
        except Exception: return None
    return str(v)


# ── read ───────────────────────────────────────────────────────────────────────────────────────────────────────
def read_one(path):
    fam, f = open_file(path)
    if f is None:
        return {"path": path, "format": "unknown", "error": "unsupported format"}
    tags, junk, out = {}, [], {"path": path, "format": fam}
    if fam == "id3":
        out["id3_version"] = "2.%d" % f.version[1] if getattr(f, "version", None) else None
        managed = set()
        for key, spec in ID3_MAP.items():
            if isinstance(spec, tuple):
                kind, desc = spec
                if kind == "UFID":
                    fr = f.getall("UFID:" + desc); tags[key] = first(fr[0].data) if fr else None
                else:
                    fr = f.getall("TXXX:" + desc); tags[key] = first(fr[0].text) if fr else None
                managed.add(kind + ":" + desc)
            else:
                fr = f.getall(spec)
                if key == "comment":
                    tags[key] = first(fr[0].text) if fr else None
                else:
                    tags[key] = first(fr[0].text) if fr else None
                managed.add(spec)
        trck = tags.get("tracknumber")
        if trck and "/" in trck:
            tags["tracknumber"], tags["tracktotal"] = trck.split("/", 1)
        tpos = tags.get("discnumber")
        if tpos and "/" in tpos:
            tags["discnumber"], tags["disctotal"] = tpos.split("/", 1)
        counts = {}
        for fr in f.values():
            fid = fr.FrameID
            full = fid + ":" + (getattr(fr, "desc", "") or "") if fid in ("TXXX", "UFID", "COMM", "WXXX") else fid
            if full in managed or fid in ID3_KEPT and fid not in ("TXXX", "UFID"): continue
            if fid in ("TXXX", "UFID") and full in managed: continue
            if fid in ("APIC", "USLT", "SYLT", "COMM"): continue
            counts[fid] = counts.get(fid, 0) + 1
        junk = ["%s ×%d" % (k, n) if n > 1 else k for k, n in sorted(counts.items())]
        out["cover"] = bool(f.getall("APIC"))
        out["lyrics"] = bool(f.getall("USLT") or f.getall("SYLT"))
    elif fam in ("flac", "vorbis", "opus"):
        t = f.tags or {}
        for key, name in VORBIS_MAP.items():
            v = t.get(name); tags[key] = first(v)
        managed = {n.upper() for n in VORBIS_MAP.values()}
        junk = sorted({k.upper() for k in t.keys() if k.upper() not in managed and k.upper() not in ("METADATA_BLOCK_PICTURE", "LYRICS", "UNSYNCEDLYRICS", "COVERART", "COVERARTMIME")})
        out["cover"] = bool(getattr(f, "pictures", None)) or "METADATA_BLOCK_PICTURE" in {k.upper() for k in t.keys()}
        out["lyrics"] = any(k.upper() in ("LYRICS", "UNSYNCEDLYRICS") for k in t.keys())
    elif fam == "mp4":
        t = f.tags or {}
        for key, name in MP4_MAP.items():
            tags[key] = first(t.get(name))
        trkn = t.get("trkn"); disk = t.get("disk")
        if trkn: tags["tracknumber"], tags["tracktotal"] = str(trkn[0][0]), (str(trkn[0][1]) if trkn[0][1] else None)
        if disk: tags["discnumber"], tags["disctotal"] = str(disk[0][0]), (str(disk[0][1]) if disk[0][1] else None)
        managed = set(MP4_MAP.values()) | {"trkn", "disk", "covr", "\xa9lyr", "pgap", "cpil"}
        junk = sorted(k for k in t.keys() if k not in managed)
        out["cover"] = "covr" in t; out["lyrics"] = "\xa9lyr" in t
    elif fam == "asf":
        t = f.tags or {}
        # ffmpeg (Lavf) writes lowercase duplicates beside the WM/ names — `title`, `date`, `album` — and on a file it
        # converted they can be the only copy of a value (this library's WMA: `date` = 1967, no WM/Year). Read them as
        # a fallback; they are not junk, they are the same fact spelled twice, and a write normalises them away.
        LOWER = {"title": "title", "author": "artist", "album": "album", "date": "date", "year": "date", "genre": "genre",
                 "composer": "composer", "track": "tracknumber", "comment": "comment"}
        for key, name in ASF_MAP.items():
            tags[key] = first([str(x) for x in t.get(name, [])]) if t.get(name) else None
        for lk, key in LOWER.items():
            if not tags.get(key) and t.get(lk): tags[key] = first([str(x) for x in t.get(lk, [])])
        managed = set(ASF_MAP.values()) | {"WM/Picture", "WM/Lyrics", "WM/TrackNumber", "WM/Track"} | set(LOWER.keys())
        junk = sorted(k for k in t.keys() if k not in managed)
        out["cover"] = "WM/Picture" in t; out["lyrics"] = "WM/Lyrics" in t
    out["tags"] = {k: v for k, v in tags.items() if v not in (None, "")}
    out["junk"] = junk
    return out


# ── write ──────────────────────────────────────────────────────────────────────────────────────────────────────
def set_id3(f, tags, drop_unmanaged, cover, keep_version):
    existing_version = f.version[1] if getattr(f, "version", None) and f.version[0] == 2 else None
    if drop_unmanaged:
        managed = {"APIC", "USLT", "SYLT", "COMM", "TXXX", "UFID"} | {v for v in ID3_MAP.values() if isinstance(v, str)}
        for key in list(f.keys()):
            if key.split(":")[0] not in managed: del f[key]
    def settext(fid, v):
        if v is None:
            f.delall(fid); return
        cls = Frames[fid]
        f.setall(fid, [cls(encoding=3, text=[v])])
    trck = tags.get("tracknumber"); tt = tags.get("tracktotal")
    if "tracknumber" in tags and trck is not None: tags = dict(tags); tags["tracknumber"] = trck + ("/" + tt if tt else "")
    dn = tags.get("discnumber"); dt = tags.get("disctotal")
    if "discnumber" in tags and dn is not None: tags = dict(tags); tags["discnumber"] = dn + ("/" + dt if dt else "")
    for key, v in tags.items():
        spec = ID3_MAP.get(key)
        if spec is None: continue
        if isinstance(spec, tuple):
            kind, desc = spec
            if kind == "UFID":
                f.delall("UFID:" + desc)
                if v is not None: f.add(Frames["UFID"](owner=desc, data=v.encode("ascii", "ignore")))
            else:
                f.delall("TXXX:" + desc)
                if v is not None: f.add(TXXX(encoding=3, desc=desc, text=[v]))
        elif key == "comment":
            f.delall("COMM")
            if v is not None: f.add(Frames["COMM"](encoding=3, lang="eng", desc="", text=[v]))
        else:
            settext(spec, v)
    if cover:
        with open(cover, "rb") as fh: data = fh.read()
        mime = "image/png" if cover.lower().endswith(".png") else "image/jpeg"
        f.delall("APIC"); f.add(APIC(encoding=3, mime=mime, type=3, desc="Cover", data=data))
    v2 = 3 if (keep_version and existing_version == 3) else 4
    f.save(v2_version=v2)


def set_vorbis(f, tags, drop_unmanaged, cover):
    if f.tags is None: f.add_tags()
    t = f.tags
    if drop_unmanaged:
        managed = {n.upper() for n in VORBIS_MAP.values()} | {"METADATA_BLOCK_PICTURE", "LYRICS", "UNSYNCEDLYRICS"}
        for k in list(t.keys()):
            if k.upper() not in managed: del t[k]
    for key, v in tags.items():
        name = VORBIS_MAP.get(key)
        if name is None: continue
        if v is None:
            if name in t: del t[name]
        else:
            t[name] = [v]
    if cover:
        with open(cover, "rb") as fh: data = fh.read()
        pic = Picture(); pic.type = 3; pic.mime = "image/png" if cover.lower().endswith(".png") else "image/jpeg"; pic.data = data
        if isinstance(f, FLAC):
            f.clear_pictures(); f.add_picture(pic)
        else:
            import base64
            t["METADATA_BLOCK_PICTURE"] = [base64.b64encode(pic.write()).decode("ascii")]
    f.save()


def set_mp4(f, tags, drop_unmanaged, cover):
    if f.tags is None: f.add_tags()
    t = f.tags
    if drop_unmanaged:
        managed = set(MP4_MAP.values()) | {"trkn", "disk", "covr", "\xa9lyr", "pgap", "cpil"}
        for k in list(t.keys()):
            if k not in managed: del t[k]
    tn = tags.get("tracknumber"); tt = tags.get("tracktotal"); dn = tags.get("discnumber"); dt = tags.get("disctotal")
    if "tracknumber" in tags:
        if tn is None: t.pop("trkn", None)
        else: t["trkn"] = [(int(tn), int(tt) if tt else 0)]
    if "discnumber" in tags:
        if dn is None: t.pop("disk", None)
        else: t["disk"] = [(int(dn), int(dt) if dt else 0)]
    for key, v in tags.items():
        name = MP4_MAP.get(key)
        if name is None: continue
        if v is None:
            t.pop(name, None)
        elif name.startswith("----"):
            t[name] = [v.encode("utf-8")]
        else:
            t[name] = [v]
    if cover:
        with open(cover, "rb") as fh: data = fh.read()
        fmt = MP4Cover.FORMAT_PNG if cover.lower().endswith(".png") else MP4Cover.FORMAT_JPEG
        t["covr"] = [MP4Cover(data, imageformat=fmt)]
    f.save()


def set_asf(f, tags, drop_unmanaged, cover):
    t = f.tags
    if drop_unmanaged:
        managed = set(ASF_MAP.values()) | {"WM/Picture", "WM/Lyrics"}
        for k in list(t.keys()):
            if k not in managed: del t[k]
    for key, v in tags.items():
        name = ASF_MAP.get(key)
        if name is None: continue
        # the lowercase Lavf duplicate of a value we are setting goes, so the file cannot say two things
        for lk in [k for k in list(t.keys()) if k.islower() and k in ("title", "author", "album", "date", "year", "genre", "composer", "track", "comment")]:
            if {"title": "title", "author": "artist", "album": "album", "date": "date", "year": "date", "genre": "genre", "composer": "composer", "track": "tracknumber", "comment": "comment"}[lk] == key: del t[lk]
        if v is None:
            if name in t: del t[name]
        else:
            # Phase 290 (FR-290-3) — WM/Year and WM/OriginalReleaseYear hold a year, not a date.
            if key in ("date", "originaldate"): v = v[:4]
            t[name] = [ASFUnicodeAttribute(v)]
    if cover:
        with open(cover, "rb") as fh: data = fh.read()
        # WM/Picture: type(1) + size(4 LE) + mime\0 + desc\0 + data (UTF-16LE strings)
        mime = ("image/png" if cover.lower().endswith(".png") else "image/jpeg").encode("utf-16-le") + b"\x00\x00"
        payload = bytes([3]) + len(data).to_bytes(4, "little") + mime + "Cover".encode("utf-16-le") + b"\x00\x00" + data
        t["WM/Picture"] = [ASFByteArrayAttribute(payload)]
    f.save()


def probe(path):
    try:
        out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration:stream=codec_name,codec_type", "-of", "json", path],
                             capture_output=True, text=True, timeout=60)
        if out.returncode != 0: return None
        j = json.loads(out.stdout)
        dur = float(j.get("format", {}).get("duration") or 0)
        codec = next((s.get("codec_name") for s in j.get("streams", []) if s.get("codec_type") == "audio"), None)
        return dur, codec
    except Exception:
        return None


def write_one(spec, keep_version, keep_unmanaged):
    path = spec["path"]
    res = {"path": path}
    fam = family(path)
    if fam is None:
        res["error"] = "unsupported format"; return res
    before = probe(path)
    if before is None:
        res["error"] = "ffprobe could not read the original"; return res
    d = os.path.dirname(path) or "."
    fd, tmp = tempfile.mkstemp(prefix=".js-tags-", suffix=os.path.splitext(path)[1], dir=d)
    os.close(fd)
    try:
        shutil.copy2(path, tmp)
        fam2, f = open_file(tmp)
        drop = bool(spec.get("drop_unmanaged")) and not keep_unmanaged if "drop_unmanaged" in spec else False
        drop = bool(spec.get("drop_unmanaged"))
        cover = spec.get("cover") or None
        tags = spec.get("tags") or {}
        # Phase 290 — a date is never made less precise: writing 1999 over 1999-09-06 keeps 1999-09-06.
        have = read_one(path).get("tags", {})
        for k in ("date", "originaldate"):
            v, h = tags.get(k), have.get(k)
            if v and h and len(h) > len(v) and h.startswith(v): tags = dict(tags); tags[k] = h
        if fam2 == "id3": set_id3(f, tags, drop, cover, keep_version)
        elif fam2 in ("flac", "vorbis", "opus"): set_vorbis(f, tags, drop, cover)
        elif fam2 == "mp4": set_mp4(f, tags, drop, cover)
        elif fam2 == "asf": set_asf(f, tags, drop, cover)
        after = probe(tmp)
        if after is None:
            res["error"] = "left as it was: ffprobe could not read the result"; return res
        if before[1] != after[1] or abs(before[0] - after[0]) > 1.0:
            res["error"] = "left as it was: the result did not read back the same (codec %s→%s, %.1f→%.1f s)" % (before[1], after[1], before[0], after[0]); return res
        back = read_one(tmp).get("tags", {})
        missing = [k for k, v in tags.items() if v is not None and k in VORBIS_MAP and back.get(k) is None and fam2 != "asf"]
        if missing:
            res["error"] = "left as it was: %s did not read back" % ", ".join(missing); return res
        st = os.stat(path)
        try:
            os.chown(tmp, st.st_uid, st.st_gid)
        except Exception:
            pass
        os.chmod(tmp, st.st_mode & 0o7777)
        os.replace(tmp, path)
        res["written"] = True
        res["tags"] = back
        return res
    except Exception as e:
        res["error"] = "left as it was: %s" % e
        return res
    finally:
        if os.path.exists(tmp):
            try: os.remove(tmp)
            except Exception: pass


def main():
    if len(sys.argv) < 2:
        print(__doc__); sys.exit(1)
    cmd = sys.argv[1]
    if cmd == "read":
        for p in sys.argv[2:]:
            try:
                print(json.dumps(read_one(p), ensure_ascii=False))
            except Exception as e:
                print(json.dumps({"path": p, "error": str(e)}))
        return
    if cmd == "write":
        plan = json.load(sys.stdin)
        keep_version = bool(plan.get("keep_id3_version", True))
        keep_unmanaged = bool(plan.get("keep_unmanaged", True))
        for spec in plan.get("files", []):
            print(json.dumps(write_one(spec, keep_version, keep_unmanaged), ensure_ascii=False), flush=True)
        return
    if cmd == "check":
        print(json.dumps({"ok": True, "mutagen": mutagen.version_string}))
        return
    print(json.dumps({"error": "unknown command %s" % cmd})); sys.exit(1)


if __name__ == "__main__":
    main()
