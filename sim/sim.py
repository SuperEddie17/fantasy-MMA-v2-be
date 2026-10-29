"""Simulace Fight Exchange na sezone UFC 2025.

Data: shortlikeafox/ultimate_ufc_dataset (ufc-master.csv), zapasy 2010-2026 s kurzy.
Elo se zahriva na zapasech 2010-2024, simulace bezi 1. 1. 2025 - 31. 12. 2025.
"""
import csv, math, random, sys, statistics, collections, datetime as dt

DATA = sys.argv[1]
M = 1.0  # 1 milion

# ---------- parametry navrhu (vychozi = shrnuti) ----------
P = dict(
    elo_start=1500, elo_k=40, price_at_1500=3.0, price_scale=200, price_min=0.5,
    ann_weeks=6, ann_normal=0.02, ann_main=0.05, ann_title=0.10,
    inactive_weeks=26, decay=0.01, delist_weeks=78,
    odds_drift=0.05,
    base_dec=1.0, base_fin=1.5, odds_cap=3.5,
    b_r1=0.5, b_bonus=1.0, b_title=3.0, captain=2.0,
    fee=0.01, budget=100.0, min_roster=8, max_roster=15, max_shorts=3,
    # opravy (vypnute = puvodni navrh)
    hold_days=0,          # dividenda jen kdyz drzis zapasnika aspon N dni pred zapasem
    yield_mode=False,     # dividenda = cena * procento misto pevnych milionu
    yield_scale=0.25,     # pri yield_mode: dividenda = cena * yield_scale * (zaklad*nasobek)/1.2
    max_trades_week=99,   # limit nakupu za tyden
    short_fix=False,      # short: platba pri vyhre bez stropu, short jen do kurzu odds_cap, drzeni jako u nakupu
    no_both=False,
    free_buys=99,         # nakupy zdarma tydne; kazdy dalsi plati prirazku
    extra_fee=0.0,        # prirazka za nakup navic (podil ceny)
    extra_escalate=False, # True: 3. nakup 1x, 4. 2x, 5. 3x prirazka ...        # zakaz drzet oba soupere z jednoho ohlaseneho zapasu
)


def amer_to_dec(a):
    a = float(a)
    return 1 + a / 100 if a > 0 else 1 + 100 / -a


def load():
    rows = list(csv.DictReader(open(DATA)))
    fights = []
    for x in rows:
        d = dt.date.fromisoformat(x['date'])
        if d.year > 2025:
            continue
        try:
            ro, bo = amer_to_dec(x['R_odds']), amer_to_dec(x['B_odds'])
        except (ValueError, ZeroDivisionError):
            ro = bo = None
        w = {'Red': 'R', 'Blue': 'B'}.get(x['Winner'])
        fin = x['finish']
        fights.append(dict(
            date=d, R=x['R_fighter'], B=x['B_fighter'], Ro=ro, Bo=bo, win=w,
            finish=fin in ('KO/TKO', 'SUB'), dec=fin in ('U-DEC', 'S-DEC', 'M-DEC'),
            rnd=int(float(x['finish_round'] or 0)), title=x['title_bout'] == 'True',
            main=x['no_of_rounds'] == '5', nc=fin in ('CNC', 'Overturned') or w is None,
        ))
    fights.sort(key=lambda f: f['date'])
    return fights


def bonuses(fights2025, seed=7):
    """Data nemaji bonusy vecera. Aproximace: 4 bonusy na event, nahodne mezi ukoncenimi
    (preferuje 1. kolo), jinak mezi vitezi."""
    rnd = random.Random(seed)
    by_ev = collections.defaultdict(list)
    for i, f in enumerate(fights2025):
        by_ev[f['date']].append(i)
    out = set()
    for d, idx in by_ev.items():
        fin = [i for i in idx if fights2025[i]['finish'] and fights2025[i]['win']]
        fin.sort(key=lambda i: (fights2025[i]['rnd'], rnd.random()))
        pick = fin[:4]
        rest = [i for i in idx if i not in pick and fights2025[i]['win']]
        rnd.shuffle(rest)
        out.update(pick + rest[:4 - len(pick)])
    return out


def elo_exp(a, b):
    return 1 / (1 + 10 ** ((b - a) / 400))


