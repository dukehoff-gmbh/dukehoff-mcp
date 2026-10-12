#
# Copyright 2026 Dukehoff GmbH
# SPDX-License-Identifier: Apache-2.0
#

"""Writes the March 2026 data set of the billing platform (deterministic).

Usage: make-platform-data.py <platform dir>

120 customers, tariff changes, ~4,500 meter records with mixed units, redeliveries and ~45
corrections. The correction desk writes UTC ("Z"), meters write local time with their offset.
Every correction is written on a later day than the reading it replaces, except one of c-1187's
(storage, 2026-03-20): written 30 minutes after the reading, on the same day, which as text sorts
before the reading ("...T08:40:00Z" < "...T09:10:00+01:00"). Only a comparison of the written
timestamps as instants gets that one right.
"""
import os
import random
import sys
from datetime import date, datetime, timedelta

root = sys.argv[1]
rnd = random.Random(20260331)
out = os.path.join(root, "data", "2026-03")
os.makedirs(out, exist_ok=True)

COUNTRIES = ["DE"] * 10 + ["AT"] * 3 + ["FR"] * 2 + ["NL"] * 2 + ["CH"] * 2 + ["GB"] * 2
CURRENCY = {"CH": "CHF", "GB": "GBP"}
NAMES = ["Alder", "Birch", "Cedar", "Dogwood", "Elm", "Fir", "Ginkgo", "Hazel", "Ivy", "Juniper", "Kauri",
         "Larch", "Maple", "Nutmeg", "Oak", "Pine", "Quince", "Rowan", "Spruce", "Teak", "Umbrella", "Vine",
         "Willow", "Yew", "Zelkova"]
SUFFIX = ["Systems", "Labs", "Logistics", "Media", "Analytics", "Studio", "Health", "Retail", "Energy", "Works"]


def offset(country, day):
    # European summer time from 2026-03-29
    summer = day >= date(2026, 3, 29)
    if country == "GB":
        return "+01:00" if summer else "+00:00"
    return "+02:00" if summer else "+01:00"


customers = []
contracts = []
for n in range(1100, 1220):
    cid = f"c-{n}"
    country = rnd.choice(COUNTRIES)
    r = rnd.random()
    tariff = "STUDENT" if r < 0.12 else "STANDARD" if r < 0.55 else "BUSINESS" if r < 0.9 else "ENTERPRISE"
    student = tariff == "STUDENT"
    years = rnd.randint(0, 9)
    name = f"{rnd.choice(NAMES)} {rnd.choice(SUFFIX)}" if tariff in ("BUSINESS", "ENTERPRISE") else f"{rnd.choice(NAMES)} {n}"
    vat = f"{country}{rnd.randint(100000000, 999999999)}" if tariff in ("BUSINESS", "ENTERPRISE") else ""
    switch = None
    if tariff in ("BUSINESS", "ENTERPRISE") and rnd.random() < 0.15:
        switch = date(2026, 3, rnd.randint(2, 28))
    if cid == "c-1187":
        country, tariff, student, years, name = "CH", "BUSINESS", False, 4, "Rowan Analytics"
        vat = "CHE123456789"
        switch = date(2026, 3, 10)
    customers.append((cid, name, country, CURRENCY.get(country, "EUR"), vat, years, student))
    start = date(2026 - years, rnd.randint(1, 12), 1) if years else date(2025, rnd.randint(4, 12), 1)
    if switch:
        contracts.append((cid, "STANDARD", start))
        contracts.append((cid, tariff, switch))
    else:
        contracts.append((cid, tariff, start))

# usage
SCALE = {"STUDENT": 0.2, "STANDARD": 1, "BUSINESS": 4, "ENTERPRISE": 12}
UNITS = {"storage": [("GB", 1), ("TB", 0.001), ("MB", 1000)],
         "traffic": [("GB", 1), ("TB", 0.001), ("MB", 1000)],
         "compute": [("h", 1), ("min", 60), ("s", 3600)],
         "api": [("k", 1), ("calls", 1000)]}
BASE = {"storage": 40, "traffic": 120, "compute": 30, "api": 15}
days = [date(2026, 2, 26) + timedelta(d) for d in range(38)]  # 2026-02-26 .. 2026-04-04
records = []
seq = {}


def fmt(q):
    s = f"{q:.4f}".rstrip("0").rstrip(".")
    return s if s else "0"


def recorded(country, day, hour, minute):
    t = datetime(day.year, day.month, day.day, hour, minute)
    return t.strftime("%Y-%m-%dT%H:%M:00") + offset(country, day)


