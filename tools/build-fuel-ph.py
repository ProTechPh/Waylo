#!/usr/bin/env python3
"""Trim the Philippine DOE weekly pump-price PDFs to the rows the app reads (SPEC 5.9).

The Department of Energy publishes one PDF per field office per week (NCR, North Luzon, South
Luzon, Visayas, Mindanao), each a table of AREA x PRODUCT (RON 100/97/95/91, Diesel, Diesel Plus,
Kerosene) with a price range and a common price. There is no machine-readable feed: the region
pages on doe.gov.ph are HTML and the newest PDF is found there.

Output is CSV (gzipped) with the header `area,region,product,low,high,common,weekStart,weekEnd`,
prices in pesos per liter to two decimals, `weekStart`/`weekEnd` as YYYY-MM-DD, and a manifest
beside it. A region whose newest PDF is a scan (no text layer) is skipped with a warning and its
rows are left out; the app then shows nothing for that region rather than something stale.

Usage:
  python3 tools/build-fuel-ph.py --out DIR                 # fetch the pages, then the newest PDFs
  python3 tools/build-fuel-ph.py --out DIR --local DIR     # parse DIR/<region>.pdf, no network
"""

import argparse
import datetime as dt
import gzip
import hashlib
import io
import json
import os
import re
import sys
import urllib.request

REGIONS = [
    ("ncr", "https://doe.gov.ph/data-and-prices/liquid-fuels/retail-pump-prices/ncr-pump-prices"),
    ("north_luzon", "https://doe.gov.ph/data-and-prices/liquid-fuels/retail-pump-prices/north-luzon-pump-prices"),
    ("south_luzon", "https://doe.gov.ph/data-and-prices/liquid-fuels/retail-pump-prices/south-luzon-pump-prices"),
    ("visayas", "https://doe.gov.ph/data-and-prices/liquid-fuels/retail-pump-prices/visayas-pump-prices"),
    ("mindanao", "https://doe.gov.ph/data-and-prices/liquid-fuels/retail-pump-prices/mindanao-pump-prices"),
]

UA = "WayloMaps-bake (+https://github.com/waylo)"
MONTHS = {m: i for i, m in enumerate(
    ["january", "february", "march", "april", "may", "june", "july", "august",
     "september", "october", "november", "december"], 1)}
MONTH_ABBR = {m[:3]: i for m, i in MONTHS.items()}


NUMBER = re.compile(r"^\d{1,3}(?:\.\d{1,2})?$")


def fix_area(s):
    """The PDFs draw ñ with a broken glyph, which comes out as U+FFFD: "Para\uFFFDaqué" is
    Parañaque. The neighbouring letters give the case. short: every dropped glyph in these
    sheets is ñ, revisit if another one shows up."""
    def repl(m):
        prev = s[m.start() - 1] if m.start() else ""
        return "Ñ" if prev.isupper() else "ñ"
    s = " ".join(re.sub("\ufffd", repl, s).replace(",", " ").split())
    # A footer or a header ("DATE MONITORING: NOVEMBER 10-12, 2026") rides the column the area
    # label uses. A place name has no digits, no column title and fits a row.
    if len(s) > 40 or re.search(r"\d", s) or re.search(r"MONITORING|DATE|SOURCE|NOTE|PRICE|PRODUCT|RANGE|(^| )N/?A( |$)", s, re.I):
        return ""
    return s
# A price outside this is a placeholder or a misread, not a pump price.
MIN_P, MAX_P = 30.0, 250.0
# A PDF page with fewer characters than this has no text layer (a scan).
TEXT_PAGE_CHARS = 40
# A region whose newest readable week is older than this is left out rather than published as
# current: North Luzon's field office went to scanned PDFs in November 2026, so its newest
# readable week trails the others by weeks.
MAX_REGION_AGE_D = 42


def fetch(url, timeout=120):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def month(name):
    n = name.lower().strip(".")
    return MONTHS.get(n) or MONTH_ABBR.get(n[:3])


