"""Personal taste for the TV: reads tv-assets/taste.json (the viewer's profile) and scores TMDB
titles against it. Used by refresh_pool.py for Suggestions, mood cards, wallpapers and quotes.

Rules taken from the profile:
- Two main poles: cerebral/twisty mystery and sci-fi; warm, grounded, life-affirming drama. Plus
  Indian (Hindi, Malayalam, other regional) storytelling, prestige crime, atmospheric dark mystery,
  and selective romance.
- Explicitly praised titles are the strongest signal; watched-but-unrated titles are familiarity,
  not endorsement (a weak signal). Watched titles may still come up (the viewer welcomes
  rewatches), marked as seen and capped so new things come first.
- Watchlist titles are boosted. Nothing is excluded only for being outside the profile: a share
  of every refresh is plain new, well-rated releases.
"""
import json
import os
import re
import time

INDIAN = {'hi', 'ml', 'ta', 'te', 'kn', 'mr', 'bn', 'pa', 'gu'}

# name, weight (from the profile's strength), TMDB genres, story-theme words (TMDB keywords or
# overview), languages that count on their own.
CLUSTERS = [
    ('cerebral_mystery_psychological', 1.0, {'Mystery', 'Thriller'},
     ['twist', 'nonlinear', 'non-linear', 'psychological', 'unreliable', 'puzzle', 'identity',
      'deception', 'mind game', 'amnesia', 'memory', 'conspiracy', 'manipulation', 'secret'], set()),
    ('intelligent_speculative_scifi', 1.0, {'Science Fiction', 'Sci-Fi & Fantasy'},
     ['time travel', 'time loop', 'artificial intelligence', ' ai ', 'android', 'robot',
      'alternate reality', 'parallel universe', 'multiverse', 'consciousness', 'simulation',
      'dystopia', 'technology', 'clone', 'future'], set()),
    ('feel_good_human_drama', 1.0, {'Drama', 'Comedy', 'Family'},
     ['friendship', 'found family', 'coming of age', 'hope', 'feel-good', 'feel good', 'life lesson',
      'road trip', 'father son', 'father daughter', 'mother', 'family', 'second chance', 'mentor',
      'self-discovery', 'personal growth', 'unlikely friendship', 'heartwarming', 'dream'], set()),
    ('grounded_indian_drama', 0.8, {'Drama', 'Comedy'},
     ['small town', 'village', 'rural', 'slice of life', 'middle class', 'social', 'realism'], INDIAN),
    ('crime_investigation_prestige_drama', 0.8, {'Crime'},
     ['investigation', 'detective', 'police', 'murder', 'serial killer', 'corruption', 'trial',
      'lawyer', 'heist', 'gangster', 'cop', 'case'], set()),
    ('malayalam_and_indian_regional_cinema', 0.8, {'Mystery', 'Thriller', 'Drama', 'Comedy', 'Crime'},
     [], {'ml', 'ta', 'te', 'kn'}),
    ('atmospheric_horror_and_dark_mystery', 0.6, {'Horror'},
     ['folklore', 'folk horror', 'supernatural', 'curse', 'haunted', 'myth', 'eerie', 'occult'], set()),
    ('romance', 0.35, {'Romance'},
     ['love', 'relationship', 'romance', 'soulmate'], set()),
]

# Mood cards from the profile's suggested rails: name, colour, how a title qualifies.
TECH_WORDS = ['hacker', 'hacking', 'cyber', 'surveillance', 'conspiracy', 'paranoia', 'artificial intelligence',
              'technology', 'computer', 'tech', 'corporation', 'identity', 'dissociative']


def _norm(s):
    return re.sub(r'[^a-z0-9]+', ' ', s.lower()).strip()


def _strip_note(title):
    """'Signal (Korean TV series)' -> 'Signal'; 'Sir (Is Love Enough? Sir)' -> 'Is Love Enough? Sir'."""
    m = re.match(r'^(.*?)\s*\((.*)\)\s*$', title)
    if not m:
        return title
    inner = m.group(2)
    return inner if '?' in inner or len(inner.split()) > 3 and 'series' not in inner.lower() else m.group(1)


