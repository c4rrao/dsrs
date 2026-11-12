#!/usr/bin/env python3
"""
Generate a synthetic corpus of contributions. Each record is one contributor's submission for
one transaction; most transactions get several corroborating contributors. No network calls;
load_seed_data.py submits the result.

Usage:
    python3 generate_seed_data.py --count 500 --pool-size 30 --out seed_data.json
"""

import argparse
import json
import random
from datetime import date, timedelta

# city -> (price per m² low, high, is_coastal); plausible relative tiers, not market data
CITIES = {
    "Lisbon":     (4500, 6500, False),
    "Cascais":    (4500, 6500, True),
    "Porto":      (3000, 4200, False),
    "Oeiras":     (3800, 5000, False),
    "Sintra":     (2200, 3200, False),
    "Braga":      (1800, 2500, False),
    "Coimbra":    (1700, 2400, False),
    "Aveiro":     (1800, 2600, True),
    "Faro":       (2200, 3000, True),
    "Setubal":    (1600, 2200, True),
    "Evora":      (1400, 2000, False),
    "Viseu":      (1200, 1700, False),
    "Guimaraes":  (1300, 1800, False),
    "Leiria":     (1400, 1900, False),
    "Portimao":   (2200, 3000, True),
}

STREET_PREFIXES = ["Rua", "Avenida", "Travessa", "Praca", "Alameda", "Largo"]
STREET_NAMES = [
    "das Flores", "Central", "do Comercio", "da Liberdade", "de Santo Antonio",
    "dos Combatentes", "da Republica", "do Sol", "da Paz", "das Acacias",
    "do Castelo", "da Fonte", "dos Pescadores", "da Escola", "do Rio",
    "Nova", "da Igreja", "do Mercado", "das Oliveiras", "da Estacao",
]

GENERIC_TAGS = ["parking", "elevator", "renovated", "garden", "garage", "balcony", "storage"]
COASTAL_TAGS = ["sea view", "beach access", "terrace"]

# (weight, propertyType, sqm_low, sqm_high)
PROPERTY_TYPES = [
    (60, "Apartment", 45, 140),
    (20, "House", 100, 250),
    (10, "Studio", 25, 45),
    (5, "Duplex", 90, 180),
    (5, "Villa", 150, 400),
]

# (weight, role)
ROLES = [(40, "Buyer"), (40, "Seller"), (12, "Broker"), (5, "Notary"), (3, "TaxAuthority")]

# (weight, (year_low, year_high))
YEAR_BUCKETS = [(15, (1950, 1980)), (25, (1980, 2000)), (35, (2000, 2015)), (25, (2015, 2024))]

# newer buildings skew towards better ratings
ENERGY_BY_ERA = {
    (1950, 1980): ["E", "E", "F", "F", "G", "D"],
    (1980, 2000): ["D", "D", "E", "C", "E", "F"],
    (2000, 2015): ["C", "C", "B", "D", "B", "C"],
    (2015, 2024): ["A", "A", "B", "B", "C", "A"],
}


# How many contributors corroborate a transaction. Skewed well above a realistic mix, since only
# corroborated transactions are sellable; single-contributor ones are kept so the filter is
# still exercised.
CONTRIBUTOR_COUNTS = [(15, 1), (45, 2), (25, 3), (15, 4)]


def weighted_choice(rng: random.Random, weighted_options: list):
    weights = [w for w, _ in weighted_options]
    return rng.choices([o for _, o in weighted_options], weights=weights, k=1)[0]


def generate_transaction(rng: random.Random, index: int, pool_size: int, today: date,
                         max_contributors: int) -> list:
    """One transaction as one or more corroborating submissions with identical data, so the
    operator merges them. Parties and declared roles vary, which spreads the trust scores the
    pricing model's quality term needs."""
    data = generate_record(rng, index, pool_size, today)
    n = weighted_choice(rng, CONTRIBUTOR_COUNTS)
    n = min(n, max_contributors, pool_size)

    # distinct parties: MergeContribution refuses a contributor already on the record
    offset = rng.randrange(pool_size)
    parties = [f"Seed{((offset + k) % pool_size) + 1:03d}" for k in range(n)]

    # sometimes stack a role for the redundancy bonus, sometimes spread across roles
    if n > 1 and rng.random() < 0.4:
        roles = [data["role"]] * n
    else:
        roles = [data["role"]] + [weighted_choice(rng, ROLES) for _ in range(n - 1)]

    return [dict(data, contributor=p, role=r) for p, r in zip(parties, roles)]


