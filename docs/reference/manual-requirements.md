---
title: Ish oqimi talablari (qo'llanma tahlili)
type: reference
created: 2026-11-17
updated: 2026-11-17
sources:
  - ext: https://support.shapr3d.com/hc/en-us/articles/9760033847964-Shapr3D-Manual
  - code: document/src/main/kotlin/com/sharpcad/document/ModelBuilder.kt
  - code: app/src/main/kotlin/com/sharpcad/app/ModelController.kt
verified_at: bac5213
---

# Ish oqimi talablari

Egasi bergan qo'llanmaning (26.170, 352 sahifa) modellashtirishga oid boblaridan
qisqacha, o'z so'zlarimiz bilan olingan talablar. Akkaunt, obuna, bulut va hamkorlik
boblari tashlab ketildi. Maqsad — o'xshash ish oqimi, lekin o'z dizaynimiz: hech qanday
nom, belgi yoki rasm ko'chirilmaydi.

> ⚠️ Bu sahifa talablar ro'yxati, kod holati emas. Nima qilinganini pastdagi
> "Holat" bo'limi va [[sketch-engine]], [[viewport-rendering]], [[kernel-jni]] da tekshiring.

## Workspace & selection
- Adaptive menu: selection predicts next tools (left); "More" for the rest. Modes (Section, Measure) bottom-left, adapt to selection.
- Command search with fuzzy match; pre-selection filters results.
- Area selection: L→R fully inside, R→L touching; filters All/Bodies/Faces/Edges.
- Overlapping items → pop-up list to choose. Selected items drawn on top. Select-through mode.
- Notable points (snaps): endpoints, midpoints, centers (arcs, circles, rectangular faces, cylinders), intersections.
- Tap empty area = finish tool / deselect.
- Numeric labels: tap → numpad; expressions + - * / allowed; no comma decimals.
- Gizmo: arrows (drag or tap → value), tiles (planar move), center (pivot, snaps to geometry), Copy badge (multiple copies), Link badge.

## Items Manager / History
- Items: bodies, sketches, planes, axes, images, folders; hide/show, rename, delete, zoom-to, select from list; show hidden, invert visibility.
- History: one step per edit, expandable card with parameters; step menu: breakpoint, suppress, zoom-to, rename, duplicate, delete; filter by selection / isolated; merge steps.

## Sketching
- Start: tap world-plane squares near origin, or double-tap grid / planar face / construction plane. "Normal to sketch" button. Same plane right after → same sketch, else new sketch.
- States: green fully defined, blue under-defined; per point too (filled centre = connected).
- Tools: Line (chain, auto-close ends drawing), Arc, auto Line/Arc (pen), Spline (fit / control points; long-tap adds point), Rectangle (centre / diagonal / 3-point), Circle, Ellipse, Polygon (triangle…octagon, editable side count), Offset (single / chain), Move/Rotate (+copy), drag elements directly, Pattern (linear 1–2 dirs total/spacing + qty; circular total/spacing angle, uniform/rotated), Text (font, height, alignment → profiles), Project, Trim (tap segment), Delete.
- Dimensions: length (absolute / horizontal / vertical), diameter, radius, angle; editable labels.
- Constraints: parallel, perpendicular, tangent, coincident, midpoint, concentric, horizontal/vertical (nearest axis), equal, symmetry (pick axis), disconnect, lock; drag point onto point/line/midpoint creates constraint; auto-constraining setting (H/V, perpendicular, tangent, coincident); anchored entity first/last selected.
- Make construction (dashed, reference only: symmetry/revolve axes). Lock/unlock points.

## Insert / Construct / Transform
- Variables & expressions in every numeric field: named variables, + - * /, functions (sin, cos, sqrt, pi(), …), mixed units auto-converted, angles need explicit unit.
- Construction planes: offset, midplane, 3 points, on curve at point, perpendicular to edge at point, through edge at angle, parallel to face at point. Axes: 2 points, 2 planes, cylinder/cone axis, along edge, perpendicular to face at point.
- Transform: Move/Rotate (gizmo, copy), Scale (uniform / non-uniform, copy), Translate (two points), Pattern 3D (linear 1–3 dirs, circular; result grouped in a folder), Rotate around axis, Align (face/edge to target, snaps, flip), Mirror (over plane/face, keep or remove original).

## Tools
- Extrude = push/pull: select face(s) or sketch profile(s), drag gizmo, Boolean badge, draft angle; sides one-sided/symmetric; extent distance / to object / through all; start from profile / offset / plane.
- Automatic boolean: no contact → New Body; face of a body selected, or profile touching a body pulled away → Union; profile/face pushed into a body → Subtract; Intersect only manual.
- Chamfer/Fillet are one tool: select edges, drag arrows away = fillet, toward = chamfer; fillet radius/chord, G1/G2, rolling-ball/setback corners; chamfer one or two distances.
- Offset Face (planar and curved, tangent faces included), Shell (pick faces to remove, wall thickness), Loft (profiles in order, guides, periodic), Union/Subtract/Intersect (target + tools, keep originals), Split Body (by plane/face/profile), Revolve (profile + axis: grid axis, construction axis, sketch line or edge; angle; height for coils), Sweep (profile + path; orientation, twist, scale, corners), Replace Face, Offset Edge (3D), Project (edges/sketches onto faces), Wrap & Emboss.

## Modes, measurement, files
- Isolate selected items (also filters History). Section view (plane/face, 2D section, section only, normal to section). Measure panel (pin measurements) + selection info bar: length, area, volume, radius sums.
- Import: STEP, IGES, X_T/X_B, STL (reference), SLDPRT/ASM, images (trace with opacity), DXF/DWG; imports are one History step. Design space 1 km³.
- Export: STEP, IGES, STL, OBJ, 3MF, GLB, USDZ, X_T, DXF/DWG, PDF, images; resolution (deviation/angle), separate files per item, units.

## Holat (2026-11-17)

| Talab | Holat |
|---|---|
| Eskiz holati rangi (yashil/ko'k) | qilingan (faqat butun eskiz, nuqtalar emas) |
| Chiziq, to'rtburchak (diagonal), aylana | qilingan |
| Avtomatik cheklovlar: tutashish, gorizontal/vertikal | qilingan |
| O'lcham kiritish (uzunlik, radius) | qilingan, ifodalar yo'q |
| Ekstruziya: hudud tanlash, avtomatik birlashtirish/kesish, simmetrik | qilingan (qurilmada sinalmagan) |
| Revolve (tekislik o'qi atrofida) | qilingan (qurilmada sinalmagan) |
| Fillet, faska, yuzani surish, bul amallari | qilingan (qurilmada sinalmagan) |
| Tarix paneli, bekor qilish/qaytarish | qilingan (qurilmada sinalmagan) |
| Moslashuvchan menyu (tanlovga qarab asboblar) | qilingan, oddiy panel shaklida |
| Import: STEP, IGES; eksport: STEP, IGES, STL, OBJ, 3MF | qilingan (qurilmada sinalmagan) |
| PBR materiallar (100+), ko'rinish rejimlari | qilingan, materiallar saqlanmaydi |
| 2D chizmalar (4 ko'rinish, masshtab, o'lchamlar, SVG/DXF/PDF) | qilingan (qurilmada sinalmagan) |
| AR (GLB orqali tashqi ko'ruvchi) | qilingan, ishonchliligi tekshirilmagan |
| Loyihalar, avtomatik saqlash, oxirgi loyihalar | qilingan (qurilmada sinalmagan) |
| Mavzu (qorong'i/yorug'), urg'u rangi, chap qo'l rejimi, yozuvlar | qilingan |
| Benchmark (qurish, bul, eksport/import, aylanish kadri) | qilingan; Perfetto/AGI profili qilinmagan |
| Shell, kesim ko'rinishi, maydon/hajm o'lchash | qilingan (qurilmada sinalmagan) |
| Markazdan to'rtburchak, olti burchak; teng/parallel/perpendikulyar cheklovlar (dvigatelda) | qilingan |
| Items paneli: tanlash, nomlash, yashirish/ko'rsatish, o'chirish (loyihada saqlanadi) | qilingan |
| Transform: siljitish/aylantirish, nusxa, ko'zgu, chiziqli va doiraviy massiv (jismlar) | qilingan (qurilmada sinalmagan) |
| Yoy (vatar ustida yarim aylana), Trim (teginish bilan kesish); yoyli profillar ekstruziya/revolve qilinadi | qilingan (qurilmada sinalmagan) |
| Qolganlari (splayn, ofset, eskiz massivi, matn, loft, sweep, o'zgaruvchilar, gizmo, cheklovlarni UI dan qo'yish) | rejalashtirilgan |
