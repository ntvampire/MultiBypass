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
import socket
import sys
import time
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

# Paths relative to repository root
REPO_ROOT = Path(__file__).resolve().parent.parent
SEEDS_DIR = REPO_ROOT / "seeds"
DOCS_DIR = REPO_ROOT / "docs"
ASSETS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets"

SEEDS_ANTIDPI_FILE = SEEDS_DIR / "seeds_antidpi.txt"
SEEDS_STRATEGIES_FILE = SEEDS_DIR / "seeds_strategies.txt"
SEEDS_GEOBLOCK_FILE = SEEDS_DIR / "seeds_geoblock.txt"

OUT_ANTIDPI_DOCS = DOCS_DIR / "domains_antidpi.txt"
OUT_STRATEGIES_DOCS = DOCS_DIR / "strategies.txt"
OUT_SMARTHOSTS_DOCS = DOCS_DIR / "smart-no-crutch.hosts"
OUT_VERSION_DOCS = DOCS_DIR / "domains_version.json"

OUT_ANTIDPI_ASSETS = ASSETS_DIR / "domains_antidpi.txt"
OUT_STRATEGIES_ASSETS = ASSETS_DIR / "strategies.txt"
OUT_STRATEGIES_LIST_ASSETS = ASSETS_DIR / "proxytest_strategies.list"
OUT_SMARTHOSTS_ASSETS = ASSETS_DIR / "smart-no-crutch.hosts"

DEFAULT_STRATEGY = "-s1 -f-1 -t5 -a1"

# External upstream sources for Anti-DPI
UPSTREAM_ANTIDPI_URLS = [
    # Flowseal community lists for Discord, YouTube, GGC and voice endpoints
    "https://raw.githubusercontent.com/Flowseal/zapret-discord-youtube/main/lists/list-general.txt",
    "https://raw.githubusercontent.com/Flowseal/zapret-discord-youtube/main/lists/list-google.txt",
]

# Primary SmartHosts SNI-proxy providers for geoblock bypass
SMARTHOSTS_PROVIDERS = [
    ("GeoHide EU", "https://geohide.ru/eu/hosts"),
    ("ImMALWARE", "https://raw.githubusercontent.com/ImMALWARE/dns.malw.link/refs/heads/master/hosts"),
    ("AstraCat", "https://raw.githubusercontent.com/ASTRACAT2022/host-DNS/main/base_hosts.txt"),
    ("Noktomezo (Fallback)", "https://raw.githubusercontent.com/Noktomezo/RussiaFancyLists/main/lists/hosts/smart-no-crutch.hosts"),
]

# Anti-DPI service roots to strictly exclude from SmartHosts (these are handled by ByeByeDPI)
ANTIDPI_ROOTS = [
    "youtube.com", "googlevideo.com", "ytimg.com", "ggpht.com", "gvt1.com", "gvt2.com",
    "discord.com", "discordapp.com", "discord.gg",
    "instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net",
    "twitter.com", "twimg.com", "t.co", "x.com",
    "signal.org", "whatsapp.com"
]

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


def is_private_ip(ip: str) -> bool:
    """Check if IPv4 address is loopback, broadcast or private network."""
    if ip.startswith(("127.", "0.", "10.", "192.168.", "169.254.")):
        return True
    if ip.startswith("172."):
        parts = ip.split(".")
        if len(parts) > 1:
            try:
                octet = int(parts[1])
                if 16 <= octet <= 31:
                    return True
            except ValueError:
                pass
    return False


def is_antidpi_domain(domain: str) -> bool:
    """Check if domain belongs to Anti-DPI (ByeByeDPI) and should never be routed to SmartHosts."""
    d = domain.lower()
    for root in ANTIDPI_ROOTS:
        if d == root or d.endswith("." + root):
            return True
    return False


def is_smarthosts_excluded(domain: str) -> bool:
    """Check if domain should be stripped from SmartHosts SNI relay."""
    d = domain.strip().lower().rstrip(".")
    if not d:
        return True
    if "4pda" in d:
        return True
    if d in SMARTHOSTS_EXCLUDED_DOMAINS:
        return True
    if is_antidpi_domain(d):
        return True
    return False


def probe_sni_relay(ip: str, timeout: float = 2.5) -> tuple[str, float | None]:
    """Measure TCP :443 connect latency for a candidate SNI relay IP."""
    start = time.monotonic()
    try:
        with socket.create_connection((ip, 443), timeout=timeout):
            latency_ms = (time.monotonic() - start) * 1000.0
            return ip, latency_ms
    except OSError:
        return ip, None


def check_sni_relays(
    candidate_ips: list[str],
    timeout: float = 2.5,
    max_workers: int = 24,
) -> dict[str, float]:
    """Probe candidate SNI relay IPs concurrently and return {ip: latency_ms} for reachable relays."""
    if not candidate_ips:
        return {}
    healthy: dict[str, float] = {}
    workers = max(1, min(max_workers, len(candidate_ips)))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for ip, latency_ms in pool.map(lambda addr: probe_sni_relay(addr, timeout=timeout), candidate_ips):
            if latency_ms is not None:
                healthy[ip] = latency_ms
    return healthy


