#!/usr/bin/env python3
"""Export reference ("golden") data from sim.py for the Kotlin `rules` tests.

Usage:
    python3 sim/export_golden.py [--csv path/to/ufc-master.csv]

Without --csv the dataset is downloaded to sim/data/ (git-ignored).
Writes rules/src/test/resources/golden/*.json. Every value is produced by calling
sim.py's own code (Market, Player, run), never by a re-implementation here.

Money in the JSON is in whole currency units (1 M = 1 000 000), as in the backend.
"""
import argparse
import collections
import datetime as dt
import hashlib
import json
import os
import random
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, 'rules', 'src', 'test', 'resources', 'golden')
DATA_URL = 'https://raw.githubusercontent.com/shortlikeafox/ultimate_ufc_dataset/master/ufc-master.csv'
N = 500
UNIT = 1_000_000

# Rules v2. The game's rule set is 'v2_free2_extra3' (report "v2, 2 nakupy zdarma, kazdy dalsi +3 % ceny").
V2 = dict(hold_days=42, max_trades_week=2, yield_mode=True, price_at_1500=6.0, short_fix=True, no_both=True)
SCENARIOS = {
    'v2_limit2': V2,
    'v2_free2_extra3': dict(V2, max_trades_week=99, free_buys=2, extra_fee=0.03),
}


def units(m):
    """Millions (float) -> whole units, rounded half-even like the backend."""
    return round(m * UNIT)


def stub_market(sim, p, fights=()):
    mk = sim.Market.__new__(sim.Market)
    mk.p = p
    mk.elo = collections.defaultdict(lambda: p['elo_start'])
    mk.last = {}
    mk.mult = collections.defaultdict(lambda: 1.0)
    mk.pre = []
    mk.fights = list(fights)
    mk.bonus = set()
    mk.events = []
    mk.delisted = set()
    mk.ann = {}
    return mk


def fight(date, win='R', finish=False, rnd=3, title=False, main=False, nc=False, ro=2.0, bo=2.0):
    return dict(date=date, R='A', B='B', Ro=ro, Bo=bo, win=win, finish=finish, dec=not finish,
                rnd=rnd, title=title, main=main, nc=nc)


def write(name, payload):
    path = os.path.join(OUT, name)
    with open(path, 'w') as fh:
        json.dump(payload, fh, ensure_ascii=False, indent=None, separators=(',', ':'))
        fh.write('\n')
    print('wrote', os.path.relpath(path, ROOT), os.path.getsize(path), 'B')


