---
title: 2D chizmalar va AR
type: subsystem
created: 2026-11-17
updated: 2026-11-17
sources:
  - code: kernel/src/main/cpp/src/modeling.cpp
  - code: document/src/main/kotlin/com/sharpcad/document/Drawing.kt
  - code: document/src/main/kotlin/com/sharpcad/document/GlbExport.kt
  - code: app/src/main/kotlin/com/sharpcad/app/DrawingScreen.kt
  - code: app/src/main/kotlin/com/sharpcad/app/ArLauncher.kt
  - code: app/src/main/AndroidManifest.xml
verified_at: a99096b
---

# 2D chizmalar va AR

## Chizmalar

Yadro OCCT'ning aniq yashirin-chiziq algoritmi (HLR) bilan ko'rinish proyeksiyasini
beradi: ko'rinadigan va yashirin qirralar 2D kesmalar sifatida. `DrawingLayout` old,
ust, o'ng va izometrik ko'rinishlarni A4 gorizontal varaqqa uchinchi burchak usulida
joylaydi, standart masshtabni (10:1 … 1:1000) tanlaydi, ortografik ko'rinishlarga umumiy
eni/bo'yi o'lchamlarini va sarlavha blokini qo'shadi. Eksport: SVG, DXF (R12) — sof
Kotlin; PDF — Android `PdfDocument` orqali, oldindan ko'rish bilan bir xil chizuvchi.
Bular [[modeling-history]] dagi jismlardan quriladi; talablar [[manual-requirements]] da.

> ⚠️ Yuqoridan qaraganda yashirin chiziqlar ko'rinadiganlari ustiga tushadi (pastki
> kontur, teshik tubi). Bu HLR uchun to'g'ri natija; testda "yashirin chiziq yo'q" deb
> kutmang.

## AR

View → View in AR jismlarni GLB (glTF 2.0, metr, Y yuqoriga, PBR materiallar) qilib
yozadi va `FileProvider` orqali Google'ning AR ko'ruvchisiga (Google Play Services for
AR) beradi; u bo'lmasa, glTF ochadigan istalgan ilovaga.

> ⚠️ Google AR ko'ruvchisi `file` parametrida asosan http(s) manzilni kutadi; mahalliy
> `content://` manzili barcha qurilmalarda ochilishi kafolatlanmagan. Ilova ichidagi
> ARCore + Filament ko'ruvchisi hali yo'q. Qurilmada sinalmagan.

> ⚠️ `ChooserIntent` hech qachon `ActivityNotFoundException` tashlamaydi; shuning uchun
> avval `resolveActivity` bilan tekshiriladi. Manifestdagi `<queries>` usiz Android 11+
> da paketlarni ko'rmaydi.
