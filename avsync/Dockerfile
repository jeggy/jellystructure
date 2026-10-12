# Phase 317 (FR-317-1) — the A/V sync measurer: a sidecar, because the measurement needs a lip-sync model
# on the GPU and cannot be done from container metadata at all.
#
#   docker build -t jellystructure-avsync -f avsync/Dockerfile .     (repo root: the context publish.yml uses)
#   docker compose -f docker-compose.yml -f docker-compose.avsync.yml up -d
#
# or one file by hand, the way the research report was measured:
#
#   docker run --rm --gpus '"device=1","capabilities=compute,utility,video"' -v /media:/media:ro \
#     jellystructure-avsync python /app/measure.py /media/some/file.mkv 20,50,80 45 --pct
#
# `video` in the capabilities is what lets NVDEC in; without it every decode silently falls back to the CPU.
FROM pytorch/pytorch:2.4.1-cuda12.1-cudnn9-runtime

# jellyfin-ffmpeg: the ffmpeg Jellyfin itself runs — scale_cuda and tonemap_cuda (Ubuntu's 4.4 has no tonemap_cuda,
# and its scale_cuda has no format=), libdav1d for AV1, which neither household GPU's NVDEC decodes.
RUN apt-get update && apt-get install -y --no-install-recommends curl gnupg ca-certificates git && \
    mkdir -p /etc/apt/keyrings && \
    curl -fsSL https://repo.jellyfin.org/jellyfin_team.gpg.key | gpg --dearmor -o /etc/apt/keyrings/jellyfin.gpg && \
    echo "deb [signed-by=/etc/apt/keyrings/jellyfin.gpg] https://repo.jellyfin.org/ubuntu jammy main" \
      > /etc/apt/sources.list.d/jellyfin.list && \
    apt-get update && apt-get install -y --no-install-recommends jellyfin-ffmpeg7 && rm -rf /var/lib/apt/lists/* && \
    ln -s /usr/lib/jellyfin-ffmpeg/ffmpeg /usr/local/bin/ffmpeg && ln -s /usr/lib/jellyfin-ffmpeg/ffprobe /usr/local/bin/ffprobe
RUN pip install --no-cache-dir python_speech_features scipy opencv-python-headless

# SyncNet v2 + S3FD (code MIT, Joon Son Chung). The weights' licence is not stated, so they are fetched at build time,
# never committed. Pinned by commit and by hash.
ARG SYNCNET_COMMIT=907c0b579c2e2d83f0eae1b2ac9e720cde4e5623
RUN git clone -q https://github.com/joonson/syncnet_python /app && git -C /app checkout -q ${SYNCNET_COMMIT} && \
    mkdir -p /app/data /app/detectors/s3fd/weights && \
    curl -fsSL -o /app/data/syncnet_v2.model https://www.robots.ox.ac.uk/~vgg/software/lipsync/data/syncnet_v2.model && \
    curl -fsSL -o /app/detectors/s3fd/weights/sfd_face.pth https://www.robots.ox.ac.uk/~vgg/software/lipsync/data/sfd_face.pth && \
    echo "961e8696f888fce4f3f3a6c3d5b3267cf5b343100b238e79b2659bff2c605442  /app/data/syncnet_v2.model" | sha256sum -c - && \
    echo "d54a87c2b7543b64729c9a25eafd188da15fd3f6e02f0ecec76ae1b30d86c491  /app/detectors/s3fd/weights/sfd_face.pth" | sha256sum -c -

COPY avsync/measure.py /app/measure.py
COPY avsync/server.py /app/server.py
WORKDIR /app
# HOME is root's in the base image; the service runs as the host's own uid (the overlay's `user:`), which
# cannot write there — and torch touches ~/.cache on some paths even with every weight already local.
ENV HOME=/tmp PYTHONUNBUFFERED=1 AVSYNC_PORT=8000
EXPOSE 8000
CMD ["python", "/app/server.py"]
