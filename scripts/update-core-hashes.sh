#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY'
import concurrent.futures, hashlib, io, json, pathlib, urllib.request, zipfile
root = pathlib.Path('rust/core-manifest.json')
names = ['swanstation', 'mednafen_psx', 'pcsx_rearmed', 'ppsspp', 'play']
def fetch(pair):
    abi, name = pair
    filename = name + '_libretro_android.so'
    url = f'https://buildbot.libretro.com/nightly/android/latest/{abi}/{filename}.zip'
    with urllib.request.urlopen(url, timeout=120) as response:
        archive = response.read(100_000_001)
    if len(archive) > 100_000_000:
        raise ValueError('Archive too large')
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        if z.getinfo(filename).file_size > 200_000_000:
            raise ValueError('Core too large')
        data = z.read(filename)
    print(f'{abi}/{name}: {hashlib.sha256(data).hexdigest()}', flush=True)
    return {'abi': abi, 'name': name, 'url': url, 'sha256': hashlib.sha256(data).hexdigest()}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    entries = list(pool.map(fetch, [(a,n) for a in ['arm64-v8a','x86_64'] for n in names]))
staging = root.with_suffix('.json.tmp')
staging.write_text(json.dumps(entries, indent=2) + '\n')
staging.replace(root)
PY
