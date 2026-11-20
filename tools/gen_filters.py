#!/usr/bin/env python3
"""Generate the browser's bundled filter assets.

Sources are the same public filter data Brave uses (Brave-specific lists plus
EasyList/EasyPrivacy/uBlock filters). Everything is compiled into small text
assets at build-prep time; the Android app never downloads or parses upstream
filter files at runtime.
"""

import re
import shutil
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets"
CACHE = ROOT / "tools" / ".filter-cache"
OUT.mkdir(parents=True, exist_ok=True)
CACHE.mkdir(parents=True, exist_ok=True)

BRAVE = "https://raw.githubusercontent.com/brave/adblock-lists/master/brave-lists/"
SOURCES = [
    ("easylist.txt", "https://easylist.to/easylist/easylist.txt", 0),
    ("easyprivacy.txt", "https://easylist.to/easylist/easyprivacy.txt", 1),
    ("ubo-filters.txt", "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt", 2),
    ("brave-specific.txt", BRAVE + "brave-specific.txt", 4),
    ("brave-android-specific.txt", BRAVE + "brave-android-specific.txt", 5),
    ("brave-social.txt", BRAVE + "brave-social.txt", 5),
    ("brave-cookie-specific.txt", BRAVE + "brave-cookie-specific.txt", 4),
    ("brave-firstparty.txt", BRAVE + "brave-firstparty.txt", 4),
    ("filters-mirror.txt", BRAVE + "filters-mirror.txt", 4),
    ("yt-distracting.txt", BRAVE + "yt-distracting.txt", 6),
    ("yt-recommended.txt", BRAVE + "yt-recommended.txt", 6),
    ("yt-shorts.txt", BRAVE + "yt-shorts.txt", 6),
]

# Reuse the original upstream snapshots from earlier work when available.
LOCAL_SNAPSHOTS = {
    "easylist.txt": Path("/home/maharab/.local/share/opencode/tool-output/tool_1f8179158001kyVXQPEFZxoL6I"),
    "easyprivacy.txt": Path("/home/maharab/.local/share/opencode/tool-output/tool_1f81790ea0013XVS0BYjzH13cY"),
    "ubo-filters.txt": Path("/home/maharab/.local/share/opencode/tool-output/tool_1f817904a001fH9doTgNk2ueyb"),
    "brave-specific.txt": Path("/tmp/opencode/brave_lists/brave-specific.txt"),
    "brave-android-specific.txt": Path("/tmp/opencode/brave_lists/brave-android-specific.txt"),
    "brave-social.txt": Path("/tmp/opencode/brave_lists/brave-social.txt"),
    "brave-cookie-specific.txt": Path("/tmp/opencode/brave_lists/brave-cookie-specific.txt"),
    "brave-firstparty.txt": Path("/tmp/opencode/brave_lists/brave-firstparty.txt"),
    "filters-mirror.txt": Path("/tmp/opencode/brave_lists/filters-mirror.txt"),
    "yt-distracting.txt": Path("/tmp/opencode/brave_lists/yt-distracting.txt"),
    "yt-recommended.txt": Path("/tmp/opencode/brave_lists/yt-recommended.txt"),
    "yt-shorts.txt": Path("/tmp/opencode/brave_lists/yt-shorts.txt"),
}

