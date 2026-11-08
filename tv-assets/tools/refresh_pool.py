"""Builds the My Favourites pool: the latest movies and series streaming in India on the apps
installed on the TV, with an IMDb rating above the bar, each with a character cut-out and a
darkened scene. Then pushes the pool to the TV.

  python3 -I refresh_pool.py              refresh and push
  python3 -I refresh_pool.py --no-push    refresh only

Sources: TMDB (availability, via JustWatch; images) with the key in tv-assets/tmdb.key, and
IMDb's official daily ratings file (datasets.imdbws.com). Cut-outs use macOS Vision through
tools/cutout. Titles whose images give no clean cut-out of a person are skipped.
"""
import datetime
import gzip
import html
import json
import os
import random
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # our own taste.py, even under python -I
from taste import INDIAN as INDIAN_LANGS, Taste  # noqa: E402
from find_tv import find_tv  # noqa: E402
from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageOps

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # tv-assets
POOL = os.path.join(ROOT, 'tv', 'pool')
CACHE = os.path.join(ROOT, 'cache')
CUTOUT_TOOL = os.path.join(ROOT, 'tools', 'cutout')
def _tv_serial():
    """The TV's adb address: tv-serial.txt beside the tools (deploy.sh keeps it current, as the
    TV's address changes now and then), or ANDROID_SERIAL."""
    try:
        with open(os.path.join(ROOT, 'tv-serial.txt')) as f:
            return f.read().strip() or None
    except OSError:
        return None


SERIAL = os.environ.get('ANDROID_SERIAL') or _tv_serial() or '192.168.1.20:5555'
DEST = '/sdcard/Android/data/tv.dumbtv.home/files/cinema/pool'

PER_KIND = 30
MIN_RATING = 7.5
MIN_VOTES = 1000
JUDGE_VERSION = 3
REGION = 'IN'

# TV package -> names TMDB uses for that service in India (matched case-insensitively).
APPS = {
    'com.netflix.ninja': ['netflix'],
    'com.amazon.firebat': ['amazon prime video'],
    'in.startv.hotstar': ['jiohotstar', 'hotstar', 'disney plus hotstar'],
    'com.apple.atve.amazon.appletv': ['apple tv+', 'apple tv plus', 'apple tv'],
    'com.onemainstream.sonyliv.android': ['sony liv', 'sonyliv'],
    'com.zee5.amazon': ['zee5'],
    'com.discoveryplus.tv.fire': ['discovery+', 'discovery plus'],
}


def log(*a):
    print(*a, flush=True)


# ---- sources -----------------------------------------------------------------------------

def tmdb(path, **params):
    key = open(os.path.join(ROOT, 'tmdb.key')).read().strip()
    # v4 read tokens are long JWTs and go in a header; v3 keys go in the query.
    headers = {'Accept': 'application/json'}
    if len(key) > 40:
        headers['Authorization'] = 'Bearer ' + key
    else:
        params['api_key'] = key
    url = 'https://api.themoviedb.org/3' + path + '?' + urllib.parse.urlencode(params)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(2 + attempt * 2)
                continue
            raise
        except (urllib.error.URLError, TimeoutError):
            time.sleep(2 + attempt * 2)
    raise RuntimeError('TMDB unreachable: ' + path)


def imdb_ratings():
    """{tconst: (rating, votes)} from IMDb's daily file, downloaded at most once a day."""
    os.makedirs(CACHE, exist_ok=True)
    f = os.path.join(CACHE, 'title.ratings.tsv.gz')
    if not os.path.exists(f) or time.time() - os.path.getmtime(f) > 20 * 3600:
        log('downloading IMDb ratings')
        urllib.request.urlretrieve('https://datasets.imdbws.com/title.ratings.tsv.gz', f + '.part')
        os.replace(f + '.part', f)
    out = {}
    with gzip.open(f, 'rt', encoding='utf-8') as r:
        next(r)
        for line in r:
            t, rating, votes = line.rstrip('\n').split('\t')
            out[t] = (float(rating), int(votes))
    return out


def adb(*args, check=True):
    """stdout and stderr together: adb push prints its summary on stderr when not on a terminal."""
    r = subprocess.run(['adb', '-s', SERIAL, *args], capture_output=True, text=True, check=check)
    return r.stdout + r.stderr


def locate():
    """Points SERIAL at the TV, found again if its address changed; False when it's off."""
    global SERIAL
    addr = find_tv(log)
    if addr:
        SERIAL = addr
    return addr is not None


def installed_apps():
    try:
        if not locate():
            raise OSError('TV not found')
        pkgs = {l.split(':', 1)[1].strip() for l in adb('shell', 'pm list packages').splitlines() if ':' in l}
        found = [p for p in APPS if p in pkgs]
        if found:
            return found
    except (subprocess.SubprocessError, OSError):
        pass
    log('TV not reachable; using every known app')
    return list(APPS)


def provider_ids(apps):
    """TMDB provider id -> TV package, for the installed apps."""
    out = {}
    for kind in ('movie', 'tv'):
        for p in tmdb(f'/watch/providers/{kind}', watch_region=REGION)['results']:
            name = p['provider_name'].lower()
            for pkg in apps:
                if any(name == n for n in APPS[pkg]):
                    out[p['provider_id']] = pkg
    return out


def download(url, path):
    if os.path.exists(path) and os.path.getsize(path) > 0:
        return path
    for attempt in range(3):
        try:
            urllib.request.urlretrieve(url, path + '.part')
            os.replace(path + '.part', path)
            return path
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            time.sleep(2 + attempt * 3)
    return None


# ---- picking titles ------------------------------------------------------------------------

# ---- picking titles by taste --------------------------------------------------------------

TASTE_SHARE = 0.8  # of each kind's 30: picked by taste; the rest are simply the newest releases
SEEN_MAX = 6  # watched titles allowed per kind (rewatches welcome, but new things come first)
FRESH_CANDIDATES = 60  # newest available, well-rated titles considered per kind
SEED_CANDIDATES = 80  # "more like what you loved" titles considered per kind


def details(kind, tid, max_age=20 * 3600, images=False):
    """TMDB details, cached (availability changes daily). The image lists are big (often 100+
    entries), so they're only fetched for titles that are actually picked."""
    folder = os.path.join(CACHE, 'details')
    os.makedirs(folder, exist_ok=True)
    f = os.path.join(folder, f'{kind}-{tid}{"-img" if images else ""}.json')
    if os.path.exists(f) and time.time() - os.path.getmtime(f) < max_age:
        return json.load(open(f))
    plain = os.path.join(folder, f'{kind}-{tid}.json')
    if images and os.path.exists(plain) and time.time() - os.path.getmtime(plain) < max_age:
        d = json.load(open(plain))
        if 'images' in d:  # an earlier full download already has them
            return d
    extra = 'external_ids,watch/providers,keywords' + (',images' if images else '')
    d = tmdb(f'/{kind}/{tid}', append_to_response=extra, **({'include_image_language': 'null,en'} if images else {}))
    json.dump(d, open(f, 'w'))
    return d


def imdb_of(kind, tid):
    """A title's IMDb id from a small lookup, cached for good: checking the rating before the
    full details saves most of the downloads."""
    f = os.path.join(CACHE, 'imdb-ids-all.json')
    cache = json.load(open(f)) if os.path.exists(f) else {}
    key = f'{kind}-{tid}'
    if key not in cache:
        cache[key] = tmdb(f'/{kind}/{tid}/external_ids').get('imdb_id')
        json.dump(cache, open(f, 'w'))
    return cache[key]


def rated_enough(kind, tid, ratings, min_rating=MIN_RATING):
    r = ratings.get(imdb_of(kind, tid))
    return bool(r) and r[0] >= min_rating and r[1] >= MIN_VOTES