for (cid, name, country, cur, vat, years, student) in customers:
    tariff = [c for c in contracts if c[0] == cid][-1][1]
    count = rnd.randint(20, 45) if cid != "c-1187" else 130
    scale = SCALE[tariff] * (1.6 if cid == "c-1187" else 1)
    for _ in range(count):
        res = rnd.choice(["storage", "storage", "traffic", "compute", "api"])
        unit, factor = rnd.choice(UNITS[res])
        qty = round(BASE[res] * scale * rnd.uniform(0.2, 1.8), 1)
        day = rnd.choice(days)
        seq[cid] = seq.get(cid, 0) + 1
        rid = f"R-{cid[2:]}-{seq[cid]:04d}"
        rec = recorded(country, day + timedelta(1), rnd.randint(1, 5), rnd.choice([0, 10, 20, 30, 40, 50]))
        records.append([rid, cid, res, fmt(qty * factor), unit, day.isoformat(), rec, "reading"])

# the reading c-1187's bug hinges on: storage of 2026-03-20, written 2026-03-21 09:10 local
seq["c-1187"] += 1
special = f"R-1187-{seq['c-1187']:04d}"
records.append([special, "c-1187", "storage", "0.18", "TB", "2026-03-20", "2026-03-21T09:10:00+01:00", "reading"])

# corrections: written by the correction desk in UTC, on a later day than the reading
corrections = []
in_march = [r for r in records if r[5].startswith("2026-03") and r[0] != special]
for r in rnd.sample(in_march, 44):
    country = [c for c in customers if c[0] == r[1]][0][2]
    written = datetime.fromisoformat(r[6][:19]) + timedelta(days=rnd.randint(1, 6), hours=rnd.randint(0, 8))
    c = list(r)
    if rnd.random() < 0.25:
        c[5] = (date.fromisoformat(r[5]) + timedelta(days=rnd.choice([-1, 1]))).isoformat()
    c[3] = fmt(float(r[3]) * rnd.uniform(0.7, 1.1))
    c[6] = written.strftime("%Y-%m-%dT%H:%M:00") + "Z"
    c[7] = "correction"
    corrections.append(c)
corrections.append([special, "c-1187", "storage", "155", "GB", "2026-03-20", "2026-03-21T08:40:00Z", "correction"])

# redeliveries: identical records twice
redelivered = rnd.sample(records, 60)

# export order: by usage day, corrections after the readings of their day
allrecs = records + redelivered + corrections
allrecs.sort(key=lambda r: (r[5], r[7] == "correction", r[1], r[0]))
with open(os.path.join(out, "usage.csv"), "w") as f:
    f.write("reading_id;customer;resource;quantity;unit;usage_day;recorded_at;kind\n")
    for r in allrecs:
        f.write(";".join(r) + "\n")

with open(os.path.join(out, "customers.csv"), "w") as f:
    f.write("id;name;country;currency;vat_id;years_active;student\n")
    for c in customers:
        f.write(";".join([c[0], c[1], c[2], c[3], c[4], str(c[5]), str(c[6]).lower()]) + "\n")

with open(os.path.join(out, "contracts.csv"), "w") as f:
    f.write("customer;tariff;from\n")
    for c in contracts:
        f.write(f"{c[0]};{c[1]};{c[2].isoformat()}\n")

with open(os.path.join(out, "rates.csv"), "w") as f:
    f.write("date;currency;rate\n")
    chf, gbp = 0.9412, 0.8371
    for d in range(1, 32):
        day = date(2026, 3, d)
        if day.weekday() >= 5:
            continue
        chf += rnd.uniform(-0.004, 0.004)
        gbp += rnd.uniform(-0.003, 0.003)
        f.write(f"{day.isoformat()};CHF;{chf:.4f}\n{day.isoformat()};GBP;{gbp:.4f}\n")

with open(os.path.join(out, "tax-rates.csv"), "w") as f:
    f.write("country;rate;eu\nDE;19;true\nAT;20;true\nFR;20;true\nNL;21;true\nCH;8.1;false\nGB;20;false\n")

with open(os.path.join(out, "credits.csv"), "w") as f:
    f.write("id;customer;amount;expires\n")
    n = 0
    for c in rnd.sample(customers, 22) + [[x for x in customers if x[0] == "c-1187"][0]]:
        for _ in range(rnd.randint(1, 2)):
            n += 1
            expires = date(2026, rnd.choice([2, 3, 4, 6, 12]), 28)
            f.write(f"CR-{n:03d};{c[0]};{rnd.choice(['10.00', '25.00', '50.00', '100.00', '250.00'])};{expires.isoformat()}\n")

print(f"{len(customers)} customers, {len(allrecs)} usage records ({len(corrections)} corrections), written to {out}")