def dates_in(text):
    """Every (month, day, year) the text names, from a filename or a link's own words."""
    out = []
    years = [int(y.group(1)) for y in re.finditer(r"(20\d{2})", text)]
    year = max(years) if years else None
    if year is None:
        return []
    for m in re.finditer(r"([A-Za-z]{3,9})\.?\s*(\d{1,2})", text):
        mo = month(m.group(1))
        if mo:
            out.append((mo, int(m.group(2)), year))
    for m in re.finditer(r"(\d{1,2})\s*[-–]\s*(\d{1,2})\s+([A-Za-z]{3,9})", text):
        mo = month(m.group(3))
        if mo:
            out.append((mo, int(m.group(1)), year))
            out.append((mo, int(m.group(2)), year))
    for m in re.finditer(r"(?<!\d)(\d{2})(\d{2})(20\d{2})(?!\d)", text):  # 10062026
        out.append((int(m.group(1)), int(m.group(2)), int(m.group(3))))
    for m in re.finditer(r"(?<!\d)(\d{6})(?!\d)", text):  # 111726
        s = m.group(1)
        out.append((int(s[0:2]), int(s[2:4]), 2000 + int(s[4:6])))
    return [(mo, d, y) for mo, d, y in out if 1 <= mo <= 12 and 1 <= d <= 31]


def date_key(text):
    """The newest date in the text, for putting the likeliest candidates first."""
    best = dt.date.min
    for mo, day, year in dates_in(text):
        try:
            best = max(best, dt.date(year, mo, day))
        except ValueError:
            pass
    return best


WEEK_PATS = [
    # "for the week of November 10-16, 2026", "For the week: October 13-19, 2026"
    re.compile(r"week\s*(?:of|:)?\s*([A-Za-z]{3,9})\.?\s*(\d{1,2})\s*[-–]\s*(?:([A-Za-z]{3,9})\.?\s*)?(\d{1,2}),?\s*(20\d{2})", re.I),
    # "(FOR THE PERIOD OF NOVEMBER 03-09, 2026)"
    re.compile(r"period\s+of\s+([A-Za-z]{3,9})\.?\s*(\d{1,2})\s*[-–]\s*(?:([A-Za-z]{3,9})\.?\s*)?(\d{1,2}),?\s*(20\d{2})", re.I),
]


def week_from_text(text):
    """(start, end) from the title line, or None. NCR, South Luzon and Mindanao carry dates;
    the Visayas sheet only says "Tuesday - Monday", and its filename supplies them."""
    for pat in WEEK_PATS:
        m = pat.search(text)
        if m:
            y = int(m.group(5))
            m1, d1 = month(m.group(1)), int(m.group(2))
            m2 = month(m.group(3)) if m.group(3) else m1
            d2 = int(m.group(4))
            if not (m1 and m2):
                continue
            try:
                end = dt.date(y, m2, d2)
                start = dt.date(end.year if m1 <= m2 else end.year - 1, m1, d1)
            except ValueError:
                continue
            if start <= end and (end - start).days <= 7:
                return start, end
    return None


def week_from_name(name):
    """(start, end) from the dates in a filename, or None: the two closest days in it, else the
    newest day and the six before it."""
    days = sorted({dt.date(y, mo, d) for mo, d, y in dates_in(name)})
    if not days:
        return None
    pairs = [(a, b) for a in days for b in days if 0 <= (b - a).days <= 7 and a < b]
    if pairs:
        start, end = max(pairs, key=lambda p: p[1])
        return start, end
    return days[-1] - dt.timedelta(days=6), days[-1]


