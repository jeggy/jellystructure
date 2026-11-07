#!/usr/bin/env python3
"""DEB-3838 (DEB-3831, FR-6.3): apply a directory of unified diffs to a yt-dlp zipapp, in place.

    ytdlp-apply-patches.py <zipapp> <patch-dir>

The zipapp is the release file: a `#!` line, then a zip. Each *.patch is applied with `patch -p1 --fuzz=0` to the members it names, and
the zip is rewritten with the shebang line kept and every other member copied byte for byte. Exits non-zero, leaving the zipapp as it
was, if any hunk does not apply -- so the image build fails rather than shipping an unpatched extractor.
"""
import glob
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile


def main(zipapp, patch_dir):
    patches = sorted(glob.glob(os.path.join(patch_dir, "*.patch")))
    if not patches:
        sys.exit(f"{patch_dir}: no *.patch files")
    with open(zipapp, "rb") as f:
        head = f.readline()
    if not head.startswith(b"#!"):
        sys.exit(f"{zipapp}: no shebang line")
    with zipfile.ZipFile(zipapp) as zin:
        infos = zin.infolist()
        data = {i.filename: zin.read(i.filename) for i in infos}
    with tempfile.TemporaryDirectory() as work:
        for patch in patches:
            # Materialise only the members this patch names, patch them, read them back.
            names = []
            with open(patch, encoding="utf-8") as f:
                for line in f:
                    if line.startswith("--- a/"):
                        names.append(line[6:].split("\t")[0].rstrip("\n"))
            if not names:
                sys.exit(f"{patch}: names no file")
            for name in names:
                if name not in data:
                    sys.exit(f"{patch}: {name} is not in {zipapp}")
                path = os.path.join(work, name)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "wb") as out:
                    out.write(data[name])
            subprocess.run(["patch", "-p1", "--batch", "--fuzz=0", "--no-backup-if-mismatch", "-d", work, "-i", os.path.abspath(patch)], check=True)
            for name in names:
                with open(os.path.join(work, name), "rb") as f:
                    new = f.read()
                if new == data[name]:
                    sys.exit(f"{patch}: {name} is unchanged after patching")
                data[name] = new
        tmp = zipapp + ".new"
        with open(tmp, "wb") as out:
            out.write(head)
            with zipfile.ZipFile(out, "w") as zout:
                for i in infos:
                    zout.writestr(i, data[i.filename], compress_type=i.compress_type)
        shutil.copymode(zipapp, tmp)
        os.replace(tmp, zipapp)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