def playable(d, providers, ratings, min_rating=MIN_RATING):
    """(IMDb rating, votes, app) when the title is on an installed app in India and rated well enough."""
    imdb_id = d.get('external_ids', {}).get('imdb_id') or d.get('imdb_id')
    rating = ratings.get(imdb_id)
    if not rating or rating[0] < min_rating or rating[1] < MIN_VOTES:
        return None
    offers = d.get('watch/providers', {}).get('results', {}).get(REGION, {}).get('flatrate', [])
    pkg = next((providers[o['provider_id']] for o in offers if o['provider_id'] in providers), None)
    return (rating[0], rating[1], pkg) if pkg else None


def newest(kind, providers, ratings, exclude, limit):
    """The newest titles on the installed apps that pass the IMDb bar, newest first."""
    today = datetime.date.today().isoformat()
    date_key = 'primary_release_date' if kind == 'movie' else 'first_air_date'
    out = []
    for page in range(1, 40):
        res = tmdb(f'/discover/{kind}', watch_region=REGION,
                   with_watch_providers='|'.join(str(i) for i in providers),
                   with_watch_monetization_types='flatrate', sort_by=f'{date_key}.desc',
                   **{f'{date_key}.lte': today, 'vote_count.gte': 25}, page=page)
        for r in res['results']:
            key = f'{kind}-{r["id"]}'
            if key in exclude or any(k == key for k, _, _ in out):
                continue
            if not rated_enough(kind, r['id'], ratings):
                continue
            d = details(kind, r['id'])
            ok = playable(d, providers, ratings)
            if ok:
                out.append((key, d, ok))
            if len(out) >= limit:
                return out
        if page >= res.get('total_pages', 0):
            break
    return out


def freshness(d):
    date = d.get('release_date') or d.get('last_air_date') or d.get('first_air_date') or ''
    try:
        age = (datetime.date.today() - datetime.date.fromisoformat(date[:10])).days
    except ValueError:
        return 0
    return 0.5 if age <= 120 else 0.3 if age <= 365 else 0.1 if age <= 3 * 365 else 0


SHORT_GENRES = {'Science Fiction': 'Sci-Fi', 'Sci-Fi & Fantasy': 'Sci-Fi', 'Action & Adventure': 'Action',
                'War & Politics': 'War', 'TV Movie': 'TV Film', 'Documentary': 'Documentary'}


def genre_text(d):
    """Up to two of the title's genres, short names, for the line under its title on the TV."""
    names = []
    for g in d.get('genres', []):
        n = SHORT_GENRES.get(g['name'], g['name'])
        if n not in names:
            names.append(n)
    return ', '.join(names[:2])


def tags_for(d, key, taste):
    tags = set(taste.clusters(d)) if taste.ok else set()
    if d.get('original_language') in INDIAN_LANGS:
        tags.add('indian')
    if taste.ok and taste.is_tech(d):
        tags.add('tech')
    if key in taste.watchlist:
        tags.add('watchlist')
    if key in taste.watched:
        tags.add('seen')
    return tags


# ---- variety --------------------------------------------------------------------------------

SHOWN = os.path.join(CACHE, 'shown.json')
VARIETY = (0.9, 0.6, 0.35, 0.15)  # how far a title shown 1, 2, 3, 4 days ago is held back
JITTER = 0.35  # a daily shake of the taste order, so close matches take turns


def shown_before():
    """The pool's titles on each of the last few days, yesterday first."""
    try:
        hist = json.load(open(SHOWN))
    except (OSError, ValueError):
        hist = {}
    today = datetime.date.today()
    return [set(hist.get((today - datetime.timedelta(days=i)).isoformat(), [])) for i in range(1, len(VARIETY) + 1)]


def held_back(key, before):
    return sum(w for w, keys in zip(VARIETY, before) if key in keys)


def jitter(key):
    return random.Random(f'{datetime.date.today().isoformat()} {key}').uniform(-JITTER, JITTER)


def remember_shown(keys):
    try:
        hist = json.load(open(SHOWN))
    except (OSError, ValueError):
        hist = {}
    hist[datetime.date.today().isoformat()] = sorted(keys)
    for day in sorted(hist)[:-14]:
        del hist[day]
    json.dump(hist, open(SHOWN, 'w'), indent=0)


# ---- Indian classics --------------------------------------------------------------------------

# Hindi cinema's best-loved films and a few from the south: a handful join Suggestions each day
# (the ones on the installed apps) and the wallpapers (any of them), a different few each time.
INDIAN_CLASSICS = [
    ('Pyaasa', 1957), ('Mughal-e-Azam', 1960), ('Guide', 1965), ('Anand', 1971), ('Sholay', 1975),
    ('Deewaar', 1975), ('Golmaal', 1979), ('Mr. India', 1987), ('Nayakan', 1987), ('Andaz Apna Apna', 1994),
    ('Dilwale Dulhania Le Jayenge', 1995), ('Hera Pheri', 2000), ('Lagaan', 2001), ('Dil Chahta Hai', 2001),
    ('Munna Bhai M.B.B.S.', 2003), ('Kal Ho Naa Ho', 2003), ('Anbe Sivam', 2003), ('Swades', 2004),
    ('Rang De Basanti', 2006), ('Chak De! India', 2007), ('Taare Zameen Par', 2007), ('Jab We Met', 2007),
    ('Rock On!!', 2008), ('3 Idiots', 2009), ('Wake Up Sid', 2009), ('Udaan', 2010),
    ('Zindagi Na Milegi Dobara', 2011), ('Gangs of Wasseypur', 2012), ('Barfi!', 2012), ('Kahaani', 2012),
    ('The Lunchbox', 2013), ('Yeh Jawaani Hai Deewani', 2013), ('Drishyam', 2013), ('Queen', 2014),
    ('Haider', 2014), ('Piku', 2015), ('Masaan', 2015), ('Premam', 2015), ('Dangal', 2016),
    ('Andhadhun', 2018), ('Tumbbad', 2018), ('Gully Boy', 2019), ('Kumbalangi Nights', 2019),
]
CLASSIC_SLOTS = 6  # of each refresh's 30 films
CLASSIC_FLOOR = 7.0  # IMDb: some of the best-loved sit just under the usual 7.5 (YJHD, 7.3)


def classic_ids():
    """TMDB ids of INDIAN_CLASSICS, looked up once and remembered."""
    path = os.path.join(CACHE, 'classics.json')
    try:
        known = json.load(open(path))
    except (OSError, ValueError):
        known = {}
    for title, year in INDIAN_CLASSICS:
        name = f'{title}|{year}'
        if name not in known:
            r = tmdb('/search/movie', query=title, year=year).get('results', [])
            known[name] = r[0]['id'] if r else 0
    json.dump(known, open(path, 'w'), indent=0)
    return [known[f'{t}|{y}'] for t, y in INDIAN_CLASSICS if known.get(f'{t}|{y}')]


def indian_classics(providers, ratings, before):
    """The classics on the installed apps, least recently shown first, shuffled a little each day."""
    out = []
    for tid in classic_ids():
        if not rated_enough('movie', tid, ratings, CLASSIC_FLOOR):
            continue
        d = details('movie', tid, max_age=7 * 86400)
        ok = playable(d, providers, ratings, CLASSIC_FLOOR)
        if ok:
            out.append((f'movie-{tid}', d, ok))
    return sorted(out, key=lambda p: held_back(p[0], before) + jitter(p[0]))