class Market:
    def __init__(self, fights, p):
        self.p = p
        self.elo = collections.defaultdict(lambda: p['elo_start'])
        self.last = {}
        self.mult = collections.defaultdict(lambda: 1.0)
        self.pre = [f for f in fights if f['date'].year < 2025]
        self.fights = [f for f in fights if f['date'].year == 2025]
        for f in self.pre:
            self.apply_elo(f)
        self.bonus = bonuses(self.fights)
        self.events = sorted(set(f['date'] for f in self.fights))
        self.delisted = set()
        self.ann = {}

    def apply_elo(self, f):
        a, b = self.elo[f['R']], self.elo[f['B']]
        self.last[f['R']] = self.last[f['B']] = f['date']
        if f['nc']:
            return
        e = elo_exp(a, b)
        s = 1.0 if f['win'] == 'R' else 0.0 if f['win'] == 'B' else 0.5
        k = self.p['elo_k'] * (1.25 if f['finish'] else 1.0)
        self.elo[f['R']] = a + k * (s - e)
        self.elo[f['B']] = b - k * (s - e)

    def base_price(self, name):
        p = self.p
        return max(p['price_min'], p['price_at_1500'] * math.exp((self.elo[name] - 1500) / p['price_scale']))

    def price(self, name, today):
        return self.base_price(name) * self.mult[name]

    def upcoming(self, today):
        """Zapasy ohlasene k dnesku (kurzy se objevi ann_weeks pred zapasem)."""
        lo, hi = today, today + dt.timedelta(weeks=self.p['ann_weeks'])
        return [f for f in self.fights if lo <= f['date'] < hi]

    def weekly_update(self, today, prev):
        """Posun multiplikatoru: ohlaseni, neaktivita."""
        p = self.p
        ann = {}
        for f in self.upcoming(today):
            for s, o in (('R', 'B'), ('B', 'R')):
                ann[f[s]] = (f, f[o])
        for f in self.fights:
            if prev < f['date'] - dt.timedelta(weeks=p['ann_weeks']) <= today:
                for s, o in (('R', 'B'), ('B', 'R')):
                    bump = p['ann_title'] if f['title'] else p['ann_main'] if f['main'] else p['ann_normal']
                    diff = self.elo[f[o]] - self.elo[f[s]]
                    bump += 0.03 if diff > 200 else 0.02 if diff > 100 else 0.01 if diff > 0 else 0
                    self.mult[f[s]] *= 1 + bump
        for name, last in list(self.last.items()):
            weeks = (today - last).days / 7
            if name in ann:
                continue
            if weeks > p['delist_weeks']:
                self.delisted.add(name)
            elif weeks > p['inactive_weeks']:
                self.mult[name] *= 1 - p['decay']
        return ann

    def fight_week_drift(self, f):
        p = self.p
        if not f['Ro']:
            return
        pr = (1 / f['Ro']) / (1 / f['Ro'] + 1 / f['Bo'])
        pe = elo_exp(self.elo[f['R']], self.elo[f['B']])
        self.mult[f['R']] *= 1 + p['odds_drift'] * (pr - pe)
        self.mult[f['B']] *= 1 - p['odds_drift'] * (pr - pe)

    def dividend(self, f, side, captain=False, price=None):
        p = self.p
        if f['nc'] or f['win'] is None:
            return 0.0
        odds = f['Ro'] if side == 'R' else f['Bo']
        odds = min(odds or 2.0, p['odds_cap'])
        if f['win'] != side:
            return 0.0
        base = p['base_fin'] if f['finish'] else p['base_dec']
        core = base * odds
        if p['yield_mode']:
            core = price * p['yield_scale'] * core / 1.2
        bon = 0.0
        if f['finish'] and f['rnd'] == 1:
            bon += p['b_r1']
        if self.fights.index(f) in self.bonus:
            bon += p['b_bonus']
        if f['title']:
            bon += p['b_title']
        return (core + bon) * (p['captain'] if captain else 1)

    def short_payoff(self, f, side):
        """Short: pri prohre dostane 1 M x kurz na prohru, pri vyhre plati 1 M x kurz na vyhru."""
        p = self.p
        if f['nc'] or f['win'] is None:
            return 0.0
        own = f['Ro'] if side == 'R' else f['Bo']
        if not p['short_fix']:
            own = min(own, p['odds_cap'])
        opp = min(f['Bo'] if side == 'R' else f['Ro'], p['odds_cap'])
        return opp if f['win'] != side else -own


