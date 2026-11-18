---
title: Interfeys tillari va birliklar
type: subsystem
created: 2026-11-18
updated: 2026-11-18
sources:
  - code: ui/designsystem/src/main/kotlin/com/sharpcad/ui/designsystem/Units.kt
  - code: app/src/main/kotlin/com/sharpcad/app/CommandPanel.kt
  - code: ui/designsystem/src/main/kotlin/com/sharpcad/ui/designsystem/Tr.kt
  - code: ui/designsystem/src/main/kotlin/com/sharpcad/ui/designsystem/Strings.kt
  - code: ui/designsystem/src/test/kotlin/com/sharpcad/ui/designsystem/TrTest.kt
  - code: app/src/main/kotlin/com/sharpcad/app/AppSettings.kt
  - code: ui/workspace/src/main/kotlin/com/sharpcad/ui/workspace/Panels.kt
  - code: ui/workspace/src/main/kotlin/com/sharpcad/ui/workspace/ItemsPanel.kt
  - code: ui/workspace/src/main/kotlin/com/sharpcad/ui/workspace/MaterialPanel.kt
  - code: ui/workspace/src/main/kotlin/com/sharpcad/ui/workspace/Rails.kt
  - code: ui/workspace/src/main/kotlin/com/sharpcad/ui/workspace/Workspace.kt
verified_at: a99096b
---

# Interfeys tillari

Ingliz, o'zbek va rus tillari. Til Appearance panelidagi "Language" tanlovida
almashtiriladi (`AppSettings`), SharedPreferences'da saqlanadi; birinchi ishga
tushishda qurilma tili olinadi (bizda bo'lsa).

Kodda barcha yozuvlar inglizcha qoladi va kalit vazifasini bajaradi. Faqat ekranga
chiziladigan joyda `tr()` chaqiriladi (`Chip`, panel sarlavhalari, xabarlar, menyular,
status satri). `Tr.tr` avval to'liq moslikni, keyin " · " bo'laklarini, "so'z 12 mm",
"3 so'z" va ma'lum prefikslarni (`Open: `, `Export ` …) tarjima qiladi; topilmasa matn
o'zgarishsiz ko'rsatiladi. Lug'at — `Strings.table` (inglizcha → (o'zbekcha, ruscha)).
`Tr.language` Compose holati, shuning uchun til almashganda ekran darhol yangilanadi.

> ⚠️ Mantiqqa tarjima qilingan matnni bermang: `ChoiceField` variantlari va
> `PanelAction` yorliqlari kodda inglizcha taqqoslanadi (`AppSettings.choose`,
> `ModelController.setChoice`). `tr()` faqat `Text`/`Chip` ichida.

> ⚠️ Yangi yozuv qo'shsangiz, `Strings.table` ga ham kalitni aynan shu inglizcha matn
> bilan qo'shing; aks holda u barcha tillarda inglizcha ko'rinadi (xato bermaydi).

## Birliklar (mm / sm / dyuym)

Units rail tugmasi ko'rsatish birligini almashtiradi (`AppSettings.cycleUnits`, saqlanadi).
Model doim millimetrda; faqat uzunlik maydonlari (`DimensionField.unit == "mm"`) va
o'lchash/sudrash ko'rsatkichlari `Units` orqali ko'rsatiladi. Maydonga oddiy son yozilsa,
u tanlangan birlikda hisoblanadi; `2cm` kabi qo'shimchali yozuv o'z birligida qoladi.

> ⚠️ Birlik qo'shimchasi yoki o'zgaruvchi (harf) bor ifoda millimetr deb qabul qilinadi
> va ko'paytirilmaydi (`DimensionInput`): `w/2` da `w` mm'da saqlangan.

> ⚠️ Xususiyat yorliqlari va status satri hali `formatMm` bilan mm'da qoladi.

## Search / Tools

Ikkala tugma ham buyruqlar panelini ochadi (`CommandPanel`): barcha buyruqlar chip
ko'rinishida, inglizcha yoki joriy tildagi nom bo'yicha filtrlanadi; ro'yxat
`MainActivity` dagi `railMap` + qo'shimcha buyruqlardan tuziladi.
