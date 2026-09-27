"""Check the site's current cover bytes against Android's header normalization rule."""

from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from io import BytesIO
from time import sleep

import requests
from bs4 import BeautifulSoup
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from PIL import Image


PAGES = ("/recommend", "/recommend/2/", "/recommend/3/",
         "/newest", "/newest/2/", "/newest/3/", "/ai-duanju/2/")
KEY = b"f5d965df75336270"
IV = b"97b60374abc2fbe1"


def repair_header(data: bytearray) -> bool:
    if len(data) < 12:
        return False
    png = data[:6] == bytes.fromhex("89504e470d0a") and data[6:8] == b"\x14\x0a"
    jpeg = data[:3] == b"\xff\xd8\xff"
    jfif = jpeg and data[3] == 0xE0 and data[6:11] == b"DFIF\0"
    dqt = jpeg and data[3] == 0xDB and data[6] & 0xFC == 0x0C
    exif = jpeg and data[3] == 0xE1 and data[6:10] == b"Kxif"
    icc = jpeg and data[3] == 0xE2 and data[6:10] == b"GCC_"
    if not (png or jfif or dqt or exif or icc):
        return False
    data[6] ^= 0x0E
    return True


def decodes(data: bytes) -> bool:
    try:
        with Image.open(BytesIO(data)) as image:
            image.load()
        return True
    except Exception:
        return False


def get(url):
    for attempt in range(3):
        try:
            response = requests.get(url, timeout=20)
            response.raise_for_status()
            return response
        except requests.RequestException:
            if attempt == 2:
                raise
            sleep(.5 * (attempt + 1))


def inspect(item):
    page, item_id, url = item
    try:
        response = get(url)
    except requests.RequestException:
        return page, item_id, None, False, None
    decryptor = Cipher(algorithms.AES(KEY), modes.CBC(IV)).decryptor()
    data = bytearray(decryptor.update(response.content) + decryptor.finalize())
    before = decodes(data)
    repaired = repair_header(data)
    return page, item_id, before, repaired, decodes(data)


def main():
    items = []
    for page in PAGES:
        response = get("https://huangguoai.com" + page)
        document = BeautifulSoup(response.text, "html.parser")
        for card in document.select(".hg-drama-card[data-track-id]")[:24]:
            image = card.select_one(".hg-drama-card__cover img")
            if image is not None:
                url = image.get("data-src") or image.get("src")
                if url:
                    items.append((page, card.get("data-track-id"), url))
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(inspect, items))
    counts = Counter((before, repaired, after) for _, _, before, repaired, after in results)
    print(f"Checked {len(results)} covers: {dict(counts)}")
    failed = [(page, item_id) for page, item_id, _, _, after in results if after is False]
    unavailable = [(page, item_id) for page, item_id, _, _, after in results if after is None]
    if failed:
        raise SystemExit(f"Still invalid after repair: {failed[:20]}")
    if unavailable:
        raise SystemExit(f"Network unavailable for: {unavailable[:20]}")


if __name__ == "__main__":
    main()
