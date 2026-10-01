"""Measure a real release build's card open/back without changing device settings.

Requires the app's home page in the foreground. Captures only rendering metrics;
the report contains no titles, account data, or page screenshots.
"""
import argparse
import json
import re
import statistics
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--output', required=True)
parser.add_argument('--require-120', action='store_true')
args = parser.parse_args()


def adb(*command):
    return subprocess.check_output(['adb', *command], timeout=25).decode('utf-8', errors='replace')


def foreground():
    return 'com.huangguo.mobile/.MainActivity' in next(
        (line for line in adb('shell', 'dumpsys', 'activity', 'activities').splitlines() if 'topResumedActivity=' in line), '')


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/hg-refresh-window.xml')
    xml = adb('shell', 'cat', '/sdcard/hg-refresh-window.xml')
    return list(ET.fromstring(xml[xml.index('<?xml'):]).iter('node'))


def bounds(node):
    return list(map(int, re.findall(r'\d+', node.get('bounds', ''))))


def reset():
    text = adb('shell', 'dumpsys', 'gfxinfo', 'com.huangguo.mobile', 'reset')
    match = re.search(r'Stats since:\s*(\d+)ns', text)
    return int(match[1]) if match else int(float(adb('shell', 'cat', '/proc/uptime').split()[0]) * 1e9)


def metrics(since):
    raw = adb('shell', 'dumpsys', 'gfxinfo', 'com.huangguo.mobile', 'framestats')
    columns = None
    frames = []
    for line in raw.splitlines():
        if line.startswith('Flags,'):
            columns = line.rstrip(',').split(',')
        elif columns and re.match(r'^\d+,', line):
            row = dict(zip(columns, map(int, line.rstrip(',').split(','))))
            if row['Flags'] == 0 and row['IntendedVsync'] >= since and row['FrameCompleted'] < 2**62:
                frames.append(row)
    times = sorted(set(row['DisplayPresentTime'] for row in frames if 0 < row['DisplayPresentTime'] < 2**62))
    # Separate animations have idle gaps; these are not dropped animation frames.
    intervals = [(b-a)/1e6 for a, b in zip(times, times[1:]) if 0 < b-a < 40e6]
    cadence = statistics.median(intervals) if intervals else None
    costs = sorted((row['FrameCompleted']-row['IntendedVsync'])/1e6 for row in frames)
    vsyncs = sorted(row['FrameInterval']/1e6 for row in frames if row.get('FrameInterval', 0) > 0)
    display = adb('shell', 'dumpsys', 'display')
    rate = re.search(r'renderFrameRate ([\d.]+)', display)
    return {
        'frames': len(frames),
        'systemRenderRateHz': float(rate[1]) if rate else None,
        'medianVsyncIntervalMs': round(statistics.median(vsyncs), 3) if vsyncs else None,
        'medianPresentedIntervalMs': round(cadence, 3) if cadence else None,
        'presentationIntervalSamples': len(intervals),
        'presentedCadenceFps': round(1000/cadence, 1) if cadence else None,
        'p95FrameCompletionMs': round(costs[int((len(costs)-1)*.95)], 3) if costs else None,
        'deadlineMissed': sum(row['FrameCompleted'] > row['FrameDeadline'] for row in frames),
        'presentedIntervalsOver12_5ms': sum(interval > 12.5 for interval in intervals),
    }


if not foreground():
    raise SystemExit('App MainActivity is not foreground; no touches sent.')
cards = []
# WebView's accessibility tree is populated asynchronously after launch.
for _ in range(3):
    ui = nodes()
    if any(node.get('text', '').strip() == '返回' for node in ui):
        raise SystemExit('Start on the home page; no touches sent.')
    cards = [node for node in ui if node.get('content-desc', '').startswith('查看')
             and len(bounds(node)) == 4 and bounds(node)[3]-bounds(node)[1] > 250]
    if cards:
        break
    time.sleep(.3)
if not cards:
    raise SystemExit('No visible work card found; no touches sent.')
x1, y1, x2, y2 = bounds(cards[0])
x, y = (x1+x2)//2, y1+100
results = []
for index in range(2):
    if not foreground():
        raise SystemExit('Foreground changed; stopped.')
    since = reset()
    adb('shell', 'input', 'tap', str(x), str(y))
    time.sleep(1.0)
    results.append({'animation': 'open', 'cycle': index+1, **metrics(since)})
    if not foreground():
        raise SystemExit('Foreground changed; stopped.')
    since = reset()
    adb('shell', 'input', 'swipe', '1', '1250', '650', '1250', '450')
    time.sleep(.8)
    results.append({'animation': 'system-back', 'cycle': index+1, **metrics(since)})
    time.sleep(.2)
report = {'version': re.search(r'versionName=([^\s]+)', adb('shell','dumpsys','package','com.huangguo.mobile'))[1],
          'measurement': 'HWUI frame statistics and unique display presentation timestamps', 'animations': results}
path = Path(args.output)
path.parent.mkdir(parents=True, exist_ok=True)
path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
if args.require_120 and any(row['frames'] < 30 or not row['presentedCadenceFps'] or row['presentedCadenceFps'] < 110 for row in results):
    raise SystemExit('FAIL: animation had too few frames or presentation cadence below 110 fps')