HOST_RE = re.compile(r"^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,}$")
KEYWORDS = (
    "ad", "ads", "advert", "banner", "track", "analytic", "pixel", "beacon",
    "stat", "counter", "click", "conv", "sync", "segment", "gtm", "tagmanager",
    "doubleclick", "syndication", "taboola", "outbrain", "criteo", "scorecard",
    "quantserve", "hotjar", "mixpanel", "amplitude", "fullstory", "newrelic",
    "telemetry", "fingerprint", "impression", "collect", "metric", "analytics",
    "sponsor", "promoted", "popunder", "popup",
)
CURATED_ROOTS = {
    "doubleclick.net", "googleadservices.com", "googlesyndication.com",
    "google-analytics.com", "googletagmanager.com", "googletagservices.com",
    "adservice.google.com", "adnxs.com", "adsrvr.org", "rubiconproject.com",
    "pubmatic.com", "criteo.com", "casalemedia.com", "openx.net", "yieldmo.com",
    "3lift.com", "adcolony.com", "applovin.com", "unityads.com", "vungle.com",
    "chartboost.com", "scorecardresearch.com", "quantserve.com", "hotjar.com",
    "mixpanel.com", "amplitude.com", "segment.com", "fullstory.com",
    "taboola.com", "outbrain.com", "bat.bing.com", "clarity.ms", "newrelic.com",
    "facebook.net", "connect.facebook.net", "platform.twitter.com",
    "amazon-adsystem.com", "ads-twitter.com", "analytics.twitter.com",
    "adsterra.com", "propellerads.com", "popcash.net", "exoclick.com",
    "juicyads.com", "trafficjunky.com", "clickadu.com", "richads.com",
    "onclickads.net", "adf.ly", "bc.vc",
}
BULK_CDNS = (
    "cloudfront.net", "amazonaws.com", "azureedge.net", "akamaized.net",
    "fastly.net", "googleusercontent.com", "scdn.co",
)
ESSENTIAL_HOSTS = {
    "googlevideo.com", "ytimg.com", "ggpht.com", "gstatic.com",
    "googleusercontent.com", "youtube.com", "youtu.be", "google.com",
}
SKIP_MODIFIERS = {
    "replace", "redirect", "redirect-rule", "csp", "popup", "document",
    "generichide", "elemhide", "json", "removeparam", "badfilter", "ghide",
    "denyallow", "1p", "first-party", "~third-party", "header", "cookie",
}
EXCEPTION_SKIP_MODIFIERS = {"replace", "redirect", "redirect-rule", "csp", "badfilter"}
THIRD_PARTY_MODIFIERS = {"3p", "third-party"}

block_hosts, allow_hosts, third_party_hosts = set(), set(), set()
host_weights = {}
domain_block_rules, domain_allow_rules = [], []
block_patterns, allow_patterns, third_party_patterns = set(), set(), set()
cosmetic = []


def read_source(name, url):
    cached = CACHE / name
    local = LOCAL_SNAPSHOTS.get(name)
    if local and local.is_file() and (not cached.exists() or local.stat().st_size > cached.stat().st_size):
        shutil.copyfile(local, cached)
    if not cached.is_file():
        request = Request(url, headers={"User-Agent": "LightweightBrowser-filter-generator/1.0"})
        cached.write_bytes(urlopen(request, timeout=90).read())
    return cached.read_text(encoding="utf-8", errors="ignore").splitlines()


def strip_modifiers(line):
    if "$" not in line:
        return line, ""
    base, mods = line.split("$", 1)
    return base, mods.lower()


def parse_modifiers(mods):
    tokens = [token.strip() for token in mods.split(",") if token.strip()]
    names = {token.split("=", 1)[0] for token in tokens}
    domains, excluded = [], []
    for token in tokens:
        if token.startswith("domain="):
            for raw in token[len("domain="):].split("|"):
                domain = raw.strip().lstrip(".")
                if not domain:
                    continue
                if domain.startswith("~"):
                    excluded.append(domain[1:])
                else:
                    domains.append(domain)
    return names, tuple(domains), tuple(excluded)


def valid_host(host):
    if host.startswith("*."):
        host = host[2:]
    host = host.strip(".").lower()
    return len(host) >= 5 and "." in host and bool(HOST_RE.match(host))


def is_essential(host):
    return any(host == root or host.endswith("." + root) for root in ESSENTIAL_HOSTS)


def note_host(host, weight):
    previous = host_weights.get(host, -1)
    if weight > previous:
        host_weights[host] = weight


def extract_host(base):
    if base.startswith("||"):
        rest = base[2:]
        match = re.match(r"^([A-Za-z0-9.*_-]+(?:\.[A-Za-z0-9.*_-]+)+)", rest)
        if match:
            return match.group(1)
    match = re.match(r"^\|https?://([^/^|]+)", base, re.I)
    if match:
        return match.group(1)
    return None


def clean_pattern(base):
    value = base.lower()
    value = value.replace("*", "").replace("^", "").replace("|", "")
    value = value.strip(".:?&=/ ")
    if len(value) < 5 or len(value) > 110:
        return None
    if " " in value or "(" in value or "[" in value or "{" in value:
        return None
    if "/" in value and value.count("/") > 6:
        return None
    if not any(keyword in value for keyword in KEYWORDS):
        return None
    return value