def pick_titles(kind, providers, ratings, taste, seeds, before):
    """(ranked by taste, newest first) lists of (key, details, (rating, votes, app)) for one kind.
    Watched titles stay in: rewatches are welcome (the caller caps how many). Titles shown in the
    last few days are held back and the order is shaken a little each day, so the lineup moves."""
    fresh = newest(kind, providers, ratings, set(), FRESH_CANDIDATES)
    pool = {k: (k, d, ok) for k, d, ok in fresh}
    extra = [k for k, _ in sorted(seeds.items(), key=lambda kv: -kv[1]) if k.startswith(kind + '-')][:SEED_CANDIDATES]
    extra += [k for k in taste.watchlist if k.startswith(kind + '-')]
    for key in extra:
        if key in pool:
            continue
        floor = 7.0 if key in taste.watchlist else MIN_RATING
        if not rated_enough(kind, key.split('-', 1)[1], ratings, floor):
            continue
        d = details(kind, key.split('-', 1)[1])
        ok = playable(d, providers, ratings, 7.0 if key in taste.watchlist else MIN_RATING)
        if ok:
            pool[key] = (key, d, ok)
    def rank(item):
        key, d, ok = item
        s = (taste.score(d, key, seeds) if taste.ok else 0) + (ok[0] - 7.5) * 0.4 + freshness(d)
        if key in taste.loved:
            s += 0.8  # a loved one is the best rewatch
        elif key in taste.liked:
            s += 0.5
        return s - held_back(key, before) + jitter(key)
    by_taste = sorted(pool.values(), key=rank, reverse=True)
    # The newest, but those of the last two days after the rest: new arrivals take their turn.
    newest_first = sorted(fresh, key=lambda p: held_back(p[0], before) >= VARIETY[1])
    return by_taste, newest_first


# ---- worth rewatching ---------------------------------------------------------------------

def rewatch_lines(taste, providers, ratings, links):
    """Pool lines (no cut-out: they're only for the mood cards) for loved titles that are on
    the installed apps, so "Worth rewatching" can open them."""
    out = []
    for key in list(taste.loved) + list(taste.liked):
        kind, tid = key.split('-', 1)
        d = details(kind, tid, max_age=7 * 86400, images=True)
        ok = playable(d, providers, ratings, 0)
        if not ok:
            continue
        backs = [b for b in d.get('images', {}).get('backdrops', []) if not b.get('iso_639_1')]
        dst = os.path.join(POOL, 'scenes', key + '.jpg')
        if backs and not os.path.exists(dst):
            src = download(f'https://image.tmdb.org/t/p/w1280{backs[0]["file_path"]}',
                           os.path.join(CACHE, 'images', backs[0]['file_path'].strip('/')))
            if src:
                scene(src, dst)
        title = d.get('title') or d.get('name')
        date = d.get('release_date') or d.get('first_air_date') or ''
        link = title_link(kind, tid, ok[2], links, title)
        out.append(' | '.join([key, title.replace('|', '/'), 'movie' if kind == 'movie' else 'series', date[:4],
                               f'{ok[0]:.1f}', str(ok[1]), ok[2], '0', '#C8D0E0', '0', link, 'rewatch', genre_text(d)]))
    return out


# ---- images ------------------------------------------------------------------------------

def judge(stats):
    """Score a cut-out (higher is better); None when it is not a usable figure."""
    if not stats.get('ok'):
        return None
    x, y, w, h = stats['bbox']
    if stats['faces'] < 1 or stats['faces'] > 2 or stats['instances'] > 2:
        return None
    if h < 0.45 or not 0.05 <= stats['coverage'] <= 0.55 or w > 0.9:
        return None
    # A figure, not a strip: tall enough for its width, and big enough to look sharp.
    if stats['h'] < 500 or stats['w'] / stats['h'] > 1.25:
        return None
    score = h * 2 - abs(stats['coverage'] - 0.22) * 2
    score += 0.4 if stats['instances'] == 1 else 0
    score += 0.2 if stats['faces'] == 1 else 0
    return score


def feather(im, frac=0.14):
    """Fades edges where the source image cropped the figure, as tools/prep_characters.py does."""
    a = im.getchannel('A')
    w, h = im.size

    def ramp(length, horizontal, reverse):
        m = Image.new('L', (w, h), 255)
        d = ImageDraw.Draw(m)
        for i in range(length):
            v = int(255 * (i / length) ** 1.6)
            if horizontal:
                xx = w - 1 - i if reverse else i
                d.line([(xx, 0), (xx, h)], fill=v)
            else:
                d.line([(0, i), (w, i)], fill=v)
        return m

    if max(a.getpixel((0, yy)) for yy in range(0, h, 4)) > 128:
        a = ImageChops.multiply(a, ramp(int(w * frac), True, False))
    if max(a.getpixel((w - 1, yy)) for yy in range(0, h, 4)) > 128:
        a = ImageChops.multiply(a, ramp(int(w * frac), True, True))
    if max(a.getpixel((xx, 0)) for xx in range(0, w, 4)) > 128:
        a = ImageChops.multiply(a, ramp(int(h * frac), False, False))
    im.putalpha(a)
    return im


def glow_colour(im):
    """A saturated mid colour from the figure, for its glow."""
    small = im.copy()
    small.thumbnail((80, 80))
    best, best_score = (200, 200, 220), -1
    for (r, g, b, a) in small.getdata():
        if a < 200:
            continue
        mx, mn = max(r, g, b), min(r, g, b)
        sat = (mx - mn) / mx if mx else 0
        score = sat * (1 - abs(mx / 255 - 0.7))
        if score > best_score:
            best, best_score = (r, g, b), score
    if best_score < 0.25:
        return '#C8D0E0'
    r, g, b = best
    k = 230 / max(r, g, b)
    return '#%02X%02X%02X' % (min(255, int(r * k)), min(255, int(g * k)), min(255, int(b * k)))


def scene(src, dst):
    W, H = 1920, 1080
    im = ImageOps.fit(Image.open(src).convert('RGB'), (W, H), Image.LANCZOS)
    im = ImageOps.grayscale(im)
    im = ImageEnhance.Contrast(im).enhance(1.1)
    im = ImageEnhance.Brightness(im).enhance(0.42)
    v = Image.new('L', (W, H), 0)
    ImageDraw.Draw(v).ellipse([-W * 0.25, -H * 0.35, W * 1.25, H * 1.35], fill=255)
    v = v.filter(ImageFilter.GaussianBlur(220))
    Image.composite(im, Image.new('L', (W, H), 0), v).save(dst, quality=82, optimize=True)


def make_assets(d, key, work):
    """Cut-out + scene for one title. Returns (aspect, colour, face height fraction) or None."""
    cut_dst = os.path.join(POOL, 'cutouts', key + '.webp')
    scene_dst = os.path.join(POOL, 'scenes', key + '.jpg')
    meta = os.path.join(work, key + f'.v{JUDGE_VERSION}.json')  # a new judge re-checks old picks
    rejected = os.path.join(work, key + f'.v{JUDGE_VERSION}.rejected')
    if os.path.exists(cut_dst) and os.path.exists(scene_dst) and os.path.exists(meta):
        return tuple(json.load(open(meta)))
    if os.path.exists(rejected):
        return None  # judged before: no usable figure in its images
    imgs = d.get('images', {})
    textless = lambda lst: [i for i in lst if not i.get('iso_639_1')]
    posters = sorted(textless(imgs.get('posters', [])), key=lambda i: -i.get('vote_average', 0))[:3]
    backdrops = sorted(textless(imgs.get('backdrops', [])), key=lambda i: -i.get('vote_average', 0))
    tries = [('w780', p['file_path']) for p in posters] + [('w1280', b['file_path']) for b in backdrops[:4]]
    best = None
    for size, fp in tries:
        src = download(f'https://image.tmdb.org/t/p/{size}{fp}', os.path.join(work, fp.strip('/')))
        if not src:
            continue
        out = os.path.join(work, key + '-' + fp.strip('/').rsplit('.', 1)[0] + '.png')
        try:
            stats = json.loads(subprocess.run([CUTOUT_TOOL, src, out], capture_output=True, text=True, timeout=60).stdout.strip() or '{}')
        except (subprocess.SubprocessError, ValueError):
            continue
        score = judge(stats)
        if score is not None and (best is None or score > best[0]):
            best = (score, out, fp, stats.get('faceH', 0))
        if best and best[0] > 1.6:
            break  # good enough; saves downloads on a slow line
    if not best:
        open(rejected, 'w').close()  # remembered, so daily runs don't redo the work
        return None
    im = Image.open(best[1]).convert('RGBA')
    if im.height > 900:
        im = im.resize((round(im.width * 900 / im.height), 900), Image.LANCZOS)
    im = feather(im)
    im.save(cut_dst, 'WEBP', quality=86, method=6)
    # The scene: a backdrop other than the one the figure came from, when there is one.
    others = [b for b in backdrops if b['file_path'] != best[2]] or backdrops
    if not others:
        return None
    bsrc = download(f'https://image.tmdb.org/t/p/w1280{others[0]["file_path"]}', os.path.join(work, others[0]['file_path'].strip('/')))
    if not bsrc:
        return None
    scene(bsrc, scene_dst)
    result = (round(im.width / im.height, 3), glow_colour(im), round(best[3], 3))
    json.dump(result, open(meta, 'w'))
    return result