def generate_record(rng: random.Random, index: int, pool_size: int, today: date) -> dict:
    city, (price_low, price_high, is_coastal) = rng.choice(list(CITIES.items()))
    price_per_sqm = rng.uniform(price_low, price_high)

    _, property_type, sqm_low, sqm_high = weighted_choice(
        rng, [(w, (w, pt, lo, hi)) for w, pt, lo, hi in PROPERTY_TYPES]
    )
    sqm = round(rng.uniform(sqm_low, sqm_high), 1)

    rooms = max(1, min(8, round(sqm / 35) + rng.choice([-1, 0, 0, 0, 1])))

    year_bucket = weighted_choice(rng, YEAR_BUCKETS)
    year_built = rng.randint(*year_bucket)
    energy_rating = rng.choice(ENERGY_BY_ERA[year_bucket])

    noise = rng.uniform(0.85, 1.15)
    sale_price = round(price_per_sqm * sqm * noise, 2)

    days_back = rng.randint(0, 3 * 365)
    sale_date = today - timedelta(days=days_back)

    street = f"{rng.choice(STREET_PREFIXES)} {rng.choice(STREET_NAMES)} {rng.randint(1, 300)}"
    postal_code = f"{rng.randint(1000, 9999)}-{rng.randint(0, 999):03d}"

    tag_pool = GENERIC_TAGS + (COASTAL_TAGS if is_coastal else [])
    tags = rng.sample(tag_pool, k=rng.randint(0, 3))

    role = weighted_choice(rng, ROLES)
    contributor = f"Seed{(index % pool_size) + 1:03d}"

    return {
        "transactionId": f"TX-SEED-{index:06d}",
        "contributor": contributor,
        "role": role,
        "propertyAddress": {
            "street": street,
            "city": city,
            "postalCode": postal_code,
            "country": "Portugal",
        },
        "salePrice": sale_price,
        "saleDate": sale_date.isoformat(),
        "propertyType": property_type,
        "squareMeters": sqm,
        "numberOfRooms": rooms,
        "yearBuilt": year_built,
        "energyRating": energy_rating,
        "tags": tags,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--count", type=int, default=500, help="Number of TRANSACTIONS to generate (default: 500); each yields one or more contributor submissions")
    parser.add_argument("--pool-size", type=int, default=30, help="Number of distinct contributor identities to cycle through (default: 30) - must match allocate_seed_parties.py's --pool-size")
    parser.add_argument("--max-contributors", type=int, default=4, help="Cap on contributors per transaction (default: 4)")
    parser.add_argument("--seed", type=int, default=42, help="RNG seed for reproducibility (default: 42)")
    parser.add_argument("--out", default="seed_data.json", help="Output file path (default: seed_data.json)")
    args = parser.parse_args()

    rng = random.Random(args.seed)
    today = date.today()

    records = []
    for i in range(args.count):
        records.extend(generate_transaction(rng, i + 1, args.pool_size, today, args.max_contributors))

    with open(args.out, "w") as f:
        json.dump(records, f, indent=2)

    per_tx = {}
    for r in records:
        per_tx[r["transactionId"]] = per_tx.get(r["transactionId"], 0) + 1
    spread = {}
    for c in per_tx.values():
        spread[c] = spread.get(c, 0) + 1

    print(f"Generated {args.count} transactions as {len(records)} contributor submissions "
          f"across {args.pool_size} identities -> {args.out}")
    print("  contributors per transaction: " +
          ", ".join(f"{k}x{v}" for k, v in sorted(spread.items())))


if __name__ == "__main__":
    main()