def split_selector_list(value):
    parts, buf = [], []
    depth_p, depth_b = 0, 0
    quote = None
    for ch in value:
        if quote:
            buf.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in ("'", '"'):
            quote = ch
            buf.append(ch)
        elif ch == "(":
            depth_p += 1
            buf.append(ch)
        elif ch == ")":
            depth_p -= 1
            buf.append(ch)
        elif ch == "[":
            depth_b += 1
            buf.append(ch)
        elif ch == "]":
            depth_b -= 1
            buf.append(ch)
        elif ch == "," and depth_p == 0 and depth_b == 0:
            part = "".join(buf).strip()
            if part:
                parts.append(part)
            buf = []
        else:
            buf.append(ch)
    part = "".join(buf).strip()
    if part:
        parts.append(part)
    return parts


def valid_selector(selector):
    if not (3 <= len(selector) <= 180):
        return False
    if any(ch in selector for ch in (":", "\\", "<", ";", "{", "}", "/")):
        return False
    if any(token in selector for token in ("+js", ":has", ":xpath", "#?")):
        return False
    return bool(re.match(r"^[A-Za-z0-9_#.\[\]=\"'^$* ,>~+|_-]+$", selector))


for name, url, weight in SOURCES:
    for raw in read_source(name, url):
        line = raw.strip()
        if not line or line.startswith("!") or line.startswith("["):
            continue
        # uBO/ABP scriptlet injections and snippets are not supported by WebView.
        if "#?#" in line:
            continue

        if "##" in line or "#@#" in line:
            exception = "#@#" in line
            separator = "#@#" if exception else "##"
            domain_part, selector_part = line.split(separator, 1)
            if selector_part.startswith("+js"):
                continue
            raw_domains = [
                domain.strip().lower().lstrip(".")
                for domain in domain_part.split(",")
                if domain.strip()
            ]
            domains = tuple(domain for domain in raw_domains if not domain.startswith("~"))
            if raw_domains and not domains:
                continue
            domains = domains or None
            for selector in split_selector_list(selector_part):
                if valid_selector(selector):
                    cosmetic.append((domains, selector, exception))
            continue

        exception = line.startswith("@@")
        if exception:
            line = line[2:]
        base, mods = strip_modifiers(line)
        names, domains, _excluded = parse_modifiers(mods)
        if names & (EXCEPTION_SKIP_MODIFIERS if exception else SKIP_MODIFIERS):
            continue
        third_party = bool(names & THIRD_PARTY_MODIFIERS)

        host = extract_host(base)
        if host and valid_host(host):
            host = host.lower().lstrip("*.").strip(".")
            if is_essential(host):
                continue
            if exception:
                if domains:
                    domain_allow_rules.append((domains, host))
                else:
                    allow_hosts.add(host)
                    note_host(host, weight)
            elif domains:
                domain_block_rules.append((domains, host, False))
                note_host(host, weight)
            elif third_party:
                third_party_hosts.add(host)
                note_host(host, weight)
            else:
                block_hosts.add(host)
                note_host(host, weight)
            continue

        # Domain-scoped URL fragments are skipped: the runtime matcher only
        # supports global and third-party literal fragments.
        if domains:
            continue
        pattern = clean_pattern(base)
        if not pattern:
            continue
        if exception:
            allow_patterns.add(pattern)
        elif third_party:
            third_party_patterns.add(pattern)
        else:
            block_patterns.add(pattern)

allow_hosts.update(ESSENTIAL_HOSTS)
allow_hosts -= {"example.com", "example.org", "example.net", "test.com", "localhost"}
block_hosts.update(CURATED_ROOTS)
block_hosts -= allow_hosts
third_party_hosts -= allow_hosts
third_party_hosts -= block_hosts
block_patterns -= allow_patterns
third_party_patterns -= allow_patterns
third_party_patterns -= block_patterns
domain_block_rules = [
    rule for rule in set(domain_block_rules) if not is_essential(rule[1])
]
domain_allow_rules = set(domain_allow_rules)


def host_score(host):
    score = 0
    if any(keyword in host for keyword in KEYWORDS):
        score += 12
    if host_weights.get(host, 0) >= 1:
        score += 6
    if host_weights.get(host, 0) >= 4:
        score += 15
    if host in CURATED_ROOTS or any(host.endswith("." + root) for root in CURATED_ROOTS):
        score += 25
    if len(host) <= 28:
        score += 3
    if re.search(r"[0-9a-f]{12,}", host):
        score -= 8
    if any(host.endswith("." + cdn) for cdn in BULK_CDNS) and not any(
        keyword in host for keyword in KEYWORDS
    ):
        score -= 20
    return score


def ranked_hosts(values, limit):
    return sorted(sorted(values, key=lambda host: (-host_score(host), host))[:limit])


