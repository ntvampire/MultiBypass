#!/usr/bin/env python3
"""
MultiBypass Anti-DPI Domain Lists, TCP Desync Strategies & SmartHosts Pipeline.
Fetches, cleans, deduplicates mobile-first domain lists for Anti-DPI (ByeByeDPI),
sanitizes SmartHosts geoblock mappings (excluding 4PDA, suggestqueries, Google Sign-in and domestic services),
orders TCP desynchronization strategies with modern default #1,
and mirrors configurations for GitHub Pages distribution and app assets.

Runs via GitHub Actions on daily schedule and publishes to GitHub Pages (docs/).
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

SEEDS_ANTIDPI_FILE = SEEDS_DIR / "seeds_antidpi.txt"
SEEDS_STRATEGIES_FILE = SEEDS_DIR / "seeds_strategies.txt"

OUT_ANTIDPI_DOCS = DOCS_DIR / "domains_antidpi.txt"
OUT_STRATEGIES_DOCS = DOCS_DIR / "strategies.txt"
OUT_SMARTHOSTS_DOCS = DOCS_DIR / "smart-no-crutch.hosts"
OUT_VERSION_DOCS = DOCS_DIR / "domains_version.json"

OUT_ANTIDPI_ASSETS = ASSETS_DIR / "domains_antidpi.txt"
OUT_STRATEGIES_ASSETS = ASSETS_DIR / "strategies.txt"
OUT_STRATEGIES_LIST_ASSETS = ASSETS_DIR / "proxytest_strategies.list"
OUT_SMARTHOSTS_ASSETS = ASSETS_DIR / "smart-no-crutch.hosts"

DEFAULT_STRATEGY = "-f -1 -t 8 -n google.com -s 1+s"

# External upstream sources
UPSTREAM_ANTIDPI_URLS = [
    # Flowseal community lists for Discord, YouTube, GGC and voice endpoints
    "https://raw.githubusercontent.com/Flowseal/zapret-discord-youtube/main/lists/list-general.txt",
    "https://raw.githubusercontent.com/Flowseal/zapret-discord-youtube/main/lists/list-google.txt",
]

UPSTREAM_SMARTHOSTS_URL = "https://raw.githubusercontent.com/Noktomezo/RussiaFancyLists/main/lists/hosts/smart-no-crutch.hosts"

# Specific domains to exclude from SmartHosts to prevent conflicts or service breakages
SMARTHOSTS_EXCLUDED_DOMAINS = {
    "apis.google.com",           # Breaks Google Sign-In (OAuth) on mobile apps
    "suggestqueries.google.com",  # Causes search autocomplete latency in YouTube/browser
}

# Hard-exclusion blacklist (no desktop ballast, no torrents, no casinos)
JUNK_PATTERN = re.compile(
    r"(torrent|tracker|rutor|rutracker|nnmclub|kinozal|flibusta|rezka|lostfilm|"
    r"casino|bet|poker|porn|xxx|warez|crack|film|serial)",
    re.IGNORECASE
)

# Domestic Russian/Belarus services and banking to NEVER touch, proxy or desync
DOMESTIC_PATTERN = re.compile(
    r"(\.(ru|su|by|xn--p1ai)$|"
    r"(^|\.)(gosuslugi|sber|sberbank|tbank|tinkoff|alfabank|vtb|raiffeisen|"
    r"yandex|dzen|vk|mail|ok|ozon|wildberries|avito|kinopoisk|mos\.ru)\.)",
    re.IGNORECASE
)

DOMAIN_VALIDATOR = re.compile(
    r"^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,}$"
)

IPV4_REGEX = re.compile(
    r"^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
)


def fetch_url(url: str, timeout: int = 12) -> str:
    """Fetch text content from a remote URL with error handling."""
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "MultiBypass-Updater/3.0 (+https://github.com/ntvampire/MultiBypass)"}
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

    if not DOMAIN_VALIDATOR.match(line):
        return ""

    # Filter out junk and domestic banking/services
    if JUNK_PATTERN.search(line) or DOMESTIC_PATTERN.search(line):
        return ""

    return line


def load_seeds(file_path: Path) -> list[str]:
    """Load and sanitize domains from local seed file."""
    if not file_path.exists():
        print(f"[WARN] Seed file {file_path} not found!", file=sys.stderr)
        return []

    domains = set()
    with open(file_path, "r", encoding="utf-8") as f:
        for line in f:
            cleaned = clean_domain(line)
            if cleaned:
                domains.add(cleaned)
    return sorted(list(domains))


def compute_sha256(content: str) -> str:
    """Compute hex sha256 of text string."""
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

    result = sorted(list(domains))
    return result[:250]


def process_strategies() -> list[str]:
    """Process, validate, deduplicate and sort TCP desync strategies."""
    if not SEEDS_STRATEGIES_FILE.exists():
        print(f"[WARN] Strategies seed file {SEEDS_STRATEGIES_FILE} not found!", file=sys.stderr)
        return [DEFAULT_STRATEGY]

    strategies = []
    seen = set()

    with open(SEEDS_STRATEGIES_FILE, "r", encoding="utf-8") as f:
        for line in f:
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            if s not in seen:
                seen.add(s)
                strategies.append(s)

    # Ensure DEFAULT_STRATEGY is always at position 0
    if DEFAULT_STRATEGY in strategies:
        strategies.remove(DEFAULT_STRATEGY)
    strategies.insert(0, DEFAULT_STRATEGY)

    return strategies


def is_smarthosts_excluded(domain: str) -> bool:
    """Check if domain should be stripped from SmartHosts SNI relay."""
    d = domain.strip().lower().rstrip(".")
    if not d:
        return True
    if "4pda" in d:
        return True
    if d in SMARTHOSTS_EXCLUDED_DOMAINS:
        return True
    return False


def process_smarthosts() -> tuple[str, int]:
    """Fetch, sanitize and format SmartHosts geoblock mapping."""
    print(f"Fetching upstream SmartHosts: {UPSTREAM_SMARTHOSTS_URL}")
    raw_content = fetch_url(UPSTREAM_SMARTHOSTS_URL, timeout=15)
    if not raw_content and OUT_SMARTHOSTS_ASSETS.exists():
        print("[WARN] Failed to fetch upstream SmartHosts, using local assets fallback.")
        with open(OUT_SMARTHOSTS_ASSETS, "r", encoding="utf-8") as f:
            raw_content = f.read()

    if not raw_content:
        print("[ERROR] SmartHosts content is empty!", file=sys.stderr)
        return "", 0

    unique_domains = set()
    cleaned_lines = [
        "# MultiBypass - SmartHosts Geoblock SNI-Relay Map",
        "# Processed and sanitized: 4PDA, Google Sign-In, suggestqueries and RU/BY domains excluded.",
        ""
    ]

    for line in raw_content.splitlines():
        s = line.strip()
        if not s or s.startswith("#"):
            continue

        tokens = s.split()
        if len(tokens) < 2:
            continue

        ip = tokens[0].strip()
        if not IPV4_REGEX.match(ip) or ip in ("127.0.0.1", "0.0.0.0"):
            continue

        valid_domains = []
        for dom in tokens[1:]:
            cdom = clean_domain(dom)
            if cdom and not is_smarthosts_excluded(cdom):
                valid_domains.append(cdom)
                unique_domains.add(cdom)

        if valid_domains:
            cleaned_lines.append(f"{ip} {' '.join(sorted(valid_domains))}")

    result_text = "\n".join(cleaned_lines) + "\n"
    return result_text, len(unique_domains)


def main():
    print("=== MultiBypass Lists & Strategies Pipeline ===")

    DOCS_DIR.mkdir(parents=True, exist_ok=True)
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    # 1. Process domain lists, strategies and SmartHosts
    antidpi_list = process_antidpi_domains()
    strategies_list = process_strategies()
    smarthosts_text, smarthosts_count = process_smarthosts()

    if not antidpi_list or not strategies_list or not smarthosts_text:
        print("[ERROR] One of the lists is empty! Aborting update to protect database.", file=sys.stderr)
        sys.exit(1)

    # 2. Format outputs with header comments
    antidpi_text = (
        "# MultiBypass - Десинхронизация TCP (ByeByeDPI)\n"
        "# Мобильные узлы YouTube CDN, Discord Voice/Gateway, Meta, X, Signal\n"
        + "\n".join(antidpi_list) + "\n"
    )

    strategies_text = (
        "# MultiBypass - Пресеты десинхронизации TCP (ByeByeDPI)\n"
        "# Проверенные стратегии обхода ТСПУ для YouTube, Discord и веб-сервисов\n"
        + "\n".join(strategies_list) + "\n"
    )

    antidpi_hash = compute_sha256(antidpi_text)
    strategies_hash = compute_sha256(strategies_text)
    smarthosts_hash = compute_sha256(smarthosts_text)

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
        "antidpi": {
            "count": len(antidpi_list),
            "sha256": antidpi_hash,
            "filename": "domains_antidpi.txt"
        },
        "strategies": {
            "count": len(strategies_list),
            "sha256": strategies_hash,
            "filename": "strategies.txt"
        },
        "smarthosts": {
            "count": smarthosts_count,
            "sha256": smarthosts_hash,
            "filename": "smart-no-crutch.hosts"
        }
    }

    # 4. Write files to docs/ (GitHub Pages distribution)
    with open(OUT_ANTIDPI_DOCS, "w", encoding="utf-8", newline="\n") as f:
        f.write(antidpi_text)

    with open(OUT_STRATEGIES_DOCS, "w", encoding="utf-8", newline="\n") as f:
        f.write(strategies_text)

    with open(OUT_SMARTHOSTS_DOCS, "w", encoding="utf-8", newline="\n") as f:
        f.write(smarthosts_text)

    with open(OUT_VERSION_DOCS, "w", encoding="utf-8", newline="\n") as f:
        json.dump(version_metadata, f, indent=2, ensure_ascii=False)
        f.write("\n")

    # 5. Mirror to app assets as embedded offline fallback
    with open(OUT_ANTIDPI_ASSETS, "w", encoding="utf-8", newline="\n") as f:
        f.write(antidpi_text)

    with open(OUT_STRATEGIES_ASSETS, "w", encoding="utf-8", newline="\n") as f:
        f.write(strategies_text)

    with open(OUT_STRATEGIES_LIST_ASSETS, "w", encoding="utf-8", newline="\n") as f:
        f.write(strategies_text)

    with open(OUT_SMARTHOSTS_ASSETS, "w", encoding="utf-8", newline="\n") as f:
        f.write(smarthosts_text)

    print(f"[SUCCESS] Updated:")
    print(f"  - AntiDPI: {len(antidpi_list)} domains (SHA256: {antidpi_hash[:10]}...)")
    print(f"  - Strategies: {len(strategies_list)} presets (Default #1: {DEFAULT_STRATEGY[:30]}...)")
    print(f"  - SmartHosts: {smarthosts_count} geoblock domains (SHA256: {smarthosts_hash[:10]}...)")
    print(f"  - Version: {new_version} ({now_iso})")


if __name__ == "__main__":
    main()