# ---------- portfolio ----------
class Player:
    def __init__(self, name, mk, strat, seed=0):
        self.name, self.mk, self.strat = name, mk, strat
        self.cash = mk.p['budget']
        self.hold = {}   # jmeno -> datum nakupu
        self.short = {}  # jmeno -> datum otevreni
        self.rnd = random.Random(seed)
        self.div = 0.0
        self.fees = 0.0
        self.trades = 0
        self.captain = None
        self.week_buys = 0
        self.penalty = 0.0

    def buy(self, n, today):
        p = self.mk.p
        pr = self.mk.price(n, today)
        extra = max(0, self.week_buys - p['free_buys'] + 1)
        if today <= dt.date(2025, 1, 7):
            extra = 0  # sestaveni soupisky na zacatku je zdarma
        if extra and p['extra_escalate']:
            pen = pr * p['extra_fee'] * extra
        else:
            pen = pr * p['extra_fee'] * (1 if extra else 0)
        cost = pr * (1 + self.mk.p['fee']) + pen
        if self.mk.p['no_both'] and self.mk.ann.get(n) and self.mk.ann[n][1] in self.hold:
            return False
        if n in self.hold or cost > self.cash or len(self.hold) >= self.mk.p['max_roster'] or n in self.mk.delisted:
            return False
        self.cash -= cost
        self.fees += pr * self.mk.p['fee'] + pen
        self.penalty += pen
        self.week_buys += 1
        self.hold[n] = today
        self.trades += 1
        return True

    def sell(self, n, today):
        pr = self.mk.price(n, today)
        self.cash += pr * (1 - self.mk.p['fee'])
        self.fees += pr * self.mk.p['fee']
        del self.hold[n]
        self.trades += 1

    def open_short(self, n, today):
        if len(self.short) >= self.mk.p['max_shorts'] or n in self.short or n in self.hold:
            return
        if self.mk.p['short_fix'] and n in self.mk.ann:
            f, _ = self.mk.ann[n]
            o = f['Ro'] if f['R'] == n else f['Bo']
            if o and o > self.mk.p['odds_cap']:
                return
        pr = self.mk.price(n, today)
        self.cash += pr * (1 - self.mk.p['fee'])
        self.fees += pr * self.mk.p['fee']
        self.short[n] = today

    def close_short(self, n, today):
        pr = self.mk.price(n, today)
        self.cash -= pr * (1 + self.mk.p['fee'])
        self.fees += pr * self.mk.p['fee']
        del self.short[n]

    def worth(self, today):
        return (self.cash + sum(self.mk.price(n, today) for n in self.hold)
                - sum(self.mk.price(n, today) for n in self.short))


def run(fights, strategies, p_over=None, seeds=1):
    p = dict(P, **(p_over or {}))
    mk = Market(fights, p)
    players = []
    for name, strat in strategies:
        for s in range(seeds if name.startswith('nahodne') else 1):
            players.append(Player(name, mk, strat, seed=s))
    today = dt.date(2024, 12, 31)
    prev = today - dt.timedelta(days=7)
    # tydenni kroky: kazde utery pred eventem
    end = dt.date(2025, 12, 31)
    while today <= end:
        ann = mk.weekly_update(today, prev)
        mk.ann = ann
        week_fights = [f for f in mk.fights if today <= f['date'] < today + dt.timedelta(days=7)]
        for f in week_fights:
            mk.fight_week_drift(f)
        for pl in players:
            # vyrazene z trhu: vyplaceni
            for n in [n for n in pl.hold if n in mk.delisted]:
                pl.sell(n, today)
            pl.week_buys = 0
            pl.strat(pl, today, ann, week_fights)
        # eventy tohoto tydne
        for f in week_fights:
            for pl in players:
                active = len(pl.hold) >= p['min_roster']
                for side in 'RB':
                    n = f[side]
                    if n in pl.hold and active:
                        held = (f['date'] - pl.hold[n]).days
                        if held >= p['hold_days']:
                            d = mk.dividend(f, side, pl.captain == n, mk.price(n, today))
                            pl.cash += d
                            pl.div += d
                    if n in pl.short and (not p['short_fix'] or (f['date'] - pl.short[n]).days >= p['hold_days']):
                        d = mk.short_payoff(f, side)
                        pl.cash += d
                        pl.div += d
            mk.apply_elo(f)
            for side in 'RB':
                mk.mult[f[side]] = 1.0  # po zapase cenu urcuje Elo
        prev, today = today, today + dt.timedelta(days=7)
    return mk, players, end


# ---------- strategie ----------
def active_pool(mk, today, max_weeks=52):
    return [n for n, d in mk.last.items() if (today - d).days < max_weeks * 7 and n not in mk.delisted]


def exp_div(mk, f, side):
    o = f['Ro'] if side == 'R' else f['Bo']
    if not o:
        return 0
    pr = (1 / o) / (1 / f['Ro'] + 1 / f['Bo'])
    return pr * (1.2 * min(o, mk.p['odds_cap']) + 0.4)


def fill_hold(pl, today, pool, k):
    for n in pool:
        if len(pl.hold) >= k:
            break
        pl.buy(n, today)