def parse_pdf(path, name_hint=""):
    """(week, rows) from one DOE PDF. rows: (area, product, low, high, common)."""
    import pdfplumber

    rows = []
    week = None
    with pdfplumber.open(path) as pdf:
        text_pages = 0
        for pg in pdf.pages:
            text = pg.extract_text() or ""
            if len(text) >= TEXT_PAGE_CHARS:
                text_pages += 1
            if week is None:
                week = week_from_text(text)
            for line in parse_page(pg):
                rows.append(line)
        if text_pages < max(1, len(pdf.pages) // 2):
            return None, []  # mostly images: no text layer, nothing to read
    if week is None:
        week = week_from_name(name_hint or os.path.basename(path))
    return week, rows



def parse_page(pg):
    words = pg.extract_words()
    # The field offices put the province in a column left of the city (North Luzon:
    # "ABRA BANGUED CITY"). The address a phone sees names the city, so the province is cut
    # by the header's own columns; NCR has no such column and keeps everything.
    prov = next((w for w in words if w["text"].upper().startswith("PROVINCE")), None)
    city = next((w for w in words if w["text"].upper().startswith("CITY")), None)
    split_x = (prov["x1"] + city["x0"]) / 2 if prov and city else None
    # Rows sit 9.1 apart; a cell's parts (an area name in a smaller face, a two-line "DIESEL
    # PLUS") are up to a unit apart. Grouping within 4 keeps the row together and the next apart.
    groups = []
    for w in sorted(words, key=lambda w: w["top"]):
        if groups and w["top"] - groups[-1][0] < 4.0:
            groups[-1][1].append(w)
        else:
            groups.append([w["top"], [w]])

    entries = []  # [key, tokens, product, area]
    for key, group in [(g[0], g[1]) for g in groups]:
        ws = sorted(group, key=lambda w: w["x0"])
        product, area = None, []
        for i, w in enumerate(ws):
            word = w["text"].upper()
            if word not in ("RON", "DIESEL", "KEROSENE"):
                continue
            nxt = ws[i + 1]["text"].upper() if i + 1 < len(ws) else ""
            if word == "RON" and re.fullmatch(r"100|97|95|91", nxt):
                product, skip = f"RON {nxt}", 2
            elif word == "DIESEL" and nxt == "PLUS":
                product, skip = "Diesel Plus", 2
            elif word == "DIESEL":
                product, skip = "Diesel", 1
            elif word == "KEROSENE":
                product, skip = "Kerosene", 1
            else:
                continue
            area = [t for t in ws[:i]
                    if re.search(r"[A-Za-z]", t["text"]) and (split_x is None or t["x0"] >= split_x)]
            body = ws[i + skip:]
            entries.append([key, body, product, fix_area(" ".join(t["text"] for t in area))])
            break
        else:
            # A cell too tall for one line leaves its numbers on the next band, and an area name
            # set in a smaller face lands on its own band beside the row it belongs to.
            nums = [w for w in ws if NUMBER.match(w["text"])]
            if nums and entries and entries[-1][2] and not entries[-1][1] and key - entries[-1][0] <= 1:
                entries[-1][1] = ws
            elif not nums and re.search(r"[A-Za-z]", " ".join(w["text"] for w in ws)) and entries:
                text = " ".join(w["text"] for w in ws).strip()
                if len(text) <= 48 and not re.search(r"N/?A|PRICE|PRODUCT|RANGE", text, re.I):
                    entries[-1][3] = entries[-1][3] or fix_area(text)
            continue

    # An area's label is centred in its block, so it can sit beside any of the block's rows.
    # A block starts at each RON 100 row; a block with no label yields no rows rather than the
    # previous area's price.
    blocks = []
    for e in entries:
        if e[2] == "RON 100" or not blocks:
            blocks.append([])
        blocks[-1].append(e)

    for block in blocks:
        area = next((e[3] for e in block if e[3]), None)
        if not area:
            continue
        for _, body, product, _ in block:
            # The range columns sit after the brands, as "low - high [common]". The numbers either
            # side of the dash are the range, which holds when a brand column is blank.
            nums = [(w["x0"], w["text"]) for w in body if NUMBER.match(w["text"])]
            dash = [w["x0"] for w in body if w["text"] in ("-", "–", "—")]
            if not dash:
                continue
            d = dash[-1]
            before = [float(t) for x, t in nums if x < d]
            after = [float(t) for x, t in nums if x > d]
            if not before or not after:
                continue
            lo, hi = before[-1], after[0]
            co = after[1] if len(after) > 1 else None
            if co is not None and not (lo <= co <= hi):
                co = None  # the sheet's own "common" outside its range is a typo, not a price
            if not (MIN_P <= lo <= hi <= MAX_P):
                continue
            yield area, product, lo, hi, co


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--local", help="parse <region>.pdf from this folder instead of the network")
    args = ap.parse_args()

    import os
    os.makedirs(args.out, exist_ok=True)

    out_rows = []
    regions = {}
    week = None
    for region, page in REGIONS:
        if args.local:
            w, rows = parse_pdf(os.path.join(args.local, region + ".pdf"), region)
            path = args.local
        else:
            w, rows, path = None, [], ""
            try:
                html = fetch(page).decode("utf-8", "replace")
            except Exception as e:  # noqa: BLE001 - a region that fails must not stop the rest
                print(f"::warning::{region}: page fetch failed: {e}", file=sys.stderr)
                continue
            # A region page carries several PDFs per week (South Luzon files Region IV-A and IV-B
            # separately, North Luzon one report per field office) and years of older ones, and
            # the filenames date themselves inconsistently ("NCR ... 27 October to 2 November
            # 2026", "NEW VFO PRICE MONITORING 111726"). So: score each link's own words plus its
            # filename, read the candidates from the newest three weeks, and merge every file that
            # shares the newest week that has a text layer. A field office that has gone to scans
            # yields nothing, and the region keeps the newest week that reads rather than nothing.
            links = list({(u, a) for u, a in
                          re.findall(r'href="(https://[^"]+\.pdf[^"]*)"[^>]*>([^<]*)</a>', html)})
            links.sort(key=lambda ua: date_key(ua[1] + " " + urllib.request.unquote(ua[0])), reverse=True)
            if not links:
                print(f"::warning::{region}: no PDF links on the page", file=sys.stderr)
                continue
            newest = date_key(links[0][1] + " " + urllib.request.unquote(links[0][0]))
            links = [ua for ua in links
                     if date_key(ua[1] + " " + urllib.request.unquote(ua[0])) >= newest - dt.timedelta(days=56)][:8]
            parsed = []  # (week, rows) of every readable file
            for url, anchor in links:
                name = urllib.request.unquote(url.split("?")[0]).rsplit("/", 1)[-1]
                try:
                    blob = fetch(url)
                except Exception as e:  # noqa: BLE001
                    print(f"::warning::{region}: {name}: {e}", file=sys.stderr)
                    continue
                path = os.path.join(args.out, f"{region}.pdf")
                with open(path, "wb") as f:
                    f.write(blob)
                cw, crows = parse_pdf(path, anchor + " " + urllib.request.unquote(url))
                if cw and crows:
                    parsed.append((cw, crows))
                    print(f"{region}: read {name} ({len(crows)} rows, week {cw[0]}..{cw[1]})")
                else:
                    print(f"{region}: no text layer in {name}")
            if not parsed:
                print(f"::warning::{region}: no readable PDF on the page", file=sys.stderr)
                continue
            # Files of one week disagree on its first day ("Oct 13-20" beside "Oct 14-20"), so the
            # week is its END and the files that share that end are one report set.
            end = max(cw[1] for cw, _ in parsed)
            age_d = (dt.datetime.now(dt.timezone.utc).date() - end).days
            if age_d > MAX_REGION_AGE_D:
                print(f"::warning::{region}: newest readable week ends {end}, {age_d} days old; "
                      f"left out rather than shown as current", file=sys.stderr)
                continue
            same = [(cw, crows) for cw, crows in parsed if cw[1] == end]
            w = (min(cw[0] for cw, _ in same), end)
            rows = [r for _, crows in same for r in crows]
        if not rows:
            print(f"::warning::{region}: no readable PDF in {path or 'the page'}", file=sys.stderr)
            continue
        if w is None:
            print(f"::warning::{region}: no week in the PDF, rows dropped", file=sys.stderr)
            continue
        week = max(week, w[1]) if week else w[1]
        regions[region] = {"week": [str(w[0]), str(w[1])], "areas": len({r[0] for r in rows}), "rows": len(rows)}
        out_rows += [(a, region, p, lo, hi, co, w[0], w[1]) for a, p, lo, hi, co in rows]
        print(f"{region}: {regions[region]['areas']} areas, {regions[region]['rows']} rows, week {w[0]}..{w[1]}")

    if not out_rows:
        print("::error::no rows parsed; the published file stays", file=sys.stderr)
        return 1

    uniq = {}
    for r in out_rows:
        uniq[(r[0], r[1], r[2])] = r
    rows = sorted(uniq.values(), key=lambda r: (r[1], r[0], r[2]))

    buf = io.StringIO()
    buf.write("area,region,product,low,high,common,weekStart,weekEnd\n")
    for a, region, p, lo, hi, co, ws, we in rows:
        buf.write(f"{a},{region},{p},{lo:.2f},{hi:.2f},{'' if co is None else f'{co:.2f}'},{ws},{we}\n")
    gz = gzip.compress(buf.getvalue().encode("utf-8"), mtime=0)
    data_name = "fuel-ph.csv.gz"
    with open(os.path.join(args.out, data_name), "wb") as f:
        f.write(gz)

    manifest = {
        "version": 1,
        "generated": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "source": "doe.gov.ph",
        "latestWeekEnd": str(week),
        "areas": len({r[0] for r in rows}),
        "rows": len(rows),
        "regions": regions,
        "file": data_name,
        "fileSha256": hashlib.sha256(gz).hexdigest(),
        "fileBytes": len(gz),
    }
    with open(os.path.join(args.out, "fuel-ph-manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=1, sort_keys=True)
    print(f"{len(rows)} rows, {manifest['areas']} areas, {len(gz)} bytes, latest week end {week}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
