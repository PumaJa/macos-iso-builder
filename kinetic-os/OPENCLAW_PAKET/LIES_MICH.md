# Handoff an OpenClaw

**Alles ist in diesem Ordner `kinetic-os/OPENCLAW_PAKET/`:** `bilder/` (alle Bildschirme), `logos/`, `texte/` (alle Sprachen), `doku/` (Design-Brief, Funktionen, Grenzen, Tests), `vesc/` (Skript + Paket), `app/` (APK), `quellcode_komplett.zip` (gesamter Code).

Alternativ liegt alles im Git-Repository `PumaJa/macos-iso-builder`, Branch `ccr-a2dbb74a-vy49ez`, Ordner `kinetic-os/`.

```
git clone -b ccr-a2dbb74a-vy49ez https://github.com/PumaJa/macos-iso-builder.git
cd macos-iso-builder/kinetic-os
```

## Inhalt
| Was | Wo |
|---|---|
| Android-App (Kotlin/Compose), Quellcode | `android/` |
| Alle Texte (6 Sprachen) | `android/app/src/main/res/values*/strings.xml` |
| Design-Regeln, Farben, Schrift, Logo-Raster, Prompt | `docs/DESIGN_BRIEF.md` |
| Alle Bildschirme als Bilder (hoch + `quer_*`) | `docs/screenshots/gallery/` |
| Logo-Entwürfe | `docs/screenshots/logo_entwuerfe.png`, `android/app/src/main/res/drawable/ic_logo_*.xml` |
| VESC-Fahrzeugskript (LispBM) + Protokoll | `lispbm/kinetic.lisp`, `lispbm/protocol.md` |
| Server + Web-Dashboard | `server/` |
| Funktionsliste, bekannte Grenzen, Tests | `FEATURE_MATRIX.md`, `KNOWN_ISSUES.md`, `TEST_RESULTS.md` |

## Auftrag
1. `docs/DESIGN_BRIEF.md` lesen, alle Bilder in `docs/screenshots/gallery/` ansehen.
2. Vorschläge liefern: 3–5 Logo-Marken (SVG-Pfade, 48×48), 2–3 dunkle Presets (Graustufen), Layout-Verbesserungen für Start, Cockpit, Modi, Sperrbildschirm, Textverbesserungen (v. a. FR/IT/ES/SV gegenlesen).
3. Ergebnisse als Markdown + SVG in `kinetic-os/docs/openclaw/` ablegen (Pull Request oder per Telegram schicken).

## Regeln
- Nur Graustufen; Barlow-Schrift; eigenes Design – **keine** Stark-Logos, -Grafiken, -Texte oder Kopien des Stark-Designs.
- Simulierte Daten bleiben immer als „SIMULATION/SIM“ markiert.
- Keine Änderungen an Sicherheitslogik (`SafetyGate`, `kinetic.lisp`-Limits) ohne Tests.
- Bauen/Testen: `cd android && ./gradlew :app:testDebugUnitTest` (Screenshots: `--tests '*Gallery*'` → `app/build/gallery/`).

## Nachricht für Telegram (kopieren)
> Hallo OpenClaw, bitte klone `https://github.com/PumaJa/macos-iso-builder` (Branch `ccr-a2dbb74a-vy49ez`), lies `kinetic-os/OPENCLAW_HANDOFF.md` und `kinetic-os/docs/DESIGN_BRIEF.md`, sieh dir alle Bilder in `kinetic-os/docs/screenshots/gallery/` an und arbeite den Auftrag ab. Schick mir die Vorschläge (Logos als SVG, Presets als Farbtabelle, Layout-Ideen) hier im Chat und lege sie unter `kinetic-os/docs/openclaw/` ab.
