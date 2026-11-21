#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Générateur de la base de connaissances offline KINETIX Coach (SQLite + FTS5 BM25 + Synonymes FR/EN).
Produit : app/src/main/assets/knowledge_base.db
"""

import os
import re
import sqlite3
import sys
import unicodedata
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from kb_data_exercises import build_exercises
from kb_data_nutrition_guides import build_nutrition
from kb_data_programs import build_programs
from kb_data_biomechanics_b import build_biomechanics
from kb_data_injuries_b import build_injuries

OUT_DB = SCRIPT_DIR.parent / "app" / "src" / "main" / "assets" / "knowledge_base.db"

# 160+ synonymes & expansions FR/EN sport, anatomie, nutrition, blessures
SYNONYMS = [
    # Exercices & anglais <-> français
    ("squat", "flexion jambes quadriceps fessiers"),
    ("squats", "squat quadriceps jambes"),
    ("pompes", "push up pushup pectoraux triceps"),
    ("pompe", "pompes push up pectoraux"),
    ("pushup", "pompes pectoraux triceps"),
    ("pushups", "pompes pectoraux triceps"),
    ("traction", "tractions pull up chin up dos grand dorsal"),
    ("tractions", "pull up chin up dos grand dorsal"),
    ("pullup", "tractions dos grand dorsal"),
    ("pullups", "tractions dos grand dorsal"),
    ("chinup", "tractions supination biceps dos"),
    ("bench", "developpe couche pectoraux barre"),
    ("dc", "developpe couche bench press pectoraux"),
    ("di", "developpe incline pectoraux haut"),
    ("dm", "developpe militaire epaules overhead press"),
    ("ohp", "developpe militaire epaules"),
    ("deadlift", "souleve de terre chaine posterieure ischios dos"),
    ("sdt", "souleve de terre deadlift"),
    ("rdl", "souleve de terre roumain ischio jambiers"),
    ("fentes", "lunge bulgare quadriceps fessiers"),
    ("fente", "fentes lunge quadriceps fessiers"),
    ("lunge", "fentes bulgare quadriceps"),
    ("bulgare", "squat bulgare fentes fessiers quadriceps"),
    ("planche", "gainage plank abdos transverse"),
    ("plank", "planche gainage abdos"),
    ("gainage", "planche abdos transverse tronc core"),
    ("core", "gainage abdos transverse obliques"),
    ("abdos", "abdominaux gainage grand droit obliques"),
    ("pecs", "pectoraux grand pectoral developpe couche"),
    ("pec", "pectoraux grand pectoral"),
    ("dos", "grand dorsal dorsaux rowing tractions trapezes"),
    ("lats", "grand dorsal tirage vertical tractions"),
    ("dorsaux", "grand dorsal dos tractions rowing"),
    ("epaules", "deltoides developpe militaire elevations laterales coiffe"),
    ("epaule", "deltoide coiffe rotateurs conflit"),
    ("deltos", "deltoides epaules elevations laterales"),
    ("biceps", "curl brachial bras"),
    ("triceps", "extension pushdown dips bras"),
    ("bras", "biceps triceps avant bras curl extension"),
    ("jambes", "quadriceps ischio jambiers fessiers mollets squat"),
    ("quads", "quadriceps squat hack presse leg extension"),
    ("quadriceps", "squat fentes presse leg extension"),
    ("ischios", "ischio jambiers leg curl rdl nordic"),
    ("ischio", "ischio jambiers leg curl rdl"),
    ("hamstrings", "ischio jambiers leg curl rdl nordic"),
    ("fessiers", "grand fessier moyen fessier hip thrust pont fessier glutes"),
    ("fesses", "fessiers grand fessier hip thrust"),
    ("glutes", "fessiers hip thrust bulgare"),
    ("mollets", "gastrocnemiens soleaire calf achille"),
    ("calves", "mollets gastrocnemiens soleaire"),
    ("trapezes", "trapeze shrugs rowing face pull"),
    ("avant-bras", "grip prehension wrist curl brachial"),
    ("grip", "prehension avant bras dead hang farmers walk"),
    ("halteres", "haltere dumbbell"),
    ("barre", "barbell olympique"),
    ("poulie", "cable poulie haute basse"),
    ("elastique", "bande elastique resistance maison"),
    ("maison", "poids du corps sans materiel halteres elastique"),
    ("calisthenie", "poids du corps street workout tractions dips muscle up"),
    # Nutrition & aliments
    ("prot", "proteines whey poulet oeuf skyr"),
    ("proteine", "proteines acides amines leucine whey poulet"),
    ("proteines", "proteine leucine whey poulet oeuf poisson"),
    ("glucide", "glucides riz patate douce avoine pates glycogene"),
    ("glucides", "carbs riz avoine pates banane glycogene"),
    ("carbs", "glucides riz pates avoine"),
    ("lipides", "graisses huile olive omega 3 avocat noix"),
    ("gras", "lipides masse grasse seche"),
    ("kcal", "calories tdee energie"),
    ("calories", "kcal tdee deficit surplus"),
    ("poulet", "blanc de poulet volaille proteines"),
    ("dinde", "escalope filet de dinde volaille"),
    ("boeuf", "steak hache bavette viande rouge fer creatine"),
    ("saumon", "poisson gras omega 3 vitamine d"),
    ("thon", "poisson maigre proteines"),
    ("oeuf", "oeufs blanc oeuf jaune choline"),
    ("oeufs", "oeuf omelette choline proteines"),
    ("skyr", "fromage blanc yaourt grec caseine"),
    ("avoine", "flocons avoine porridge beta glucanes"),
    ("riz", "riz basmati complet glucides"),
    ("creatine", "creatine monohydrate force atp dosage"),
    ("whey", "proteine isolate concentrate shaker leucine"),
    ("caseine", "skyr fromage blanc nuit sommeil"),
    ("cafe", "cafeine preworkout vigilance performance"),
    ("cafeine", "cafe preworkout dosage performance"),
    ("omega", "omega 3 epa dha poisson gras inflammation"),
    ("vitamine", "vitamine d c b12 micronutriments"),
    ("magnesium", "bisglycinate crampes sommeil atp"),
    ("fer", "ferritine anemie endurance viande rouge"),
    ("collagene", "tendon vitamine c articulation keith baar"),
    ("seche", "perte de gras deficit calorique cutting satiete"),
    ("maigrir", "seche perte de poids deficit calorique"),
    ("mincir", "seche deficit calorique recomposition"),
    ("pdm", "prise de masse surplus calorique lean bulk"),
    ("bulk", "prise de masse surplus calorique hypertrophie"),
    ("masse", "prise de masse hypertrophie surplus"),
    ("recomp", "recomposition corporelle"),
    ("hydratation", "eau electrolytes sodium sueur crampes"),
    ("electrolytes", "sodium potassium magnesium hydratation crampes"),
    ("jeune", "jeune intermittent fasting 16/8 ramadan"),
    ("vegan", "vegetarien vegetal soja tofu tempeh b12"),
    ("vegetarien", "vegan vegetal lentilles oeufs tofu"),
    # Programmes & cardio
    ("programme", "plan split entrainement protocole"),
    ("split", "programme ppl upper lower full body"),
    ("ppl", "push pull legs 6 jours programme"),
    ("hypertrophie", "prise de muscle volume mev mav mrv tension mecanique"),
    ("muscle", "hypertrophie prise de masse force"),
    ("force", "powerlifting 5x5 5/3/1 1rm force maximale"),
    ("cardio", "endurance zone 2 vo2max hiit running"),
    ("course", "running footing marathon 10km semi trail"),
    ("courir", "course running 5km 10km marathon"),
    ("running", "course pied marathon semi 10km vma"),
    ("marathon", "42km course carb loading affutage"),
    ("semi", "semi marathon 21km course"),
    ("trail", "montagne denivele ultra course descente"),
    ("hyrox", "hybride sled wall balls compromised running"),
    ("hiit", "fractionne tabata vo2max intervalles"),
    ("vma", "fractionne vo2max demi cooper 30/30"),
    ("velo", "cyclisme ftp home trainer selle"),
    ("natation", "crawl piscine epaule nageur"),
    ("boxe", "combat mma conditioning rounds"),
    ("foot", "football prepa nordic copenhagen lca"),
    ("deload", "decharge recuperation fatigue semaine legere"),
    # Blessures, douleurs & mobilité
    ("mal", "douleur blessure tendinite reeducation prevention"),
    ("douleur", "mal blessure tendinopathie protocole reeducation"),
    ("tendinite", "tendinopathie hsr isometrique tendon"),
    ("tendinopathie", "tendinite hsr isometrique collagene"),
    ("genou", "genoux rotule rotulien lca menisque essuie glace valgus"),
    ("genoux", "genou squat rotule valgus tendon rotulien"),
    ("rotule", "tendon rotulien syndrome rotulien genou"),
    ("achille", "tendon achille mollet alfredson hsr talon"),
    ("talon", "fasciite aponevrosite achille pied"),
    ("tibia", "periostite tibiale fracture fatigue shin splints"),
    ("periostite", "tibia shin splints course impact"),
    ("cheville", "entorse dorsiflexion proprioception"),
    ("entorse", "cheville genou peace love proprioception"),
    ("coude", "epicondylite epitrochleite tennis elbow golfers elbow"),
    ("poignet", "poignets canal carpien tfcc pompes front squat"),
    ("lombaire", "lombaires lombalgie bas du dos hernie sciatique mcgill"),
    ("lombaires", "lombaire bas du dos lumbago hernie mcgill"),
    ("lumbago", "lombalgie mal bas du dos"),
    ("hernie", "disque lombaire sciatique dos"),
    ("sciatique", "hernie piriforme nerf dos fesse"),
    ("hanche", "cfa pincement aine moyen fessier bursite 90/90"),
    ("aine", "pubalgie adducteurs cfa psoas hanche"),
    ("claquage", "dechirure lesion musculaire ischios mollet"),
    ("dechirure", "claquage lesion musculaire ischios"),
    ("crampe", "crampes electrolytes sodium magnesium"),
    ("crampes", "crampe sodium magnesium fatigue"),
    ("courbatures", "doms recuperation dommages musculaires"),
    ("mobilite", "souplesse etirement cars 90/90 echauffement"),
    ("souplesse", "mobilite etirement grand ecart pnf"),
    ("echauffement", "ramp mobilite activation gammes montantes"),
    ("recuperation", "sommeil hrv deload nutrition sauna"),
    ("sommeil", "recuperation melatonine glycine hrv fatigue"),
    ("fatigue", "surentrainement deload sommeil snc hrv"),
    ("stress", "cortisol sommeil respiration coherence cardiaque"),
]


def strip_accents(text: str) -> str:
    nfkd = unicodedata.normalize("NFKD", text)
    return "".join(ch for ch in nfkd if not unicodedata.combining(ch))


def make_slug(category: str, title: str, idx: int) -> str:
    raw = strip_accents(f"{category}-{title}").lower()
    slug = re.sub(r"[^a-z0-9]+", "-", raw).strip("-")
    return f"{slug[:80]}-{idx}"


def main():
    all_entries = []
    ex = build_exercises()
    nu = build_nutrition()
    pr = build_programs()
    bi = build_biomechanics()
    ij = build_injuries()

    print(f"Counts -> exercice: {len(ex)}, nutrition: {len(nu)}, programme: {len(pr)}, biomecanique: {len(bi)}, blessures: {len(ij)}")
    all_entries.extend(ex)
    all_entries.extend(nu)
    all_entries.extend(pr)
    all_entries.extend(bi)
    all_entries.extend(ij)
    print(f"Total combined entries: {len(all_entries)}")

    OUT_DB.parent.mkdir(parents=True, exist_ok=True)
    if OUT_DB.exists():
        OUT_DB.unlink()

    conn = sqlite3.connect(str(OUT_DB))
    cur = conn.cursor()

    cur.execute("PRAGMA page_size = 4096;")
    cur.execute("PRAGMA journal_mode = DELETE;")
    cur.execute("PRAGMA synchronous = OFF;")

    cur.executescript("""
        CREATE TABLE entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            slug TEXT NOT NULL UNIQUE,
            category TEXT NOT NULL,
            subcategory TEXT NOT NULL,
            title TEXT NOT NULL,
            summary TEXT NOT NULL,
            body TEXT NOT NULL,
            tags TEXT NOT NULL,
            metadata_json TEXT NOT NULL,
            search_tokens TEXT NOT NULL
        );

        CREATE INDEX idx_entries_category ON entries(category);
        CREATE INDEX idx_entries_subcategory ON entries(subcategory);
        CREATE INDEX idx_entries_cat_sub ON entries(category, subcategory);

        -- FTS5 with unicode61 remove_diacritics 1 (universally supported on Android API 29..36!)
        CREATE VIRTUAL TABLE entries_fts USING fts5(
            title,
            summary,
            body,
            tags,
            search_tokens,
            category,
            subcategory,
            content='entries',
            content_rowid='id',
            tokenize='unicode61 remove_diacritics 1'
        );

        CREATE TABLE synonyms (
            term TEXT NOT NULL,
            expansion TEXT NOT NULL
        );
        CREATE INDEX idx_synonyms_term ON synonyms(term);

        CREATE TABLE kb_meta (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );
    """)

    rows = []
    for idx, item in enumerate(all_entries, start=1):
        slug = make_slug(item["category"], item["title"], idx)
        norm_tokens = strip_accents(
            f"{item['title']} {item['subcategory']} {item['tags']}"
        ).lower()
        norm_tokens = re.sub(r"[^a-z0-9\s]+", " ", norm_tokens)
        norm_tokens = " ".join(dict.fromkeys(norm_tokens.split()))
        rows.append((
            slug,
            item["category"],
            item["subcategory"],
            item["title"],
            item["summary"],
            item["body"],
            item["tags"],
            item["metadata_json"],
            norm_tokens,
        ))

    cur.executemany(
        """INSERT INTO entries(slug, category, subcategory, title, summary, body, tags, metadata_json, search_tokens)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        rows,
    )

    cur.execute("INSERT INTO entries_fts(entries_fts) VALUES('rebuild');")

    syn_rows = [(strip_accents(t).lower().strip(), strip_accents(e).lower().strip()) for t, e in SYNONYMS]
    cur.executemany("INSERT INTO synonyms(term, expansion) VALUES (?, ?)", syn_rows)

    meta_rows = [
        ("schema_version", "1"),
        ("total_entries", str(len(rows))),
        ("exercice_count", str(len(ex))),
        ("nutrition_count", str(len(nu))),
        ("programme_count", str(len(pr))),
        ("biomecanique_count", str(len(bi))),
        ("blessures_count", str(len(ij))),
        ("synonyms_count", str(len(syn_rows))),
    ]
    cur.executemany("INSERT INTO kb_meta(key, value) VALUES (?, ?)", meta_rows)

    cur.execute("PRAGMA user_version = 1;")
    conn.commit()
    cur.execute("ANALYZE;")
    cur.execute("VACUUM;")
    conn.close()

    size_mb = OUT_DB.stat().st_size / (1024 * 1024)
    print(f"Successfully built {OUT_DB} ({size_mb:.2f} MB, {len(rows)} entries, {len(syn_rows)} synonyms)")


if __name__ == "__main__":
    main()
