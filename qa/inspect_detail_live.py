"""Inspect the site's current detail-page structure without saving page content."""

import collections
import json
import sys
from pathlib import Path

import requests
from bs4 import BeautifulSoup


check = "--check" in sys.argv
video_id = next((arg for arg in sys.argv[1:] if arg != "--check"), "117")
response = requests.get(
    f"https://huangguoai.com/detail/{video_id}/",
    headers={
        "User-Agent": "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36",
        "Referer": "https://huangguoai.com/",
    },
    timeout=20,
)
response.raise_for_status()
response.encoding = "utf-8"
page = BeautifulSoup(response.text, "html.parser")
print("final_url:", response.url)
for selector in (
    ".hg-web-detail",
    ".hg-web-detail__ep-grid a",
    ".hg-web-play",
    "#videoInitialData",
):
    print(selector, len(page.select(selector)))
print("headings:", [(tag.get_text(" ", strip=True)[:55], tag.get("class")) for tag in page.select("h1")[:3]])
classes = collections.Counter(
    " ".join(tag.get("class", []))
    for tag in page.find_all(class_=True)
    if any("play" in name or "drama" in name or "episode" in name for name in tag.get("class", []))
)
print("classes:", classes.most_common(25))
data = page.select_one("#videoInitialData")
if data:
    try:
        parsed = json.loads(data.get_text())
        print("video_data_keys:", sorted(parsed.keys()))
        for key in ("title", "description", "coverSrc", "posterSrc", "ep", "epPlaySrcs", "tags", "tagLinks"):
            value = parsed.get(key)
            print("video_data_field:", key, type(value).__name__, len(value) if hasattr(value, "__len__") else value)
        print("tag_samples:", parsed.get("tags", [])[:2], parsed.get("tagLinks", [])[:2])
    except ValueError:
        print("video_data: invalid JSON")
links = page.select(f'a[href^="/video/{video_id}/"]')
print("video_links:", [(link.get("href"), link.get_text(" ", strip=True)[:12]) for link in links[:12]])
print("play_classes:", [name for name, _ in classes.most_common() if name.startswith("hg-play__") and "comment" not in name][:70])
print("detail_classes:", [name for name, _ in classes.most_common() if any(part in name for part in ("score", "rating", "ep-item", "poster", "desc", "tag"))][:30])
heading = page.select_one("h1")
if heading:
    print("heading_parents:", [(node.name, node.get("class")) for node in list(heading.parents)[:5]])

if check:
    source = (Path(__file__).parents[1] / "app/src/main/java/com/huangguo/mobile/SiteRepository.java").read_text(encoding="utf-8")
    detail_method = source.split("JSONObject detail(String id)", 1)[1].split("JSONObject episode(String id", 1)[0]
    expected = (
        'page("/video/" + id + "/")' in detail_method
        and 'document.selectFirst("#videoInitialData")' in detail_method
        and data is not None
        and len(links) > 1
    )
    print("detail_contract:", "PASS" if expected else "FAIL")
    if not expected:
        raise SystemExit(1)