# ---- title links -----------------------------------------------------------------------------

# Where each app's title pages live, and how to tidy the link before the TV app gets it.
# JioHotstar is left out: its Fire TV app ignores every outside link (tried short and full page
# links, hotstar://, cold and while running) and opens its home page, so its titles open the app.
LINK_HOSTS = {
    'com.netflix.ninja': 'netflix.com',
    'com.amazon.firebat': 'primevideo.com',
    'com.apple.atve.amazon.appletv': 'tv.apple.com',
    'com.onemainstream.sonyliv.android': 'sonyliv.com',
    'com.zee5.amazon': 'zee5.com',
    'com.discoveryplus.tv.fire': 'discoveryplus',
}
LINK_MAX_AGE = 7 * 86400


def slug(title):
    keep = ''.join(c if c.isalnum() or c in ' -' else '' for c in title.lower().replace('&', 'and'))
    return '-'.join(keep.replace('-', ' ').split())


def tidy_link(pkg, url, kind, title):
    p = urllib.parse.urlparse(url)
    if pkg == 'in.startv.hotstar':
        # Always the full page form, /in/movies|shows/<name>/<id>, with no search tracking.
        hid = p.path.rstrip('/').rsplit('/', 1)[-1]
        if hid.isdigit():
            return f'https://www.hotstar.com/in/{"movies" if kind == "movie" else "shows"}/{slug(title)}/{hid}'
    if pkg == 'com.netflix.ninja':
        nid = p.path.rstrip('/').rsplit('/', 1)[-1]
        return f'https://www.netflix.com/title/{nid}' if nid.isdigit() else url
    if pkg == 'com.apple.atve.amazon.appletv':
        return urllib.parse.urlunparse(p._replace(query='', fragment=''))  # drop affiliate tracking
    return url


def title_link(kind, tmdb_id, pkg, cache, title):
    """The title's own page in its app, from TMDB's "where to watch" page (JustWatch links);
    '-' when there is none. Cached for a week."""
    if pkg not in LINK_HOSTS:
        return '-'  # e.g. JioHotstar: its TV app ignores links, so its titles open the app
    key = f'{kind}-{tmdb_id}-{pkg}'
    hit = cache.get(key)
    if hit and hit[0] != '-' and time.time() - hit[1] < LINK_MAX_AGE:
        return tidy_link(pkg, hit[0], kind, title)
    link = '-'
    host = LINK_HOSTS.get(pkg)
    if host:
        # The website turns Python's HTTP client away (403) but serves curl.
        url = f'https://www.themoviedb.org/{kind}/{tmdb_id}/watch?locale={REGION}'
        try:
            page = subprocess.run(['curl', '-sL', '--max-time', '30', '-A', 'Mozilla/5.0 (Macintosh)', url],
                                  capture_output=True, text=True, check=True).stdout
        except subprocess.SubprocessError:
            return '-'  # try again next run
        for m in re.finditer(r'href="(https://click\.justwatch\.com[^"]+)"', page):
            target = urllib.parse.parse_qs(urllib.parse.urlparse(html.unescape(m.group(1))).query).get('r', [''])[0]
            if host in urllib.parse.urlparse(target).netloc:
                link = target
                break
    cache[key] = [link, time.time()]
    return tidy_link(pkg, link, kind, title) if link != '-' else link



# ---- daily quotes and wallpapers ----------------------------------------------------------

QUOTES_PER_DAY = 40
WALLPAPERS_PER_DAY = 20  # beside 2 of the fixed Interstellar set (the TV picks those)
# Each day's mix: the best taste matches among the classics, recent big films (brand-new ones
# first: The Odyssey), and Indian classics (with fewer IMDb votes than Hollywood's, so a lower bar).
WALL_MIX = (('taste', 10), ('recent', 5), ('indian', 5))
RECENT_YEARS = 3
BRAND_NEW_DAYS = 200
INDIAN_MIN_IMDB_VOTES = 20_000
CLASSIC_PAGES = 8  # TMDB's all-time top-rated films, 20 a page, before the IMDb-votes filter
CLASSIC_MIN_IMDB_VOTES = 100_000


def imdb_id_for(tmdb_id):
    """A film's IMDb id, cached (it never changes)."""
    f = os.path.join(CACHE, 'imdb-ids.json')
    cache = json.load(open(f)) if os.path.exists(f) else {}
    key = str(tmdb_id)
    if key not in cache:
        cache[key] = tmdb(f'/movie/{tmdb_id}/external_ids').get('imdb_id')
        json.dump(cache, open(f, 'w'))
    return cache[key]


def afi_quotes():
    """AFI's 100 greatest movie quotes, from Wikipedia; cached for a month."""
    f = os.path.join(CACHE, 'afi-quotes.json')
    if os.path.exists(f) and time.time() - os.path.getmtime(f) < 30 * 86400:
        return json.load(open(f))
    url = ('https://en.wikipedia.org/w/api.php?action=parse&format=json&prop=wikitext'
           '&page=AFI%27s_100_Years...100_Movie_Quotes')
    req = urllib.request.Request(url, headers={'User-Agent': 'tv-home-refresh/1.0 (personal use)'})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            text = json.load(r)['parse']['wikitext']['*']
    except (urllib.error.URLError, TimeoutError, KeyError, ValueError):
        return json.load(open(f)) if os.path.exists(f) else []

    def plain(cell):
        cell = re.sub(r'\{\{efn\|.*?\}\}', '', cell)
        cell = re.sub(r'\{\{sortname\|([^|}]*)\|([^|}]*)(?:\|([^|}]*))?\}\}',
                      lambda m: m.group(3) or f'{m.group(1)} {m.group(2)}', cell)
        cell = re.sub(r'\[\[(?:[^|\]]*\|)?([^\]]*)\]\]', r'\1', cell)
        cell = re.sub(r'<[^>]+>|\{\{[^}]*\}\}', '', cell)
        return cell.replace("''", '').strip().strip('"').strip()

    out = []
    table = text[text.find('{| class="wikitable'):]
    for row in table.split('\n|-')[1:]:
        cells = [c[1:].strip() for c in row.strip().split('\n') if c.startswith('|') and not c.startswith('|}')]
        if len(cells) >= 6:
            q, film = plain(cells[1]), plain(cells[4])
            if q and film:
                out.append([q, film])
    if out:
        json.dump(out, open(f, 'w'))
    return out