def s_cash(pl, today, ann, wf):
    pass


def s_random_hold(pl, today, ann, wf):
    mk = pl.mk
    if len(pl.hold) < 12:
        pool = active_pool(mk, today)
        pl.rnd.shuffle(pool)
        pool = [n for n in pool if mk.price(n, today) < pl.cash / max(1, 12 - len(pl.hold))]
        fill_hold(pl, today, pool, 12)
    pl.captain = None


def s_bluechip(pl, today, ann, wf):
    mk = pl.mk
    if len(pl.hold) < 8:
        pool = sorted(active_pool(mk, today), key=lambda n: -mk.price(n, today))
        pool = [n for n in pool if mk.price(n, today) < pl.cash / max(1, 8 - len(pl.hold))]
        fill_hold(pl, today, pool, 8)
    pick_captain(pl, wf)


def s_active_hold(pl, today, ann, wf):
    mk = pl.mk
    if len(pl.hold) < 12:
        cnt = collections.Counter()
        for f in mk.pre:
            if f['date'].year == 2024:
                cnt[f['R']] += 1
                cnt[f['B']] += 1
        pool = [n for n, _ in cnt.most_common() if n not in mk.delisted]
        pool = [n for n in pool if mk.price(n, today) < pl.cash / max(1, 12 - len(pl.hold))]
        fill_hold(pl, today, pool, 12)
    pick_captain(pl, wf)


def pick_captain(pl, wf):
    best, bv = None, -1
    for f in wf:
        for s in 'RB':
            if f[s] in pl.hold and exp_div(pl.mk, f, s) > bv:
                best, bv = f[s], exp_div(pl.mk, f, s)
    pl.captain = best


def rent(pl, today, wf, key, keep_min=8):
    """Prodej vse, co tento tyden nezapasi, a kup zapasniky tohoto tydne podle klice."""
    mk = pl.mk
    if not wf:
        return
    fighting = {f[s]: (f, s) for f in wf for s in 'RB' if f['Ro']}
    sellable = [n for n in pl.hold if n not in fighting][:mk.p['max_trades_week']]
    for n in sellable:
        pl.sell(n, today)
    cands = sorted(fighting, key=lambda n: key(mk, *fighting[n], today))
    buys = 0
    for n in cands:
        if len(pl.hold) >= mk.p['max_roster'] or buys >= mk.p['max_trades_week']:
            break
        if n in pl.hold:
            continue
        # nech hotovost na dokupeni do minima
        if pl.buy(n, today):
            buys += 1
    pick_captain(pl, wf)


def s_announced(pl, today, ann, wf):
    """Drz 15 zapasniku s ohlasenym zapasem (kup hned pri ohlaseni), po zapase prodej."""
    mk = pl.mk
    lim = mk.p['max_trades_week']
    out = [n for n in pl.hold if n not in ann][:lim]
    for n in out:
        pl.sell(n, today)
    cands = sorted((n for n in ann if n not in pl.hold),
                   key=lambda n: -exp_div(mk, ann[n][0], 'R' if ann[n][0]['R'] == n else 'B') / mk.price(n, today))
    # nejdriv ty, co zapasi nejpozdeji (stihnou drzeni)
    cands.sort(key=lambda n: -(ann[n][0]['date'] - today).days)
    buys = 0
    for n in cands:
        if buys >= lim or len(pl.hold) >= mk.p['max_roster']:
            break
        if pl.buy(n, today):
            buys += 1
    pick_captain(pl, wf)


def s_due(pl, today, ann, wf, shorts=False):
    """Predvidat ohlaseni: kupovat zapasniky 10-20 tydnu po poslednim zapase (brzy dostanou dalsi),
    po zapase prodat. Max 2 nakupy tydne."""
    mk = pl.mk
    lim = min(2, mk.p['max_trades_week'])
    done = [n for n in pl.hold if n not in ann and (today - mk.last.get(n, today)).days < 8
            and (today - pl.hold[n]).days > 7]
    for n in done[:lim]:
        pl.sell(n, today)
    due = [n for n, d in mk.last.items() if 70 <= (today - d).days <= 140 and n not in mk.delisted and n not in ann]
    due.sort(key=lambda n: mk.price(n, today))
    buys = 0
    for n in due:
        if buys >= lim or len(pl.hold) >= mk.p['max_roster']:
            break
        if pl.buy(n, today):
            buys += 1
    if shorts:
        for n in [n for n in pl.short if n not in ann and (today - mk.last.get(n, today)).days < 8]:
            pl.close_short(n, today)
        weak = sorted((n for n in due if n not in pl.hold), key=lambda n: mk.elo[n])
        for n in weak[:3]:
            pl.open_short(n, today)
    pick_captain(pl, wf)


