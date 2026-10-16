#!/usr/bin/env python3
"""
MultiBypass Domain Lists Updater
Automated pipeline for collecting, filtering and publishing mobile-first domain lists:
- domains_dns.txt (SmartDNS / Geo-unblocking: Gemini, ChatGPT, Claude, Spotify, etc.)
- domains_antidpi.txt (ByeByeDPI / TCP Desync: YouTube CDN, Discord Voice/Gateway, Meta, X, Signal)

Runs via GitHub Actions on schedule and publishes to GitHub Pages (docs/).
"""

import hashlib
import json
import os
import re
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

# Paths relative to repository root
REPO_ROOT = Path(__file__).resolve().parent.parent
SEEDS_DIR = REPO_ROOT / "seeds"
DOCS_DIR = REPO_ROOT / "docs"
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets"

SEEDS_DNS_FILE = SEEDS_DIR / "seeds_dns.txt"
SEEDS_ANTIDPI_FILE = SEEDS_DIR / "seeds_antidpi.txt"

OUT_DNS_DOCS = DOCS_DIR / "domains_dns.txt"
OUT_ANTIDPI_DOCS = DOCS_DIR / "domains_antidpi.txt"
OUT_VERSION_DOCS = DOCS_DIR / "domains_version.json"

OUT_DNS_ASSETS = ASSETS_DIR / "domains_dns.txt"
OUT_ANTIDPI_ASSETS = ASSETS_DIR / "domains_antidpi.txt"

# External upstream sources
UPSTREAM_ANTIDPI_URLS = [
    # Flowseal community list for Discord, YouTube, GGC and voice endpoints
    "https://raw.githubusercontent.com/Flowseal/zapret-discord-youtube/main/general.txt",
]

UPSTREAM_DNS_URLS = [
    # We can add external community SmartDNS URLs here as they emerge
]

# Hard-exclusion blacklist (no desktop ballast, no torrents, no casinos)
JUNK_PATTERN = re.compile(
    r"(torrent|tracker|rutor|rutracker|nnmclub|kinozal|flibusta|rezka|lostfilm|"
    r"casino|bet|poker|porn|xxx|warez|crack|film|serial)",
    re.IGNORECASE
)

# Domestic Russian/Belarus services and banking to NEVER touch or desync
DOMESTIC_PATTERN = re.compile(
    r"(\.(ru|su|by|xn--p1ai)$|"
    r"(^|\.)(gosuslugi|sber|sberbank|tbank|tinkoff|alfabank|vtb|raiffeisen|"
    r"yandex|dzen|vk|mail|ok|ozon|wildberries|avito|kinopoisk|mos\.ru)\.)",
    re.IGNORECASE
)

DOMAIN_VALIDATOR = re.compile(
    r"^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}$"
)


def fetch_url(url: str, timeout: int = 10) -> str:
    """Fetch text content from a remote URL with error handling."""
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "MultiBypass-Updater/1.0 (+https://github.com/ntvampire/MultiBypass)"}
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            if response.status == 200:
                return response.read().decode("utf-8", errors="ignore")
    except Exception as e:
        print(f"[WARN] Failed to fetch {url}: {e}", file=sys.stderr)
    return ""


def clean_domain(raw: str) -> str:
    """Sanitize and validate a domain string."""
    line = raw.strip().lower()
    if not line or line.startswith("#"):
        return ""

    # Remove protocol, wildcards, ports, trailing slashes
    line = re.sub(r"^https?://", "", line)
    line = re.sub(r"^\*\.", "", line)
    line = line.split("/")[0]
    line = line.split(":")[0]
    line = line.strip(".")

    # Filter out junk, torrents and domestic sites
    if JUNK_PATTERN.search(line):
        return ""
    if DOMESTIC_PATTERN.search(line):
        return ""

    if DOMAIN_VALIDATOR.match(line):
        return line
    return ""


def load_seeds(filepath: Path) -> list[str]:
    """Load curated seed domains from a local file."""
    if not filepath.exists():
        print(f"[WARN] Seed file {filepath} not found!", file=sys.stderr)
        return []

    domains = []
    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            cleaned = clean_domain(line)
            if cleaned:
                domains.append(cleaned)
    return domains