hosts = ranked_hosts(block_hosts, 15000)
third_party = ranked_hosts(third_party_hosts, 4000)
allow_host_list = sorted(allow_hosts)

EXTRA_PATTERNS = {
    "/ads/", "/ad/", "/advert", "/banner", "/popup", "/popunder",
    "/pop-up", "/pixel", "/beacon", "/analytics", "/gtm.js", "/adslot",
    "/adserve", "/tracking", "?ad=", "&ad=", "/sponsor",
}
patterns = sorted(set(block_patterns) | EXTRA_PATTERNS)[:3000]
third_party_pattern_list = sorted(third_party_patterns)[:2000]
allow_pattern_list = sorted(allow_patterns)[:4000]

domain_host_rules = sorted(
    set(domain_block_rules) | {(domains, host, True) for domains, host in domain_allow_rules},
    key=lambda rule: (rule[0], rule[1], rule[2]),
)[:8000]

# Cosmetic rules: Brave's YouTube distraction lists are kept even when the
# generic rule budget is full, because they are small and high value.
generic, generic_exceptions = [], set()
domain_rules, domain_exceptions = [], []
seen = set()
for domains, selector, exception in cosmetic:
    key = (domains, selector, exception)
    if key in seen:
        continue
    seen.add(key)
    if exception:
        if domains:
            domain_exceptions.append((domains, selector))
        else:
            generic_exceptions.add(selector)
    elif domains is None:
        generic.append(selector)
    else:
        domain_rules.append((domains, selector))


def selector_score(selector):
    low = selector.lower()
    score = 0
    for keyword in KEYWORDS:
        if keyword in low:
            score += 8
    if low.startswith("."):
        score += 6
    elif low.startswith("#"):
        score += 5
    if len(low) > 45:
        score -= 2
    return score


PRIORITY_DOMAINS = {
    "youtube.com", "m.youtube.com", "youtubekids.com", "youtube-nocookie.com",
}


def domain_priority(rule):
    domains, selector = rule
    priority = 0 if any(domain in PRIORITY_DOMAINS for domain in domains) else 1
    return priority, domains, selector


generic = sorted(set(generic) - generic_exceptions, key=lambda s: (-selector_score(s), s))[:6000]
domain_rules = sorted(set(domain_rules), key=domain_priority)[:5000]
domain_exceptions = sorted(set(domain_exceptions), key=lambda rule: (rule[0], rule[1]))[:3000]

(OUT / "block_hosts.txt").write_text("\n".join(hosts) + "\n", encoding="utf-8")
(OUT / "allow_hosts.txt").write_text("\n".join(allow_host_list) + "\n", encoding="utf-8")
(OUT / "third_party_hosts.txt").write_text("\n".join(third_party) + "\n", encoding="utf-8")
(OUT / "block_patterns.txt").write_text("\n".join(patterns) + "\n", encoding="utf-8")
(OUT / "third_party_patterns.txt").write_text("\n".join(third_party_pattern_list) + "\n", encoding="utf-8")
(OUT / "allow_patterns.txt").write_text("\n".join(allow_pattern_list) + "\n", encoding="utf-8")

domain_host_lines = []
for domains, host, exception in domain_host_rules:
    prefix = "!" if exception else ""
    domain_host_lines.append(prefix + ",".join(domains) + "\t" + host)
(OUT / "domain_hosts.txt").write_text("\n".join(domain_host_lines) + "\n", encoding="utf-8")

cosmetic_lines = ["*\t" + selector for selector in generic]
cosmetic_lines += ["!" + ",".join(domains) + "\t" + selector for domains, selector in domain_exceptions]
cosmetic_lines += [",".join(domains) + "\t" + selector for domains, selector in domain_rules]
(OUT / "cosmetic_rules.txt").write_text("\n".join(cosmetic_lines) + "\n", encoding="utf-8")

print("hosts", len(hosts), "allow_hosts", len(allow_host_list), "third_party_hosts", len(third_party))
print("patterns", len(patterns), "third_party_patterns", len(third_party_pattern_list),
      "allow_patterns", len(allow_pattern_list))
print("domain_hosts", len(domain_host_rules))
print("cosmetic", len(cosmetic_lines), "generic", len(generic),
      "domain_rules", len(domain_rules), "domain_exceptions", len(domain_exceptions))
for path in sorted(OUT.glob("*.txt")):
    print(path.name, path.stat().st_size)