def gen_functions(sim, p):
    rnd = random.Random(2026)
    elo = lambda: round(rnd.uniform(1000, 2200), 3)  # noqa: E731
    odds = lambda: round(rnd.uniform(1.05, 8.0), 2)  # noqa: E731
    out = {}

    out['elo_expected'] = [dict(a=a, b=b, out=sim.elo_exp(a, b)) for a, b in ((elo(), elo()) for _ in range(N))]

    cases = []
    for _ in range(N):
        a, b = elo(), elo()
        outcome = rnd.choice(['A_WIN', 'B_WIN', 'DRAW', 'NO_CONTEST'])
        fin = rnd.random() < 0.5
        mk = stub_market(sim, p)
        mk.elo['A'], mk.elo['B'] = a, b
        win = {'A_WIN': 'R', 'B_WIN': 'B'}.get(outcome)
        mk.apply_elo(fight(dt.date(2025, 1, 1), win=win, finish=fin, nc=outcome == 'NO_CONTEST'))
        cases.append(dict(a=a, b=b, outcome=outcome, finish=fin, outA=mk.elo['A'], outB=mk.elo['B']))
    out['elo_update'] = cases

    cases = []
    for _ in range(N):
        e = round(rnd.uniform(400, 2600), 3)
        mk = stub_market(sim, p)
        mk.elo['A'] = e
        cases.append(dict(elo=e, out=units(mk.base_price('A'))))
    out['base_price'] = cases

    cases = []
    today = dt.date(2025, 3, 4)
    for _ in range(N):
        a, b = elo(), elo()
        kind = rnd.choice(['NORMAL', 'MAIN', 'TITLE'])
        date = today + dt.timedelta(weeks=p['ann_weeks'], days=-rnd.randint(0, 6))
        f = fight(date, title=kind == 'TITLE', main=kind == 'MAIN')
        mk = stub_market(sim, p, [f])
        mk.elo['A'], mk.elo['B'] = a, b
        mk.weekly_update(today, today - dt.timedelta(days=7))
        cases.append(dict(type=kind, eloFighter=a, eloOpponent=b, out=mk.mult['A'] - 1))
        cases.append(dict(type=kind, eloFighter=b, eloOpponent=a, out=mk.mult['B'] - 1))
    out['announcement_bump'] = cases[:N]

    cases = []
    for _ in range(N):
        a, b, ro, bo = elo(), elo(), odds(), odds()
        mk = stub_market(sim, p)
        mk.elo['A'], mk.elo['B'] = a, b
        mk.fight_week_drift(fight(dt.date(2025, 1, 1), ro=ro, bo=bo))
        cases.append(dict(oddsFighter=ro, oddsOpponent=bo, eloFighter=a, eloOpponent=b, out=mk.mult['A']))
        cases.append(dict(oddsFighter=bo, oddsOpponent=ro, eloFighter=b, eloOpponent=a, out=mk.mult['B']))
    out['odds_drift'] = cases[:N]

    cases = []
    for _ in range(N):
        days = rnd.randint(0, 700)
        announced = rnd.random() < 0.3
        last = today - dt.timedelta(days=days)
        fights = [dict(fight(today + dt.timedelta(days=10)), R='X', B='Y')] if announced else []
        mk = stub_market(sim, p, fights)
        mk.last['X'] = last
        mk.weekly_update(today, today - dt.timedelta(days=7))
        cases.append(dict(weeksSinceFight=days / 7, hasAnnounced=announced,
                          factor=mk.mult['X'], delisted='X' in mk.delisted))
    out['inactivity'] = cases

    cases = []
    for _ in range(N):
        win = rnd.choice(['R', 'R', 'B', None])
        nc = win is None or rnd.random() < 0.05
        fin = rnd.random() < 0.5
        r = rnd.randint(1, 5) if fin else 3
        title = rnd.random() < 0.1
        own = None if rnd.random() < 0.05 else odds()
        f = fight(dt.date(2025, 1, 1), win=win, finish=fin, rnd=r, title=title, nc=nc, ro=own, bo=odds())
        mk = stub_market(sim, p, [f])
        night = rnd.random() < 0.15
        if night:
            mk.bonus = {0}
        captain = rnd.random() < 0.2
        price = rnd.randint(500_000, 30_000_000)
        result = 'NO_CONTEST' if nc else 'DRAW' if win is None else 'WIN' if win == 'R' else 'LOSS'
        d = mk.dividend(f, 'R', captain, price / UNIT)
        cases.append(dict(result=result, finish=fin, round=r, odds=own, price=price, captain=captain,
                          fightNight=night, title=title, out=units(d)))
    out['dividend_winner'] = cases

    cases = []
    for _ in range(N):
        win = rnd.choice(['R', 'B', None])
        nc = win is None
        ro, bo = odds(), odds()
        f = fight(dt.date(2025, 1, 1), win=win, nc=nc, ro=ro, bo=bo)
        mk = stub_market(sim, p, [f])
        result = 'NO_CONTEST' if nc else 'WIN' if win == 'R' else 'LOSS'
        cases.append(dict(result=result, oddsFighter=ro, oddsOpponent=bo, out=units(mk.short_payoff(f, 'R'))))
    out['dividend_short'] = cases

    cases = []
    for _ in range(N):
        price = rnd.randint(500_000, 30_000_000)
        week_buys = rnd.randint(0, 5)
        first_week = rnd.random() < 0.2
        day = dt.date(2025, 1, 7) if first_week else dt.date(2025, 3, 4)
        mk = stub_market(sim, p)
        mk.price = lambda n, t, pr=price: pr / UNIT
        pl = sim.Player('golden', mk, None)
        pl.cash = 1e9
        pl.week_buys = week_buys
        pl.buy('X', day)
        total = 1e9 - pl.cash
        cases.append(dict(price=price, buysThisWeek=week_buys, firstWeek=first_week,
                          extraFee=units(pl.penalty), fee=units(pl.fees - pl.penalty), total=units(total)))
    out['trade_buy'] = cases

    cases = []
    for _ in range(N):
        price = rnd.randint(500_000, 30_000_000)
        mk = stub_market(sim, p)
        mk.price = lambda n, t, pr=price: pr / UNIT
        pl = sim.Player('golden', mk, None)
        pl.cash = 0.0
        pl.hold['X'] = dt.date(2025, 1, 1)
        pl.sell('X', dt.date(2025, 3, 4))
        cases.append(dict(price=price, fee=units(pl.fees), net=units(pl.cash)))
    out['trade_sell'] = cases

    cases = []
    for _ in range(N):
        fdate = dt.date(2025, 6, 1) + dt.timedelta(days=rnd.randint(0, 180))
        bought = fdate - dt.timedelta(days=rnd.randint(0, 90))
        held = (fdate - bought).days
        cases.append(dict(boughtOn=bought.isoformat(), fightOn=fdate.isoformat(), out=held >= p['hold_days']))
    out['eligibility'] = cases
    return out