def compute_sha256(content: str) -> str:
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def process_antidpi_domains() -> list[str]:
    """Process and deduplicate Anti-DPI domains (YouTube, Discord, Meta, X, Signal)."""
    domains = set(load_seeds(SEEDS_ANTIDPI_FILE))
    print(f"Loaded {len(domains)} base Anti-DPI seed domains.")

    for url in UPSTREAM_ANTIDPI_URLS:
        print(f"Fetching upstream Anti-DPI: {url}")
        content = fetch_url(url)
        count = 0
        for line in content.splitlines():
            cleaned = clean_domain(line)
            if cleaned and cleaned not in domains:
                # Only include relevant services (Google/YouTube, Discord, Meta, X, etc.)
                if any(k in cleaned for k in ["youtube", "googlevideo", "ytimg", "ggpht", "discord", "instagram", "facebook", "twitter", "signal"]):
                    domains.add(cleaned)
                    count += 1
        print(f"  Added {count} new valid mobile domains from upstream.")

    # Sort alphabetically, prioritizing main domains
    result = sorted(list(domains))
    # Cap to reasonable mobile size (max 250)
    return result[:250]


def process_dns_domains() -> list[str]:
    """Process and deduplicate DNS Geo-unblock domains (Gemini, ChatGPT, Claude, Spotify, etc.)."""
    domains = set(load_seeds(SEEDS_DNS_FILE))
    print(f"Loaded {len(domains)} base DNS seed domains (including full Gemini mobile stack).")

    for url in UPSTREAM_DNS_URLS:
        print(f"Fetching upstream SmartDNS: {url}")
        content = fetch_url(url)
        for line in content.splitlines():
            cleaned = clean_domain(line)
            if cleaned:
                domains.add(cleaned)

    result = sorted(list(domains))
    # Cap to reasonable mobile size (max 200)
    return result[:200]


def main():
    print("=== MultiBypass Domain Lists Pipeline ===")

    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    # 1. Process lists
    antidpi_list = process_antidpi_domains()
    dns_list = process_dns_domains()

    if not antidpi_list or not dns_list:
        print("[ERROR] One of the domain lists is empty! Aborting update to protect database.", file=sys.stderr)
        sys.exit(1)

    # 2. Format outputs with header comments
    dns_text = (
        "# MultiBypass - Свой DNS (SmartDNS / Geo-unblock)\n"
        "# Полный стек Google Gemini & Assistant, ChatGPT, Claude, Spotify, PSN, Xbox\n"
        + "\n".join(dns_list) + "\n"
    )

    antidpi_text = (
        "# MultiBypass - Десинхронизация TCP (ByeByeDPI)\n"
        "# Мобильные узлы YouTube CDN, Discord Voice/Gateway, Meta, X, Signal\n"
        + "\n".join(antidpi_list) + "\n"
    )

    dns_hash = compute_sha256(dns_text)
    antidpi_hash = compute_sha256(antidpi_text)

    # 3. Read previous version metadata
    prev_version = 0
    if OUT_VERSION_DOCS.exists():
        try:
            with open(OUT_VERSION_DOCS, "r", encoding="utf-8") as f:
                data = json.load(f)
                prev_version = data.get("version", 0)
        except Exception:
            prev_version = 0

    now_iso = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    new_version = prev_version + 1

    version_metadata = {
        "version": new_version,
        "updated_at": now_iso,
        "dns": {
            "count": len(dns_list),
            "sha256": dns_hash,
            "filename": "domains_dns.txt"
        },
        "antidpi": {
            "count": len(antidpi_list),
            "sha256": antidpi_hash,
            "filename": "domains_antidpi.txt"
        }
    }

    # 4. Write to docs/ (served by GitHub Pages)
    with open(OUT_DNS_DOCS, "w", encoding="utf-8", newline="\n") as f:
        f.write(dns_text)

    with open(OUT_ANTIDPI_DOCS, "w", encoding="utf-8", newline="\n") as f:
        f.write(antidpi_text)

    with open(OUT_VERSION_DOCS, "w", encoding="utf-8", newline="\n") as f:
        json.dump(version_metadata, f, indent=2, ensure_ascii=False)
        f.write("\n")

    # 5. Mirror to app assets as embedded offline fallback
    with open(OUT_DNS_ASSETS, "w", encoding="utf-8", newline="\n") as f:
        f.write(dns_text)

    with open(OUT_ANTIDPI_ASSETS, "w", encoding="utf-8", newline="\n") as f:
        f.write(antidpi_text)

    print(f"[SUCCESS] Updated:")
    print(f"  - DNS: {len(dns_list)} domains (SHA256: {dns_hash[:10]}...)")
    print(f"  - AntiDPI: {len(antidpi_list)} domains (SHA256: {antidpi_hash[:10]}...)")
    print(f"  - Version: {new_version} ({now_iso})")


if __name__ == "__main__":
    main()