def process_smarthosts() -> tuple[str, int]:
    """
    Fetch, aggregate, sanitize and format SmartHosts geoblock mapping
    from primary European SNI proxy providers (GeoHide EU, ImMALWARE, AstraCat, Noktomezo).
    """
    geoblock_seeds = set(load_seeds(SEEDS_GEOBLOCK_FILE))
    print(f"Loaded {len(geoblock_seeds)} curated geoblock seed domains.")

    domain_to_ips: dict[str, list[str]] = defaultdict(list)
    successful_providers = 0

    for name, url in SMARTHOSTS_PROVIDERS:
        print(f"Fetching SmartHosts provider: {name} ({url})")
        content = fetch_url(url, timeout=15)
        if not content:
            print(f"  [WARN] Failed to fetch {name}")
            continue

        successful_providers += 1
        added_entries = 0
        for line in content.splitlines():
            s = line.strip()
            if not s or s.startswith("#"):
                continue

            tokens = s.split()
            if len(tokens) < 2:
                continue

            ip = tokens[0].strip()
            if not IPV4_REGEX.match(ip) or is_private_ip(ip):
                continue

            for dom in tokens[1:]:
                cdom = clean_domain(dom)
                if not cdom or is_smarthosts_excluded(cdom):
                    continue

                if ip not in domain_to_ips[cdom]:
                    domain_to_ips[cdom].append(ip)
                    added_entries += 1

        print(f"  Processed {name}: +{added_entries} entries (running total: {len(domain_to_ips)} domains)")

    # Fallback to local asset if all network fetches failed
    if not domain_to_ips and OUT_SMARTHOSTS_ASSETS.exists():
        print("[WARN] All remote SmartHosts providers failed! Falling back to bundled assets.", file=sys.stderr)
        with open(OUT_SMARTHOSTS_ASSETS, "r", encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if not s or s.startswith("#"):
                    continue
                tokens = s.split()
                if len(tokens) < 2:
                    continue
                ip = tokens[0].strip()
                if not IPV4_REGEX.match(ip) or is_private_ip(ip):
                    continue
                for dom in tokens[1:]:
                    cdom = clean_domain(dom)
                    if cdom and not is_smarthosts_excluded(cdom):
                        if ip not in domain_to_ips[cdom]:
                            domain_to_ips[cdom].append(ip)

    if not domain_to_ips:
        print("[ERROR] SmartHosts mapping is completely empty!", file=sys.stderr)
        return "", 0

    all_candidate_ips = sorted({ip for ips in domain_to_ips.values() for ip in ips})
    healthy_latencies: dict[str, float] = {}
    if os.environ.get("SKIP_RELAY_HEALTHCHECK") == "1":
        print(f"Skipping SNI relay health-check (SKIP_RELAY_HEALTHCHECK=1); keeping all {len(all_candidate_ips)} IPs.")
    else:
        print(f"Health-checking {len(all_candidate_ips)} unique SmartHosts SNI relay IPs (TCP :443)...")
        healthy_latencies = check_sni_relays(all_candidate_ips)
        print(f"  Healthy SNI relays: {len(healthy_latencies)}/{len(all_candidate_ips)}")

    use_health_filter = len(healthy_latencies) >= 3
    if not use_health_filter and all_candidate_ips and os.environ.get("SKIP_RELAY_HEALTHCHECK") != "1":
        print(
            "  [WARN] Too few healthy SNI relays detected; keeping unfiltered upstream IPs as fallback.",
            file=sys.stderr,
        )

    # Group domains by proxy IP (keeping top 2 healthy IPs sorted by lowest latency)
    ip_to_domains: dict[str, list[str]] = defaultdict(list)
    active_domains = 0
    skipped_unhealthy_domains = 0
    for dom, ips in sorted(domain_to_ips.items()):
        if use_health_filter:
            live_ips = sorted(
                (ip for ip in ips if ip in healthy_latencies),
                key=lambda addr: healthy_latencies[addr],
            )
            if not live_ips:
                skipped_unhealthy_domains += 1
                continue
            selected_ips = live_ips[:2]
        else:
            selected_ips = ips[:2]
        active_domains += 1
        for ip in selected_ips:
            ip_to_domains[ip].append(dom)

    if skipped_unhealthy_domains > 0:
        print(f"  Dropped {skipped_unhealthy_domains} domain(s) with no reachable SNI relay.")

    if not ip_to_domains:
        print("[ERROR] No live SmartHosts proxy endpoints remained!", file=sys.stderr)
        return "", 0

    health_note = (
        f"live proxy endpoints: {len(ip_to_domains)}/{len(all_candidate_ips)}"
        if use_health_filter
        else f"proxy endpoints: {len(ip_to_domains)} (unfiltered)"
    )

    cleaned_lines = [
        "# MultiBypass - SmartHosts Geoblock SNI-Relay Map",
        "# Autonomous multi-provider aggregation (GeoHide EU, ImMALWARE, AstraCat).",
        "# Processed and sanitized: 4PDA, Google Sign-In, suggestqueries, Anti-DPI and domestic RU/BY services excluded.",
        f"# Total unique geoblock domains: {active_domains}, {health_note}",
        ""
    ]

    # Format into /etc/hosts lines chunked by 6 domains per line for clean readability
    for ip in sorted(ip_to_domains.keys()):
        doms = sorted(list(set(ip_to_domains[ip])))
        chunk_size = 6
        for i in range(0, len(doms), chunk_size):
            chunk = doms[i:i + chunk_size]
            cleaned_lines.append(f"{ip} {' '.join(chunk)}")

    result_text = "\n".join(cleaned_lines) + "\n"
    return result_text, active_domains


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