def _split_top(text, sep='|'):
    """Splits wiki markup on `sep`, ignoring separators inside [[...]] and {{...}}."""
    parts, depth, cur, i = [], 0, '', 0
    while i < len(text):
        two = text[i:i + 2]
        if two in ('[[', '{{'):
            depth += 1
            cur += two
            i += 2
            continue
        if two in (']]', '}}'):
            depth -= 1
            cur += two
            i += 2
            continue
        if text[i] == sep and depth == 0:
            parts.append(cur)
            cur = ''
        else:
            cur += text[i]
        i += 1
    parts.append(cur)
    return parts


def _clean_wiki(t):
    t = re.sub(r'\[\[(?:[^|\]]*\|)?([^\]]*)\]\]', r'\1', t)
    t = re.sub(r'\{\{[^}]*\}\}|<[^>]+>', ' ', t)
    t = t.replace("'" * 3, '').replace("'" * 2, '')
    return re.sub(r'\s+', ' ', t).strip().strip('"').strip()


def film_quotes(title, year, kind='film'):
    """Short, well-known lines from the film's Wikiquote page: the page's picture captions first
    (editors put the famous lines there), then short lines from the characters' sections.
    Cached for two months."""
    folder = os.path.join(CACHE, 'wikiquote')
    os.makedirs(folder, exist_ok=True)
    f = os.path.join(folder, slug(f'{title} {year} {kind}') + '.json')
    if os.path.exists(f) and time.time() - os.path.getmtime(f) < 60 * 86400:
        return json.load(open(f))
    api = 'https://en.wikiquote.org/w/api.php?'
    headers = {'User-Agent': 'tv-home-refresh/1.0 (personal use)'}

    def get(params):
        # Wikiquote answers rapid requests with an empty page: pace them and retry.
        for attempt in range(4):
            time.sleep(0.6 + attempt * 3)
            try:
                req = urllib.request.Request(api + urllib.parse.urlencode(params), headers=headers)
                with urllib.request.urlopen(req, timeout=30) as r:
                    return json.load(r)
            except (urllib.error.URLError, TimeoutError, ValueError):
                if attempt == 3:
                    raise

    quotes = []
    try:
        hits = get({'action': 'query', 'list': 'search', 'format': 'json', 'srlimit': 6,
                    'srsearch': f'{title} {year} film' if kind == 'film' else f'{title} TV series'})['query']['search']
        want = title.lower()
        page = next((h['title'] for h in hits if h['title'].lower() == want or
                     h['title'].lower().startswith(want + ' (')), None)
        if page:
            text = get({'action': 'parse', 'format': 'json', 'prop': 'wikitext', 'page': page})['parse']['wikitext']['*']
            captions, lines, section = [], [], ''
            skip = ('dialogue', 'tagline', 'cast', 'about', 'external', 'see also', 'quotes about', 'reviews')
            for line in text.split('\n'):
                if line.startswith('[[File:') or line.startswith('[[Image:'):
                    captions.append(_clean_wiki(_split_top(line.strip()[2:-2])[-1]))
                elif re.match(r'^==[^=].*==\s*$', line):
                    section = line.strip('= \n').lower()
                elif line.startswith('* ') and not any(k in section for k in skip):
                    lines.append(_clean_wiki(line[2:]))

            def good(q):
                return 12 <= len(q) <= 100 and not re.match(r"^[A-Z][\w .'-]{0,25}:", q) and '"' not in q

            seen = set()
            for q in [c for c in captions if good(c)] + [l for l in lines if good(l)]:
                if q.lower() not in seen:
                    seen.add(q.lower())
                    quotes.append(q)
                if len(quotes) >= 6:
                    break
    except (urllib.error.URLError, TimeoutError, KeyError, ValueError):
        return json.load(open(f)) if os.path.exists(f) else []
    json.dump(quotes, open(f, 'w'))
    return quotes


def wallpaper_quotes(folder, films):
    """quotes.txt beside the wallpapers: "file | quote" lines (several per file; "*" for all)."""
    out = []
    known = afi_quotes() + extra_quotes()
    for name, title, year in films:
        qs = film_quotes(title, year)[:5]
        if not qs:  # nothing short on Wikiquote: lines from AFI's list or quotes-extra.txt
            qs = [q for q, src in known if src.lower() == title.lower() and '"' not in q][:5]
        for q in qs:
            out.append(f'{name} | {q}')
    with open(os.path.join(folder, 'quotes.txt'), 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(out) + '\n')
    return len(out)

def extra_quotes():
    """[quote, source] pairs from quotes-extra.txt."""
    out = []
    extra = os.path.join(ROOT, 'quotes-extra.txt')
    if os.path.exists(extra):
        for line in open(extra, encoding='utf-8'):
            if '~' in line and not line.lstrip().startswith('#'):
                q, src = line.split('~', 1)
                out.append([q.strip(), src.strip()])
    return out


TASTE_QUOTES = 24  # of the 40: from films and series the viewer has watched, loved ones most


def taste_quotes(taste):
    """[quote, source, weight] from Wikiquote for everything the viewer has watched."""
    out = []
    for key, title in taste.watched.items():
        kind, tid = key.split('-', 1)
        d = details(kind, tid, max_age=30 * 86400)
        year = (d.get('release_date') or d.get('first_air_date') or '')[:4]
        name = d.get('title') or d.get('name') or title
        w = 3 if key in taste.loved else 2 if key in taste.liked else 1
        for q in film_quotes(name, year, 'film' if kind == 'movie' else 'series'):
            out.append([q, name, w])
    return out


def daily_quotes(taste=None):
    """Today's quotes: mostly lines from what the viewer has watched (loved titles weighted up),
    the rest from AFI's list and quotes-extra.txt; short enough to read at a glance."""
    mine = []
    if taste is not None and taste.ok:
        rnd0 = random.Random(datetime.date.today().toordinal() * 7)
        weighted = [q for q in taste_quotes(taste) if len(q[0]) <= 95 and '"' not in q[0]]
        while weighted and len(mine) < TASTE_QUOTES:
            pick = rnd0.choices(weighted, weights=[q[2] for q in weighted])[0]
            weighted.remove(pick)
            mine.append([pick[0], pick[1]])
    pool = mine + afi_quotes() + extra_quotes()
    # Short enough to read at a glance, and not two-person dialogue.
    pool = [p for p in pool if len(p[0]) <= 95 and '"' not in p[0]]
    seen, unique = set(), []
    for q, src in pool:
        if q.lower() not in seen:
            seen.add(q.lower())
            unique.append([q, src])
    rnd = random.Random(datetime.date.today().toordinal())
    first, rest = unique[:len(mine)], unique[len(mine):]
    rnd.shuffle(rest)
    today = first + rest[:QUOTES_PER_DAY - len(first)]
    rnd.shuffle(today)
    with open(os.path.join(POOL, 'quotes.txt'), 'w', encoding='utf-8') as f:
        f.write(f'# {len(today)} of {len(unique)} quotes for {datetime.date.today()}\n')
        f.write('\n'.join(f'{q} ~ {src}' for q, src in today) + '\n')
    log(f'quotes: {len(today)} of {len(unique)}')


WALLPAPER_ROTATION = 120  # the best taste matches the daily window moves through (12 days)