def s_due_short(pl, today, ann, wf):
    s_due(pl, today, ann, wf, shorts=True)


def s_rent_yield(pl, today, ann, wf):
    rent(pl, today, wf, lambda mk, f, s, t: -exp_div(mk, f, s) / mk.price(f[s], t))


def s_rent_fav(pl, today, ann, wf):
    rent(pl, today, wf, lambda mk, f, s, t: (f['Ro'] if s == 'R' else f['Bo']))


def s_rent_arb(pl, today, ann, wf):
    def key(mk, f, s, t):
        pr = (1 / f['Ro']) / (1 / f['Ro'] + 1 / f['Bo'])
        pe = elo_exp(mk.elo[f['R']], mk.elo[f['B']])
        edge = pr - pe if s == 'R' else pe - pr
        return -edge
    rent(pl, today, wf, key)


def s_rent_yield_short(pl, today, ann, wf):
    mk = pl.mk
    for n in list(pl.short):
        pl.close_short(n, today)
    s_rent_yield(pl, today, ann, wf)
    # short na zapasniky, ktere Elo precenuje vuci kurzum (trh ceka prohru)
    cands = []
    for f in wf:
        if not f['Ro']:
            continue
        pr = (1 / f['Ro']) / (1 / f['Ro'] + 1 / f['Bo'])
        pe = elo_exp(mk.elo[f['R']], mk.elo[f['B']])
        cands.append((pr - pe, f['R']))
        cands.append((pe - pr, f['B']))
    for e, n in sorted(cands)[:3]:
        if n not in pl.hold:
            pl.open_short(n, today)


STRATS = [
    ('hotovost (nic nedelat)', s_cash),
    ('nahodne portfolio 12, drzet', s_random_hold),
    ('hvezdy 8, drzet', s_bluechip),
    ('nejaktivnejsi 2024, drzet', s_active_hold),
    ('drzet jen ohlasene zapasniky', s_announced),
    ('predvidat ohlaseni (10-20 tydnu po zapase)', s_due),
    ('predvidat ohlaseni + 3 shorty', s_due_short),
    ('pronajem: levni zapasnici tydne', s_rent_yield),
    ('pronajem: favorite tydne', s_rent_fav),
    ('pronajem: Elo vs kurzy', s_rent_arb),
    ('pronajem levnych + 3 shorty', s_rent_yield_short),
]


def report(label, p_over=None, seeds=200):
    mk, players, end = run(FIGHTS, STRATS, p_over, seeds)
    res = collections.defaultdict(list)
    for pl in players:
        res[pl.name].append((pl.worth(end), pl.div, pl.fees, pl.trades))
    print(f'\n### {label}')
    print('| strategie | cisté jmeni (M) | z toho dividendy | poplatky | obchody |')
    print('|---|---|---|---|---|')
    for name, _ in STRATS:
        v = res[name]
        w = [x[0] for x in v]
        if len(v) > 1:
            q = sorted(w)
            ws = f'{statistics.median(w):.0f} (5–95 %: {q[len(q)//20]:.0f}–{q[-len(q)//20]:.0f})'
        else:
            ws = f'{w[0]:.0f}'
        print(f'| {name} | {ws} | {statistics.mean(x[1] for x in v):.0f} | '
              f'{statistics.mean(x[2] for x in v):.0f} | {statistics.mean(x[3] for x in v):.0f} |')
    return mk


if __name__ == '__main__':
    FIGHTS = load()
    mk = report('Puvodni navrh')
    prices = sorted((mk.base_price(n) for n in active_pool(mk, dt.date(2025, 12, 31))), reverse=True)
    print('\nceny na konci 2025: top', [round(x, 1) for x in prices[:5]], 'median', round(statistics.median(prices), 1),
          'min', round(prices[-1], 1), 'pocet', len(prices))
    V2 = dict(hold_days=42, max_trades_week=2, yield_mode=True, price_at_1500=6.0, short_fix=True, no_both=True)
    report('v2 (limit 2 nakupy)', V2)
    for fee in (0.02, 0.03, 0.05):
        report(f'v2, 2 nakupy zdarma, kazdy dalsi +{fee:.0%} ceny, prvni tyden zdarma', dict(V2, max_trades_week=99, free_buys=2, extra_fee=fee))
    report('v2, 2 nakupy zdarma, stoupajici prirazka 2 %, 4 %, 6 % ..., prvni tyden zdarma', dict(V2, max_trades_week=99, free_buys=2, extra_fee=0.02, extra_escalate=True))
