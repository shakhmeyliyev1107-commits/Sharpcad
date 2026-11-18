---
title: Loyihalar, sozlamalar va benchmark
type: operation
created: 2026-11-17
updated: 2026-11-18
sources:
  - code: document/src/main/kotlin/com/sharpcad/document/ProjectStore.kt
  - code: app/src/main/kotlin/com/sharpcad/app/WorkspaceState.kt
  - code: app/src/main/kotlin/com/sharpcad/app/AppSettings.kt
  - code: app/src/main/kotlin/com/sharpcad/app/Benchmark.kt
  - code: ui/designsystem/src/main/kotlin/com/sharpcad/ui/designsystem/Theme.kt
verified_at: b9234ce
---

# Loyihalar, sozlamalar va benchmark

## Loyihalar

Har bir loyiha `filesDir/projects/<id>/project.json` da: xususiyatlar tarixi
([[modeling-history]]), nom, jism materiallari, nomlari, yashirinligi va o'lcham maydonlaridagi o'zgaruvchilar (`w=20`). Ilova oxirgi o'zgarishdan 0.8 s keyin
avtomatik saqlaydi, ochilganda oxirgi loyihani tiklaydi, File menyusida oxirgi 6 loyiha
ko'rinadi. Import qilingan fayllar `filesDir/imports` da qoladi.

> ⚠️ Loyiha fayli atomar yoziladi (`.tmp` faylga, keyin nomini o'zgartirish). To'g'ridan
> to'g'ri `project.json` ga yozmang: yozish o'rtasida ilova to'xtasa, loyiha buziladi.

> ⚠️ `ProjectData.FORMAT` ni faqat mos kelmaydigan o'zgarishda oshiring; yangi maydonlar
> uchun shart emas (`ignoreUnknownKeys`). Xususiyat klassini qayta nomlasangiz,
> `@SerialName` o'zgarmasin, aks holda eski loyihalar ochilmaydi.

Spetsifikatsiyadagi Room o'rniga oddiy JSON papkalar ishlatilgan: metadata kam
(nom, vaqt), Room va KSP qo'shish hozircha ortiqcha.

## Sozlamalar

O'ng paneldagi palitra tugmasi: qorong'i/yorug' mavzu, urg'u rangi (6 ta), asboblar
paneli chapda yoki o'ngda (chap qo'llilar uchun), panel yozuvlari. SharedPreferences da
saqlanadi.

> ⚠️ `SharpCadColors` maydonlari Compose holati. Ularni oddiy `val` ga qaytarsangiz,
> mavzu o'zgarganda ekran qayta chizilmaydi.

## Benchmark

Help → Run benchmark: 100 jismli model qurish vaqti, bul amali kechikishi (5 ta o'lchov
medianasi), STEP eksport/import, 240 kadrlik avtomatik to'liq aylanish va xotira.
Natija ekranda ko'rsatiladi va `filesDir/benchmark-*.txt` ga yoziladi. Benchmark modeli
saqlanmaydi.

> ⚠️ Kadr vaqti vsync oraliqlaridan o'lchanadi — bu ekranga chiqish tezligi, GPU vaqti
> emas. GPU ishini o'lchash uchun Android GPU Inspector yoki Perfetto kerak; ular bilan
> profil hali qilinmagan.
