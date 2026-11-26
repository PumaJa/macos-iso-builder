# Kinetic OS – Design Brief (for designers, OpenClaw or a local model)

Use this brief to produce **additional design variants** (themes, logo marks, screen layouts) that fit the app.
Everything here is the app's own design system. Do **not** use Stark assets, logos, fonts, photos or trade dress:
the app may follow the same *usage concept* (large instruments, M1–M5 modes, landscape handlebar mount), but its look must stay original.

## Principles
1. **Greyscale only** – black → white. No hue for decoration. Status is carried by brightness, labels and icons.
2. **Quiet and precise** – thin hairlines (1 dp), generous spacing, few borders, no drop shadows, no gradients except subtle photo fades.
3. **Large instruments** – speed, battery, mode and riding time readable at a glance with gloves.
4. **Light numerals** – big numbers in Barlow Light; small uppercase labels with wide tracking.
5. **Honesty** – simulated data always shows the "SIMULATION" banner and "SIM" tags; never style fake data like real data.
6. **Landscape first** – handlebar mount; portrait must still work.

## Tokens (Kotlin source of truth: `android/app/src/main/kotlin/org/kineticos/app/ui/Theme.kt`)
| Token | OLED | Graphite | Titanium | Day |
|---|---|---|---|---|
| bg | `#000000` | `#111113` | `#0E0D0C` | `#F2F2F4` |
| surface | `#121212` | `#1A1A1D` | `#181614` | `#FFFFFF` |
| surface2 | `#1E1E20` | `#242428` | `#221F1C` | `#E8E8EC` |
| text | `#FFFFFF` | `#F5F5F7` | `#F4F1EC` | `#0A0A0A` |
| textDim | `#8E8E93` | `#9A9AA0` | `#9E978E` | `#5E5E66` |
| hairline (grid) | `#2C2C2E` | `#34343A` | `#35302A` | `#D4D4DA` |
| accent | grey level 0.4–1.0 (user setting, default 0.95 = `#F2F2F2`) | | | `#111111` |

Typography: **Barlow** (SIL OFL 1.1) – Light for numbers ≥ 40 sp, Regular for titles, Medium 10–12 sp uppercase labels with 1.6 sp tracking.
Radii: cards 16 dp, buttons 12 dp, pills fully round. Touch targets ≥ 56 dp (glove mode).

## Logo
Built-in marks (vector drawables in `res/drawable/ic_logo_*.xml`, 48×48 grid):
- **Apex** (default): upright stem `M10.5,8h5v32h-5z` + free-standing chevron `M19.5,24L32,8H38L25.5,24L38,40H32Z`.
- **Ring**: Apex at 58 % inside a 1.6-unit circle.
- **Tile**: rounded square (r = 11) with Apex cut out (even-odd).
- Wordmark: "KINETIC" (or the user's own name) in Barlow Regular, tracking ≈ size/6.
Users can also set their own logo image and name (Settings → Design).

## Deliverables wanted from a design run
1. 3–5 additional **logo marks** on the 48×48 grid as SVG path data (single colour, works at 16 px and 512 px).
2. 2–3 additional **dark presets** as token tables like above (still greyscale).
3. Screen mock-ups (1080×2400 portrait and 2400×1080 landscape) for: Start, Cockpit, Modes (M1–M5 with PS / regen / traction sliders), Lock screen.
4. For each proposal: one sentence of rationale.

## How to integrate a result
- New preset → add to `DesignPreset` (`data/Settings.kt`) and `nightPalette()` (`ui/Theme.kt`) + strings in all 6 `values*/strings.xml`.
- New logo → add `res/drawable/ic_logo_<name>.xml`, a `LogoStyle` entry and a branch in `logoRes()` (`ui/Widgets.kt`).
- Run `./gradlew :app:testDebugUnitTest --tests '*Gallery*'` → screenshots in `app/build/gallery/` (portrait + `quer_*` landscape).

## Prompt template for a local model / OpenClaw
> You are a senior product designer. Using ONLY the rules and tokens in docs/DESIGN_BRIEF.md, propose <N> new
> <logo marks | dark presets | screen layouts> for Kinetic OS, an original greyscale cockpit app for VESC electric
> motorcycles. No third-party brand assets or look-alikes. Output SVG path data / token tables / layout descriptions
> plus a one-sentence rationale each.
