---
title: Eskiz dvigateli va eskiz rejimi
type: subsystem
created: 2026-11-17
updated: 2026-11-17
sources:
  - code: sketch/src/main/cpp/src/sketch.cpp
  - code: sketch/src/main/cpp/include/cad/sketch/sketch.h
  - code: sketch/src/main/cpp/jni/jni_sketch.cpp
  - code: sketch/src/main/cpp/CMakeLists.txt
  - code: sketch/src/main/kotlin/com/sharpcad/sketch/Sketch.kt
  - code: sketch/src/main/kotlin/com/sharpcad/sketch/Snapper.kt
  - code: sketch/src/main/kotlin/com/sharpcad/sketch/SketchPlane.kt
  - code: third_party/planegcs/CMakeLists.txt
  - code: third_party/planegcs/README.md
  - code: app/src/main/kotlin/com/sharpcad/app/SketchSession.kt
  - code: ui/workspace/src/main/kotlin/com/sharpcad/ui/workspace/SketchControls.kt
  - code: app/build.gradle.kts
  - code: tools/fetch-sketch-deps.sh
verified_at: 19752a7
---

# Eskiz dvigateli va eskiz rejimi

`:sketch` moduli 2D eskizni (chiziq, aylana) cheklovlar bilan saqlaydi va PlaneGCS
yordamida yechadi. Qatlamlar [[kernel-jni]] dagi bilan bir xil: sof C++
(`cad::sketch::Sketch`), JNI (`libcadsketch.so`), Kotlin API. Chizish
[[viewport-rendering]] dagi ustki chiziqlar (overlay) orqali ko'rsatiladi.

## Model va yechish

Obyekt va cheklov identifikatorlari hech qachon qayta ishlatilmaydi; o'chirilgani
"o'lik" deb belgilanadi. Cheklovlar: ustma-ust tushish, gorizontal, vertikal, masofa,
radius, mahkamlash. Har bir `solve` da PlaneGCS tizimi modeldan qaytadan quriladi —
eskizlar kichik, ikki tasvirni sinxron saqlashdan arzonroq.

Natija: holat (yechildi / ziddiyat / yaqinlashmadi), erkinlik darajalari soni, zid va
ortiqcha cheklovlar ro'yxati. Ziddiyat yoki muvaffaqiyatsizlikda geometriya o'zgarmaydi.

Sudrab yechish (`solveDragging`) PlaneGCS'ning vaqtinchalik cheklovidan (manfiy teg)
foydalanadi: nuqta nishonga haqiqiy cheklovlar ruxsat bergan darajada tortiladi.

> ⚠️ Parametrlar `std::deque<double>` da saqlanadi, chunki PlaneGCS ularga ko'rsatkich
> ushlab turadi. Uni `std::vector` ga almashtirmang: o'sganda manzillar o'zgaradi va
> yechuvchi bo'shatilgan xotiraga yozadi.

> ⚠️ PlaneGCS'da 0 tegi "tegsiz" degani, shuning uchun cheklov tegi `id + 1`. Natijadagi
> teglardan 1 ayiriladi va musbat bo'lmaganlari (vaqtinchalik cheklovlar) tashlanadi.

## PlaneGCS

Manba FreeCAD 1.0.2 dan o'zgartirilmagan holda `third_party/planegcs` da; FreeCAD'ga
bog'liq to'rtta sarlavha o'rniga o'zimizning shim fayllar qo'yilgan. Eigen va Boost
sarlavhalari `tools/fetch-sketch-deps.sh` bilan yuklanadi (git'da yo'q).

> ⚠️ `third_party/planegcs/src/Mod/Sketcher/App/planegcs` ichidagi fayllarni tahrirlamang
> va kutubxonani statik bog'lamang (LGPL). Moslashtirish kerak bo'lsa, shim qo'shing.

> ⚠️ PlaneGCS har doim `-O2` bilan kompilyatsiya qilinadi. Optimallashtirilmagan Eigen
> kodi bir necha o'n marta katta va sekin; debug yig'ilishda eskiz ishlatib bo'lmaydi.

> ⚠️ `EIGEN_MPL2_ONLY` olib tashlanmasin: u Eigen'ning MPL-2.0 dan boshqa litsenziyali
> qismlari tasodifan ishlatilishini kompilyatsiya xatosiga aylantiradi.

## Ilovadagi eskiz rejimi

`SketchSession` asbob (chiziq, to'rtburchak, aylana), yopishish va avtomatik
cheklovlarni bog'laydi. Bir barmoq bilan sudrash chizadi; ikki barmoq navigatsiya
bo'lib qoladi. Chizib bo'lingach, o'lcham maydoni chiqadi va kiritilgan qiymat masofa
yoki radius cheklovi sifatida qo'shiladi. Ziddiyat chiqsa, yangi cheklov qaytarib
olinadi.

Yopishish ustuvorligi: uch nuqta, markaz, o'rta nuqta, to'r. Faqat uch nuqta va markazga
yopishganda ustma-ust tushish cheklovi qo'yiladi.

> ⚠️ Asbob faol bo'lganda bir barmoqli surish o'chadi (barmoq chizadi). Surish uchun
> asbobni o'chirish kerak; bu ma'lum cheklov, xato emas.

Har bir asosiy tekislik (XY, XZ, YZ) o'z eskiziga ega. Tekislik istalgan payt
almashtiriladi: chizish tanlangan tekislik eskiziga tushadi, qolganlari saqlanadi va
ko'rinib turadi. "Clear" faqat tanlangan tekislik eskizini tozalaydi.

Boshqaruv paneli (`SketchControls`) tor ekranda bir necha qatorga o'raladi.

> ⚠️ O'lcham maydonlarini teginish boshlanganda tozalamang. Ikki barmoqli aylantirish ham
> bitta barmoq tushishidan boshlanadi va maydonlar yo'qolib qoladi; ular faqat yangi
> shakl tugallanganda yoki asbob almashganda almashtiriladi.

## Yig'ilish

> ⚠️ `app/build.gradle.kts` dagi `ndkVersion` olib tashlanmasin. Usiz ilova moduli
> o'rnatilmagan standart NDK'ni qidiradi, `.so` fayllardan debug ma'lumotini olib
> tashlay olmaydi va APK ikki baravar kattalashadi (ogohlantirish: "Unable to strip").

> ⚠️ Sinov uchun `assembleRelease` debug kaliti bilan imzolanadi. Do'konga yuklashdan
> oldin haqiqiy kalit bilan almashtirilishi shart.

## Sinov holati

Yechuvchi o'rami (11 C++), JNI (6) va Kotlin mantig'i (yopishish, tekislik, chiziqlar)
host'da test qilinadi. `SketchSession` va boshqaruv paneli faqat qurilmada tekshiriladi.
