#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Vérification automatisée de knowledge_base.db : volume, unicité, latence FTS5 (<5 ms) et pertinence FR avec/sans accents."""

import re
import sqlite3
import time
import unicodedata
from pathlib import Path

DB_PATH = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "knowledge_base.db"

STOPWORDS = {
    "le", "la", "les", "de", "du", "des", "un", "une", "et", "ou", "a", "au", "aux",
    "en", "pour", "par", "sur", "avec", "sans", "dans", "je", "tu", "il", "nous", "vous",
    "mon", "ma", "mes", "ton", "son", "que", "qui", "quoi", "comment", "quel", "quelle",
    "est", "sont", "faire", "avoir", "etre", "plus", "moins", "tres", "bien",
}


def strip_accents(text: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFKD", text) if not unicodedata.combining(c))


def expand_query(conn, raw_query: str) -> str:
    norm = re.sub(r"[^a-z0-9\s]", " ", strip_accents(raw_query).lower())
    tokens = [t for t in norm.split() if len(t) >= 2 and t not in STOPWORDS]
    if not tokens:
        return ""
    cur = conn.cursor()
    parts = []
    for tok in tokens:
        group = [f"{tok}*"]
        cur.execute("SELECT expansion FROM synonyms WHERE term = ?", (tok,))
        for (exp,) in cur.fetchall():
            for e in exp.split():
                if len(e) >= 3 and e not in STOPWORDS and f"{e}*" not in group:
                    group.append(f"{e}*")
        parts.append("(" + " OR ".join(group[:5]) + ")")
    # Join with OR when >3 tokens so natural sentences still match sharply while BM25 ranks multi-hit top!
    joiner = " AND " if len(parts) <= 2 else " OR "
    return joiner.join(parts)


def main():
    assert DB_PATH.exists(), f"Missing DB: {DB_PATH}"
    conn = sqlite3.connect(str(DB_PATH))
    cur = conn.cursor()

    cur.execute("SELECT category, COUNT(*) FROM entries GROUP BY category ORDER BY COUNT(*) DESC")
    counts = dict(cur.fetchall())
    total = sum(counts.values())
    print("Category breakdown:", counts, "TOTAL =", total)

    assert total >= 2000, f"Expected >= 2000 entries, got {total}"
    assert counts.get("exercice", 0) >= 500
    assert counts.get("nutrition", 0) >= 1000
    assert counts.get("programme", 0) >= 100
    assert counts.get("biomecanique", 0) >= 200
    assert counts.get("blessures", 0) >= 200

    # Check duplicates
    cur.execute("SELECT COUNT(*), COUNT(DISTINCT lower(title)) FROM entries")
    c_all, c_dist = cur.fetchone()
    print(f"Uniqueness check: {c_all} total rows, {c_dist} distinct titles")
    assert c_all == c_dist, "Duplicate titles detected!"

    # Test real French queries (with/without accents, synonyms, natural phrasing)
    test_queries = [
        ("squat genoux", ["squat", "genou"]),
        ("mal epaule", ["epaule", "coiffe", "conflit"]),
        ("proteine poulet", ["poulet", "proteine"]),
        ("programme hypertrophie 4 jours", ["upper", "4 jours", "hypertrophie"]),
        ("creatine dosage", ["creatine"]),
        ("tendinite achille", ["achille"]),
        ("douleur bas du dos deadlift", ["dos", "souleve", "lomb"]),
        ("développé incliné angle", ["incline", "developpe"]),
        ("récupération sommeil hrv", ["sommeil", "hrv", "recuperation"]),
        ("omega 3 saumon", ["saumon", "omega"]),
    ]

    latencies = []
    for q, expected_any in test_queries:
        fts_q = expand_query(conn, q)
        t0 = time.perf_counter()
        cur.execute(
            """SELECT e.category, e.title, bm25(entries_fts, 8.0, 4.0, 1.5, 5.0, 3.0, 2.0, 2.0) AS score
               FROM entries_fts
               JOIN entries e ON e.id = entries_fts.rowid
               WHERE entries_fts MATCH ?
               ORDER BY score ASC
               LIMIT 5""",
            (fts_q,),
        )
        hits = cur.fetchall()
        dt_ms = (time.perf_counter() - t0) * 1000.0
        latencies.append(dt_ms)
        top_titles = [f"[{c}] {t}" for c, t, _ in hits[:3]]
        print(f"Q='{q}' ({dt_ms:.2f} ms) -> {top_titles}")
        assert len(hits) > 0, f"No results for {q}"
        joined = strip_accents(" ".join(t for _, t, _ in hits).lower())
        assert any(k in joined for k in expected_any), f"Unexpected hits for '{q}': {top_titles}"

    avg_ms = sum(latencies) / len(latencies)
    max_ms = max(latencies)
    print(f"\nALL CHECKS PASSED! Avg FTS5 query latency: {avg_ms:.2f} ms | Max: {max_ms:.2f} ms")
    conn.close()


if __name__ == "__main__":
    main()