def daily_wallpapers(taste=None, seeds=None):
    """Today's classic-film wallpapers: well-known films (100,000+ IMDb votes) ranked by the
    viewer's taste, their own favourites and TMDB's "more like these" included; a daily window
    moves through the best of them. Each is a text-free backdrop darkened for the dark theme."""
    out_dir = os.path.join(POOL, 'wallpapers')
    os.makedirs(out_dir, exist_ok=True)
    # The real classics: TMDB's highest-rated films that also have at least 100,000 IMDb votes
    # (TMDB's own counts top out around 40,000, so the bar is set on IMDb's scale).
    ratings = imdb_ratings()
    films = []
    for page in range(1, CLASSIC_PAGES + 1):
        films += tmdb('/discover/movie', sort_by='vote_average.desc', **{'vote_count.gte': 3000}, page=page)['results']
    # Anime stills are too bright for the dark theme; Interstellar already has its own set.
    films = [f for f in films if f.get('original_language') != 'ja' and f['id'] != 157336]
    films = [f for f in films if ratings.get(imdb_id_for(f['id']), (0, 0))[1] >= CLASSIC_MIN_IMDB_VOTES]
    if taste is not None and taste.ok:
        seeds = seeds or {}
        known = {f['id'] for f in films}
        extra = [k for k in taste.watched if k.startswith('movie-')]
        extra += [k for k, _ in sorted(seeds.items(), key=lambda kv: -kv[1]) if k.startswith('movie-')][:60]
        for key in extra:
            tid = int(key.split('-', 1)[1])
            if tid in known or tid == 157336:
                continue
            d = details('movie', tid, max_age=30 * 86400)
            votes = ratings.get(d.get('external_ids', {}).get('imdb_id') or d.get('imdb_id'), (0, 0))[1]
            if votes >= CLASSIC_MIN_IMDB_VOTES and d.get('original_language') != 'ja':
                films.append(d)
                known.add(tid)

        def score(f):
            key = f'movie-{f["id"]}'
            d = details('movie', f['id'], max_age=30 * 86400)
            s = taste.score(d, key, seeds) + (f.get('vote_average', 7) - 7) * 0.3
            return s + (2.0 if key in taste.loved else 1.2 if key in taste.liked else 0.6 if key in taste.watched else 0)

        films = sorted(films, key=score, reverse=True)[:WALLPAPER_ROTATION]
        log('wallpapers: taste picks ' + ', '.join(f['title'] for f in films[:8]) + ' …')
    todays = todays_walls({'taste': films, 'recent': recent_walls(ratings, taste, seeds),
                           'indian': indian_walls(ratings)})
    work = os.path.join(CACHE, 'wallpapers')
    os.makedirs(work, exist_ok=True)
    captions, keep, films_today = [], set(), []
    for film in todays:
        name = f'classic-{film["id"]}.jpg'
        dst = os.path.join(out_dir, name)
        if not os.path.exists(dst):
            imgs = tmdb(f'/movie/{film["id"]}/images', include_image_language='null')
            backs = sorted(imgs.get('backdrops', []), key=lambda i: -i.get('vote_average', 0))
            if not backs:  # text-free ones first; some (older Indian films) have only titled ones
                imgs = tmdb(f'/movie/{film["id"]}/images', include_image_language='en,hi')
                backs = sorted(imgs.get('backdrops', []), key=lambda i: -i.get('vote_average', 0))
            if not backs:
                continue
            src = download(f'https://image.tmdb.org/t/p/original{backs[0]["file_path"]}',
                           os.path.join(work, backs[0]['file_path'].strip('/')))
            if not src:
                continue
            im = ImageOps.fit(Image.open(src).convert('RGB'), (1920, 1080), Image.LANCZOS)
            mean = sum(ImageOps.grayscale(im).resize((64, 36)).getdata()) / (64 * 36)
            if mean > 62:
                im = ImageEnhance.Brightness(im).enhance(62 / mean)
            im.save(dst, quality=90, optimize=True)
        keep.add(name)
        year = (film.get('release_date') or '')[:4]
        captions.append(f'{name} | {film["title"]}  ·  {year}')
        films_today.append((name, film['title'], year))
    for f in os.listdir(out_dir):
        if f.endswith('.jpg') and f not in keep:
            os.remove(os.path.join(out_dir, f))
    with open(os.path.join(out_dir, 'captions.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(captions) + '\n')
    n = wallpaper_quotes(out_dir, films_today)
    # The fixed set (Interstellar) gets its film's lines too.
    m = wallpaper_quotes(os.path.join(ROOT, 'tv', 'wallpapers'), [('*', 'Interstellar', 2014)])
    log(f'wallpapers: {len(keep)} classics today, {n + m} film quotes')
    return keep


def recent_walls(ratings, taste, seeds):
    """Big films of the last few years (100,000+ IMDb votes): the brand-new first, newest first,
    then the rest by taste."""
    since = (datetime.date.today() - datetime.timedelta(days=365 * RECENT_YEARS)).isoformat()
    films = []
    for page in (1, 2, 3):
        films += tmdb('/discover/movie', sort_by='vote_count.desc', **{'primary_release_date.gte': since},
                      page=page)['results']
    films = [f for f in films if f.get('original_language') != 'ja' and f['id'] != 157336
             and ratings.get(imdb_id_for(f['id']), (0, 0))[1] >= CLASSIC_MIN_IMDB_VOTES]
    cut = (datetime.date.today() - datetime.timedelta(days=BRAND_NEW_DAYS)).isoformat()
    new = sorted((f for f in films if (f.get('release_date') or '') >= cut), key=lambda f: f.get('release_date') or '', reverse=True)
    rest = [f for f in films if f not in new]
    if taste is not None and taste.ok:
        rest.sort(key=lambda f: taste.score(details('movie', f['id'], max_age=30 * 86400), f'movie-{f["id"]}', seeds or {}),
                  reverse=True)
    if new:
        log('wallpapers: brand-new ' + ', '.join(f['title'] for f in new[:5]))
    return [{**f, 'brand_new': f in new} for f in new + rest]


def indian_walls(ratings):
    """The Indian classics that enough people know (20,000+ IMDb votes), any app or none."""
    out = []
    for tid in classic_ids():
        d = details('movie', tid, max_age=30 * 86400)
        votes = ratings.get(d.get('external_ids', {}).get('imdb_id') or d.get('imdb_id'), (0, 0))[1]
        if votes >= INDIAN_MIN_IMDB_VOTES:
            out.append({'id': tid, 'title': d.get('title'), 'release_date': d.get('release_date')})
    return out


def todays_walls(buckets):
    """WALLPAPERS_PER_DAY films from the buckets in WALL_MIX: in each, a window moving through it
    day by day; brand-new films always first. A film already picked is skipped."""
    day = datetime.date.today().toordinal()
    out, ids = [], set()
    for name, n in WALL_MIX:
        films = buckets.get(name) or []
        if not films:
            continue
        new = [f for f in films if f.get('brand_new')][:2]
        rest = [f for f in films if f not in new]
        start = (day * n) % max(len(rest), 1)
        for f in new + (rest + rest)[start:start + len(rest)]:
            if sum(1 for g in out if g.get('_from') == name) >= n:
                break
            if f['id'] not in ids:
                ids.add(f['id'])
                out.append({**f, '_from': name})
    return out[:WALLPAPERS_PER_DAY]


# ---- mood cards -------------------------------------------------------------------------

# The viewer's own rails (from taste.json's suggested homepage rails): name, card colour, and
# the tags (taste clusters and others, see tags_for) that put a title in it.
MOODS = [
    ('Mind-bending tonight', '#7C6CFF', {'cerebral_mystery_psychological', 'intelligent_speculative_scifi'}),
    ('After a long day', '#F2B84B', {'feel_good_human_drama'}),
    ('The next Mr. Robot', '#33D17A', {'tech'}),
    ('Hidden gems from Indian cinema', '#FF8A3D', {'indian', 'grounded_indian_drama', 'malayalam_and_indian_regional_cinema'}),
    ('Dark crime & slow burns', '#E5484D', {'crime_investigation_prestige_drama'}),
    ('Eerie & atmospheric', '#5BA8B5', {'atmospheric_horror_and_dark_mystery'}),
    ('From your watchlist', '#4C8DFF', {'watchlist'}),
    ('Worth rewatching', '#D16BA5', {'rewatch'}),
]
ANYTHING = ('Anything at all', '#B8C0CC')
MIN_PER_MOOD = 3


def title_extras(kind, tmdb_id, d=None):
    """(genre names, poster path) for a title; cached, since neither changes."""
    f = os.path.join(CACHE, 'extras.json')
    cache = json.load(open(f)) if os.path.exists(f) else {}
    key = f'{kind}-{tmdb_id}'
    if key not in cache:
        d = d or tmdb(f'/{kind}/{tmdb_id}', append_to_response='images', include_image_language='null,en')
        posters = d.get('images', {}).get('posters', [])
        textless = [p for p in posters if not p.get('iso_639_1')]
        en = [p for p in posters if p.get('iso_639_1') == 'en']
        # The title's own key-art poster reads best on a card; a text-free one if that's all there is.
        path = d.get('poster_path') or (en or textless or [{}])[0].get('file_path')
        cache[key] = [[g['name'] for g in d.get('genres', [])], path]
        json.dump(cache, open(f, 'w'))
    return cache[key]


def make_moods(lines):
    """pool/moods.txt ("name | colour | key,key,...") and pool/posters/<key>.jpg for the deck."""
    folder = os.path.join(POOL, 'posters')
    os.makedirs(folder, exist_ok=True)
    keys, genres = [], {}
    for line in lines:
        cols = line.split(' | ')
        key = cols[0]
        kind, tid = key.split('-', 1)
        names, poster = title_extras(kind, tid)
        tags = set(cols[11].split(',')) if len(cols) > 11 and cols[11] else set()
        dst = os.path.join(folder, key + '.jpg')
        if poster and not os.path.exists(dst):
            src = download(f'https://image.tmdb.org/t/p/w342{poster}', os.path.join(CACHE, 'images', poster.strip('/')))
            if src:
                Image.open(src).convert('RGB').save(dst, quality=85, optimize=True)
        if os.path.exists(dst):
            keys.append(key)
            genres[key] = tags
    for f in os.listdir(folder):
        if f.endswith('.jpg') and f[:-4] not in keys:
            os.remove(os.path.join(folder, f))
    rnd = random.Random(datetime.date.today().toordinal())
    out = []
    for name, colour, wanted in MOODS:
        members = [k for k in keys if genres[k] & wanted]
        if len(members) >= MIN_PER_MOOD:
            rnd.shuffle(members)
            out.append(f'{name} | {colour} | {",".join(members)}')
    rnd.shuffle(out)  # a different deck order each day
    shuffled = [k for k in keys if 'rewatch' not in genres[k]]  # anything new (or a seen one in the pool)
    rnd.shuffle(shuffled)
    out.append(f'{ANYTHING[0]} | {ANYTHING[1]} | {",".join(shuffled)}')
    with open(os.path.join(POOL, 'moods.txt'), 'w', encoding='utf-8') as f:
        f.write('# mood | colour | titles (keys from pool.txt)\n' + '\n'.join(out) + '\n')
    log(f'moods: {len(out)} ({", ".join(o.split(" | ")[0] for o in out)})')
    return set(keys)

def lights_key(text):
    """The same normalisation as Palette.normal on the TV: lower case, & as "and", only letters
    and digits, single spaces."""
    return re.sub(r'[\W_]+', ' ', text.lower().replace('&', ' and ')).strip()


def season(tid, number, latest):
    """One season's episode list, cached: a week for the season still airing, three months for the rest."""
    folder = os.path.join(CACHE, 'seasons')
    os.makedirs(folder, exist_ok=True)
    f = os.path.join(folder, f'tv-{tid}-s{number}.json')
    if os.path.exists(f) and time.time() - os.path.getmtime(f) < (7 if latest else 90) * 86400:
        return json.load(open(f))
    d = tmdb(f'/tv/{tid}/season/{number}')
    json.dump({'episodes': [{'season_number': e.get('season_number', number), 'episode_number': e.get('episode_number'),
                             'name': e.get('name', '')} for e in d.get('episodes', [])]}, open(f, 'w'))
    return json.load(open(f))


def make_lights(lines, taste):
    """pool/lights.txt ("match | key | image") for the light strip: what an app may call a title,
    mapped to the TMDB title whose backdrop gives the colours. Apps name episodes rather than
    shows (Hotstar: "S1 E1 No Strings"), so every episode of the pool's and the taste profile's
    series is in it, as "s1e1 no strings" and, when no other series has an episode of that name,
    as "no strings". Names shared by two titles are left out rather than guessed."""
    titles = {l.split(' | ')[0] for l in lines}
    if taste is not None and taste.ok:
        titles |= {f'{k.split(":", 1)[0]}-{v}' for k, v in taste.ids.items() if v}
    found = {}

    def add(match, key, image):
        if match:
            found.setdefault(match, set()).add((key, image))

    episodes = 0
    for key in sorted(titles):
        kind, tid = key.split('-', 1)
        try:
            d = details(kind, tid, max_age=7 * 86400)
        except Exception as e:  # noqa: BLE001 - one bad title must not stop the refresh
            log(f'lights: {key}: {e}')
            continue
        image = d.get('backdrop_path') or d.get('poster_path')
        if not image:
            continue
        for name in {d.get('title'), d.get('name'), d.get('original_title'), d.get('original_name')} - {None, ''}:
            add(lights_key(name), key, image)
        if kind != 'tv':
            continue
        numbers = [s['season_number'] for s in d.get('seasons', []) if s.get('season_number')]
        for n in numbers:
            try:
                eps = season(tid, n, n == max(numbers))['episodes']
            except Exception as e:  # noqa: BLE001
                log(f'lights: {key} season {n}: {e}')
                continue
            for e in eps:
                name = lights_key(e['name'])
                if not name:
                    continue
                add(f's{e["season_number"]}e{e["episode_number"]} {name}', key, image)
                add(name, key, image)
                episodes += 1
    out = [f'{m} | {next(iter(v))[0]} | {next(iter(v))[1]}' for m, v in sorted(found.items())
           if len({k for k, _ in v}) == 1]
    with open(os.path.join(POOL, 'lights.txt'), 'w', encoding='utf-8') as f:
        f.write('# match | key | image (TMDB backdrop, or poster) -- for the light strip; built by refresh_pool.py\n')
        f.write('\n'.join(out) + '\n')
    log(f'lights: {len(titles)} titles, {episodes} episodes, {len(out)} names')


# ---- main ------------------------------------------------------------------------------------

def prune_cache(days=30):
    """Drops downloaded source images not used for a month, so the Mac cache doesn't grow forever."""
    cutoff, freed = time.time() - days * 86400, 0
    for sub in ('images', 'wallpapers'):
        folder = os.path.join(CACHE, sub)
        for f in os.listdir(folder) if os.path.isdir(folder) else []:
            path = os.path.join(folder, f)
            if f.endswith(('.jpg', '.png')) and os.path.getmtime(path) < cutoff:
                freed += os.path.getsize(path)
                os.remove(path)
    if freed:
        log(f'cache: freed {freed / 1e6:.0f} MB')


def build_kind(kind, providers, ratings, taste, seeds, links, work, before):
    """30 lines for one kind: 80% the best taste matches (films: 6 of those slots go to Indian
    classics), then the newest releases; at most SEEN_MAX titles the viewer has watched among
    the rest (rewatches welcome, new things first; a classic may be one seen before)."""
    by_taste, newest_first = pick_titles(kind, providers, ratings, taste, seeds, before)
    classics = indian_classics(providers, ratings, before) if kind == 'movie' else []
    out, done, seen = [], set(), [0]
    classic_slots = min(CLASSIC_SLOTS, len(classics))
    taste_slots = round(PER_KIND * TASTE_SHARE) - classic_slots

    def add(item, why):
        key, d, ok = item
        if key in done:
            return
        done.add(key)
        tags = tags_for(d, key, taste)
        if 'seen' in tags and seen[0] >= SEEN_MAX and why != 'classic':
            return
        title = d.get('title') or d.get('name')
        made = make_assets(details(kind, d['id'], images=True), key, work)
        if not made:
            log(f'  skip  {title} (no clean cut-out)')
            return
        if 'seen' in tags and why != 'classic':
            seen[0] += 1
        aspect, colour, face = made
        date = d.get('release_date') or d.get('first_air_date') or ''
        rating, votes, pkg = ok
        link = title_link(kind, d['id'], pkg, links, title)
        out.append(' | '.join([key, title.replace('|', '/'), 'movie' if kind == 'movie' else 'series', date[:4],
                               f'{rating:.1f}', str(votes), pkg, f'{aspect:.3f}', colour, f'{face:.3f}', link,
                               ','.join(sorted(tags)), genre_text(d)]))
        log(f'  {len(out):2d}/{PER_KIND} {kind:5s} {rating:.1f} [{why}{", seen" if "seen" in tags else ""}] {title} ({date[:4]})')

    for item in by_taste:
        if len(out) >= taste_slots:
            break
        add(item, 'taste')
    target = len(out) + classic_slots
    for item in classics:
        if len(out) >= target:
            break
        add(item, 'classic')
    for item in newest_first + by_taste:
        if len(out) >= PER_KIND:
            break
        add(item, 'new' if item in newest_first else 'taste')
    return out


def main(push):
    prune_cache()
    for sub in ('cutouts', 'scenes'):
        os.makedirs(os.path.join(POOL, sub), exist_ok=True)
    work = os.path.join(CACHE, 'images')
    os.makedirs(work, exist_ok=True)
    ratings = imdb_ratings()
    apps = installed_apps()
    providers = provider_ids(apps)
    log('apps:', ', '.join(sorted(set(providers.values()))))
    links_file = os.path.join(CACHE, 'links.json')
    links = json.load(open(links_file)) if os.path.exists(links_file) else {}
    taste = Taste(ROOT, tmdb, CACHE, log)
    seeds = taste.seeds() if taste.ok else {}
    before = shown_before()
    lines = []
    for kind in ('movie', 'tv'):
        lines += build_kind(kind, providers, ratings, taste, seeds, links, work, before)
    lines += rewatch_lines(taste, providers, ratings, links) if taste.ok else []
    json.dump(links, open(links_file, 'w'), indent=0)
    keep = {l.split(' | ')[0] for l in lines}
    for sub, ext in (('cutouts', '.webp'), ('scenes', '.jpg')):
        for f in os.listdir(os.path.join(POOL, sub)):
            if f[:-len(ext)] not in keep:
                os.remove(os.path.join(POOL, sub, f))
    with open(os.path.join(POOL, 'pool.txt'), 'w', encoding='utf-8') as f:
        f.write('# key | title | movie/series | year | IMDb | votes | app | cut-out aspect | glow | face height | link | tags | genres\n')
        f.write(f'# refreshed {datetime.datetime.now():%Y-%m-%d %H:%M}\n')
        f.write('\n'.join(lines) + '\n')
    log(f'pool: {len(lines)} titles')
    remember_shown(l.split(' | ')[0] for l in lines)
    daily_quotes(taste)
    walls = daily_wallpapers(taste, seeds)
    posters = make_moods(lines)
    make_lights(lines, taste)
    if push:
        push_or_wait()


def push_pool(keep, walls, posters=None):
    subprocess.run(['adb', 'connect', SERIAL], capture_output=True, timeout=15)
    posters = keep if posters is None else posters
    adb('shell', 'mkdir', '-p', DEST + '/cutouts', DEST + '/scenes', DEST + '/wallpapers', DEST + '/posters')
    for sub, ext, wanted in (('cutouts', '.webp', keep), ('scenes', '.jpg', keep),
                             ('wallpapers', '.jpg', {w[:-4] for w in walls}), ('posters', '.jpg', posters)):
        out = adb('push', '--sync', os.path.join(POOL, sub) + '/.', f'{DEST}/{sub}/').strip().splitlines()
        log(out[-1] if out else f'{sub}: pushed')
        on_tv = adb('shell', 'ls', f'{DEST}/{sub}', check=False).split()
        stale = [f'{DEST}/{sub}/{f}' for f in on_tv if f.endswith(ext) and f[:-len(ext)] not in wanted]
        for i in range(0, len(stale), 40):
            adb('shell', 'rm', '-f', *stale[i:i + 40], check=False)
    adb('push', os.path.join(POOL, 'quotes.txt'), DEST + '/quotes.txt')
    adb('push', os.path.join(POOL, 'moods.txt'), DEST + '/moods.txt')
    adb('push', os.path.join(POOL, 'wallpapers', 'quotes.txt'), DEST + '/wallpapers/quotes.txt')
    adb('push', os.path.join(ROOT, 'tv', 'wallpapers', 'quotes.txt'), os.path.dirname(DEST) + '/wallpapers/quotes.txt')
    adb('push', os.path.join(POOL, 'pool.txt'), DEST + '/pool.txt')
    adb('push', os.path.join(POOL, 'lights.txt'), DEST + '/lights.txt')
    adb('push', os.path.join(ROOT, 'tmdb.key'), os.path.dirname(DEST) + '/tmdb.key')
    log('pushed to TV')


# ---- scheduling -------------------------------------------------------------------------

REFRESH_HOUR = 18  # the day's refresh runs at the first check after 6 pm
PUSHED = os.path.join(CACHE, 'last-push.txt')


def pool_stamp():
    """The "# refreshed YYYY-MM-DD HH:MM" stamp of the pool on the Mac, or ''."""
    try:
        for line in open(os.path.join(POOL, 'pool.txt'), encoding='utf-8'):
            if line.startswith('# refreshed'):
                return line[len('# refreshed'):].strip()
    except OSError:
        pass
    return ''


def push_or_wait():
    """Pushes the pool to the TV; when the TV is off, notes that the push is still owed
    (the next check, every 30 minutes, tries again) instead of failing the run."""
    try:
        if not locate():
            raise OSError('TV not found')
        lines = [l for l in open(os.path.join(POOL, 'pool.txt'), encoding='utf-8') if l.strip() and not l.startswith('#')]
        keep = {l.split(' | ')[0] for l in lines}
        walls = {f for f in os.listdir(os.path.join(POOL, 'wallpapers')) if f.endswith('.jpg')}
        posters = {f[:-4] for f in os.listdir(os.path.join(POOL, 'posters')) if f.endswith('.jpg')}
        push_pool(keep, walls, posters)
        open(PUSHED, 'w').write(pool_stamp())
    except (subprocess.SubprocessError, OSError) as e:
        log(f'TV not reachable ({type(e).__name__}); will push at the next check')


def tv_reachable():
    try:
        return locate()
    except (subprocess.SubprocessError, OSError):
        return False


def auto():
    """The scheduled entry point, run every 30 minutes: today's refresh at the first check after
    6 pm; otherwise only a push still owed to the TV (it was off); otherwise nothing at all."""
    now = datetime.datetime.now()
    refreshed_today = pool_stamp().startswith(now.date().isoformat())
    if not refreshed_today and now.hour >= REFRESH_HOUR:
        log(f'--- {now:%Y-%m-%d %H:%M} daily refresh')
        main(push=True)
        return
    pushed = open(PUSHED).read().strip() if os.path.exists(PUSHED) else ''
    if pool_stamp() and pushed != pool_stamp() and tv_reachable():
        log(f'--- {now:%Y-%m-%d %H:%M} TV is back: pushing the pool refreshed {pool_stamp()}')
        push_or_wait()


if __name__ == '__main__':
    if '--auto' in sys.argv:
        auto()
    else:
        main(push='--no-push' not in sys.argv)
