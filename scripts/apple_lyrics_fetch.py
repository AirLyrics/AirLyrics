#!/usr/bin/env python3
"""Fetch Apple Music lyric TTML for offline auditing.

Usage:
  apple_lyrics_fetch.py "彩虹" "周杰伦" --out DIR [--storefronts tr,cn,jp,us]

Credentials are read from local files and never printed:
  media user token: /opt/hermes/work/airlyrics-credentials/apple-media-user-token.txt
  developer token:  extracted from /tmp/am_index.js (or the cached music.apple.com page)
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

INDEX_JS = "/tmp/am_index.js"
MEDIA_TOKEN = "/opt/hermes/work/airlyrics-credentials/apple-media-user-token.txt"
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")


def http(url: str, token: str | None = None, media: str | None = None):
    headers = {"User-Agent": UA, "Accept": "application/json",
               "Origin": "https://music.apple.com"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if media:
        headers["media-user-token"] = media
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def developer_token() -> str:
    raw = pathlib.Path(INDEX_JS).read_text(encoding="utf-8", errors="ignore")
    for candidate in re.findall(r"eyJ[A-Za-z0-9_.-]{100,}", raw):
        try:
            payload = candidate.split(".")[1]
            payload += "=" * (-len(payload) % 4)
            claims = json.loads(__import__("base64").urlsafe_b64decode(payload))
        except Exception:
            continue
        if str(claims.get("iss", "")).startswith(("AMPWebPlay", "M85D85FTQ", "5IKPP2IECQ")):
            return candidate
    raise SystemExit("no usable developer token in %s" % INDEX_JS)


def pick(songs: list[dict], title: str, artist: str) -> dict | None:
    def score(song: dict) -> int:
        attributes = song.get("attributes", {})
        name = attributes.get("name", "")
        performer = attributes.get("artistName", "")
        value = 0
        if name == title:
            value += 3
        elif title in name or name in title:
            value += 1
        if artist and artist in performer:
            value += 3
        return value
    ranked = sorted(songs, key=score, reverse=True)
    return ranked[0] if ranked and score(ranked[0]) >= 3 else None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("title")
    parser.add_argument("artist", nargs="?", default="")
    parser.add_argument("--out", required=True)
    parser.add_argument("--storefronts", default="tr,cn,jp,us,hk,tw")
    args = parser.parse_args()

    out = pathlib.Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    token = developer_token()
    media = pathlib.Path(MEDIA_TOKEN).read_text().strip()

    storefronts = [s.strip() for s in args.storefronts.split(",") if s.strip()]
    candidates: dict[str, dict] = {}
    for storefront in storefronts:
        term = urllib.parse.quote(f"{args.title} {args.artist}".strip())
        status, body = http(
            f"https://amp-api.music.apple.com/v1/catalog/{storefront}/search?term={term}&types=songs&limit=15",
            token, media)
        if status != 200:
            print(f"{storefront}: search http {status}")
            continue
        for song in json.loads(body).get("results", {}).get("songs", {}).get("data", []):
            candidates.setdefault(song["id"], song)

    if not candidates:
        print("no candidates in any storefront")
        return 1

    # Lyrics availability differs per storefront, so search once and then probe details
    # everywhere (a song found in jp may only carry TTML in tr).
    pool = list(candidates.values())
    if args.artist:
        exact = [s for s in pool if args.artist in s["attributes"].get("artistName", "")]
        pool = exact or pool
    ranked = sorted(pool, key=lambda song: (
        0 if song["attributes"].get("name") == args.title else 1,
        0 if args.artist and args.artist in song["attributes"].get("artistName", "") else 1,
    ))
    for song in ranked[:12]:
        song_id = song["id"]
        for storefront in storefronts:
            url = (f"https://amp-api.music.apple.com/v1/catalog/{storefront}/songs/{song_id}"
                   "?include=syllable-lyrics,lyrics&extend=ttmlLocalizations&l=zh-Hans")
            status, payload = http(url, token, media)
            if status != 200:
                continue
            detail = json.loads(payload)
            relationships = detail["data"][0].get("relationships", {})
            for kind in ("syllable-lyrics", "lyrics"):
                entries = (relationships.get(kind) or {}).get("data") or []
                if not entries:
                    continue
                attributes = entries[0].get("attributes", {})
                ttml = attributes.get("ttmlLocalizations") or attributes.get("ttml")
                if not ttml:
                    continue
                # keep the richer word-timed payload when both exist
                path = out / f"{kind}.ttml"
                path.write_text(ttml, encoding="utf-8")
                (out / "detail.json").write_bytes(payload)
                (out / "source.json").write_text(json.dumps({
                    "storefront": storefront, "song_id": song_id,
                    "name": song["attributes"].get("name"),
                    "artist": song["attributes"].get("artistName"),
                    "album": song["attributes"].get("albumName"),
                    "duration_ms": song["attributes"].get("durationInMillis"),
                    "kind": kind,
                }, ensure_ascii=False, indent=2), encoding="utf-8")
                print(f"{storefront}: {song['attributes'].get('name')} / "
                      f"{song['attributes'].get('artistName')} -> {path} "
                      f"({len(ttml)} bytes, translations={ttml.count('<text for=')})")
                return 0
        print(f"{song['attributes'].get('name')} ({song_id}): no lyric TTML in any storefront")
    return 1


if __name__ == "__main__":
    sys.exit(main())