def fixture_fights(sim, fights, p):
    mk = sim.Market(fights, p)
    rows = []
    for f in fights:
        bonus = f['date'].year == 2025 and mk.fights.index(f) in mk.bonus
        rows.append([f['date'].isoformat(), f['R'], f['B'], f['Ro'], f['Bo'], f['win'], f['finish'],
                     f['rnd'], f['title'], f['main'], f['nc'], bonus])
    return rows


def season(sim, fights):
    res = {}
    for key, over in SCENARIOS.items():
        mk, players, end = sim.run(fights, sim.STRATS, over, seeds=200)
        res[key] = dict(
            params=over,
            players=[dict(strategy=pl.name, index=i, worth=pl.worth(end), dividends=pl.div, fees=pl.fees,
                          trades=pl.trades)
                     for i, pl in enumerate(players)],
            eloEnd={n: e for n, e in mk.elo.items()},
            delisted=sorted(mk.delisted),
        )
        print('scenario', key, 'players', len(players))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--csv')
    args = ap.parse_args()
    csv_path = args.csv
    if not csv_path:
        csv_path = os.path.join(HERE, 'data', 'ufc-master.csv')
        if not os.path.exists(csv_path):
            os.makedirs(os.path.dirname(csv_path), exist_ok=True)
            print('downloading', DATA_URL)
            urllib.request.urlretrieve(DATA_URL, csv_path)
    sha = hashlib.sha256(open(csv_path, 'rb').read()).hexdigest()

    sys.argv = ['sim.py', csv_path]
    sys.path.insert(0, HERE)
    import sim  # noqa: E402  (reads sys.argv at import time)

    os.makedirs(OUT, exist_ok=True)
    p = dict(sim.P, **SCENARIOS['v2_free2_extra3'])
    meta = dict(source='sim/sim.py', dataset=DATA_URL, datasetSha256=sha, rules='v2', params=p,
                generated=dt.date.today().isoformat())
    write('functions.json', dict(meta=meta, cases=gen_functions(sim, p)))

    fights = sim.load()
    write('fights.json', dict(meta=dict(meta, columns=['date', 'R', 'B', 'Ro', 'Bo', 'win', 'finish', 'rnd',
                                                        'title', 'main', 'nc', 'bonus']),
                              rows=fixture_fights(sim, fights, p)))
    write('season2025.json', dict(meta=meta, scenarios=season(sim, fights)))


if __name__ == '__main__':
    main()