class Taste:
    def __init__(self, root, tmdb, cache_dir, log=print):
        path = os.path.join(root, 'taste.json')
        self.ok = os.path.exists(path)
        self.p = json.load(open(path, encoding='utf-8')) if self.ok else {}
        self.tmdb = tmdb
        self.log = log
        self.cache_file = os.path.join(cache_dir, 'taste-ids.json')
        self.ids = json.load(open(self.cache_file)) if os.path.exists(self.cache_file) else {}
        self.rec_file = os.path.join(cache_dir, 'taste-recs.json')
        self.recs = json.load(open(self.rec_file)) if os.path.exists(self.rec_file) else {}
        self.loved, self.liked, self.watched, self.watchlist = {}, {}, {}, {}
        if self.ok:
            self._resolve()

    # ---- the viewer's titles ----------------------------------------------------------------

    def _find(self, title, kind):
        key = f'{kind}:{title}'
        if key in self.ids:
            return self.ids[key]
        q = _strip_note(title)
        res = self.tmdb(f'/search/{kind}', query=q, include_adult='false').get('results', [])
        want = _norm(q)
        best = next((r for r in res if _norm(r.get('title') or r.get('name') or '') == want), None)
        best = best or (res[0] if res else None)
        self.ids[key] = best['id'] if best else None
        return self.ids[key]

    def _resolve(self):
        p = self.p
        for group, kind in (('movies_watched', 'movie'), ('series_watched', 'tv')):
            for t in p.get(group, []):
                tid = self._find(t['title'], kind)
                if not tid:
                    continue
                key = f'{kind}-{tid}'
                s = t.get('sentiment', '')
                if s == 'explicit_positive':
                    self.loved[key] = t['title']
                elif s in ('positive_supported_by_conversation', 'likely_positive'):
                    self.liked[key] = t['title']
                self.watched[key] = t['title']
        for t in p.get('watchlist_or_considered', []):
            kind = 'movie' if t.get('type') == 'movie' else 'tv'
            tid = self._find(t['title'], kind)
            if tid and f'{kind}-{tid}' not in self.watched:
                self.watchlist[f'{kind}-{tid}'] = t['title']
        json.dump(self.ids, open(self.cache_file, 'w'))
        self.log(f'taste: {len(self.loved)} loved, {len(self.liked)} liked, {len(self.watched)} watched, '
                 f'{len(self.watchlist)} on the watchlist')

    def seeds(self):
        """{key: weight} for titles TMDB recommends next to the viewer's titles: strongest for
        loved ones, weaker for liked, weakest for merely watched. Refreshed weekly."""
        out = {}
        for key in self.watched:
            weight = 1.0 if key in self.loved else 0.7 if key in self.liked else 0.25
            hit = self.recs.get(key)
            if not hit or time.time() - hit[1] > 7 * 86400:
                kind, tid = key.split('-', 1)
                try:
                    res = self.tmdb(f'/{kind}/{tid}/recommendations').get('results', [])
                except Exception:
                    res = []
                hit = [[f'{kind}-{r["id"]}' for r in res[:20]], time.time()]
                self.recs[key] = hit
            for rank, k in enumerate(hit[0]):
                out[k] = out.get(k, 0) + weight * (1 - rank / 30)
        json.dump(self.recs, open(self.rec_file, 'w'))
        return out

    # ---- scoring ------------------------------------------------------------------------------

    @staticmethod
    def _text(d):
        kw = d.get('keywords', {})
        words = [k['name'] for k in kw.get('keywords', kw.get('results', []))]
        return ' ' + ' '.join(words).lower() + ' ' + (d.get('overview') or '').lower() + ' '

    def clusters(self, d):
        """{cluster: strength 0..1} for a TMDB details dict (with keywords appended)."""
        genres = {g['name'] for g in d.get('genres', [])}
        lang = d.get('original_language', '')
        text = self._text(d)
        out = {}
        for name, _w, gs, words, langs in CLUSTERS:
            s = 0.0
            if genres & gs:
                s += 0.45
            hits = sum(1 for w in words if w in text)
            s += min(0.4, 0.14 * hits)
            if langs and lang in langs:
                s += 0.45
            elif langs:
                s *= 0.3  # the Indian clusters are about Indian stories
            if s >= 0.3:
                out[name] = min(1.0, s)
        return out

    def score(self, d, key, seeds):
        """How well a title fits: clusters weighted by the profile's strengths, plus TMDB's
        "more like what you loved", the watchlist, and a little for Korean-only audio caution."""
        weights = {c[0]: c[1] for c in CLUSTERS}
        cl = self.clusters(d)
        s = sum(weights[c] * v for c, v in cl.items())
        s += min(1.2, seeds.get(key, 0))
        if key in self.watchlist:
            s += 1.5
        if d.get('original_language') == 'ko':
            s -= 0.15
        return s

    def is_tech(self, d):
        text = self._text(d)
        return sum(1 for w in TECH_WORDS if w in text) >= 2
