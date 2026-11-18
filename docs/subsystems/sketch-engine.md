---
title: Eskiz dvigateli va eskiz rejimi
type: subsystem
created: 2026-11-17
updated: 2026-11-18
sources:
  - code: app/src/main/kotlin/com/sharpcad/app/TextOutline.kt
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
verified_at: b9234ce
---

# Eskiz dvigateli va eskiz rejimi

`:sketch` moduli 2D eskizni (chiziq, aylana) cheklovlar bilan saqlaydi va PlaneGCS
yordamida yechadi. Qatlamlar [[kernel-jni]] dagi bilan bir xil: sof C++
(`cad::sketch::Sketch`), JNI (`libcadsketch.so`), Kotlin API. Chizish
[[viewport-rendering]] dagi ustki chiziqlar (overlay) orqali ko'rsatiladi.

## Model va yechish

Obyekt va cheklov identifikatorlari hech qachon qayta ishlatilmaydi; o'chirilgani
"o'lik" deb belgilanadi. Cheklovlar: ustma-ust tushish, gorizontal, vertikal, masofa,
radius (aylana yoki yoy), mahkamlash. Obyektlar: chiziq, aylana, yoy (soat strelkasiga qarshi, markaz + burchaklar). Har bir `solve` da PlaneGCS tizimi modeldan qaytadan quriladi —
eskizlar kichik, ikki tasvirni sinxron saqlashdan arzonroq.

Ikki chiziqli cheklovlar ham bor: teng uzunlik, parallel, perpendikulyar.

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

`SketchSession` asbob (chiziq, to'rtburchak, markazdan to'rtburchak, aylana, olti burchak, yoy, kesish, ofset, cheklov asboblari), yopishish va avtomatik
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

> ⚠️ Manfiy qiymat mumkin bo'lgan maydonlar to'liq klaviaturani ochadi: ko'p raqamli
> klaviaturalarda minus tugmasi yo'q.

Yoy: vatar sudraladi, uning ustida yarim aylana chiziladi; uchlari yopishsa ustma-ust
tushish qo'yiladi, o'lcham maydoni radius. Kesish (Trim): teginilgan bo'lak eng yaqin ikki
kesishmagacha olib tashlanadi; hech narsani kesmagan obyekt butunlay o'chadi. Geometriya
`sketch/src/main/kotlin/com/sharpcad/sketch/Trim.kt` da (sof Kotlin, `TrimTest`).

Offset: obyekt ustiga bosib sudraladi; chiziq parallel, aylana/yoy konsentrik nusxa
oladi (nusxa asl bilan bog'lanmaydi). Bir-biriga uchma-uch ulangan chiziqlar zanjiri bir butun siljiydi: burchaklar qo'shni siljigan chiziqlar kesishmasida, yangi kontur ustma-ust tushish cheklovlari bilan bog'lanadi (`sketch/src/main/kotlin/com/sharpcad/sketch/ChainOffset.kt`). Tarmoqlanish nuqtasida zanjir to'xtaydi. Cheklov asboblari (Horizontal, Vertical — bitta
chiziq; Equal, Parallel, Perpendicular — ikki chiziq ketma-ket) teginish bilan qo'yiladi;
ziddiyat bo'lsa cheklov qaytarib olinadi.

Pattern: obyekt ustiga bosib sudraladi; 3 nusxa barmoqqacha teng taqsimlanadi, so'ng
"Count" maydoni sonini o'zgartiradi (2–50). Circular pattern: obyekt ustiga bosib
markazgacha sudraladi; 6 nusxa 360° bo'ylab teng joylashadi, Count bilan o'zgaradi.
Istalgan o'lcham maydoniga `w=40` yozilsa, `w` o'zgaruvchisi seans davomida boshqa
maydonlarda ishlatiladi (`w/2`). O'lcham maydonlari ifodalarni qabul qiladi
(`20+5*2`, `2 cm`, `1in`) — `ui/workspace/src/main/kotlin/com/sharpcad/ui/workspace/Expression.kt`.

Spline: barmoq bilan erkin chiziladi; iz ~40 px oralig'ida nazorat nuqtalariga
siyraklashtiriladi va ular orqali o'tuvchi Catmull-Rom egri saqlanadi
(`sketch/src/main/kotlin/com/sharpcad/sketch/Spline.kt`). Splaynlar `SketchSession`
da alohida turadi (yechuvchida emas). Ekranda Catmull-Rom chizig'i ko'rsatiladi, profilga
esa nuqtalarning o'zi (`SketchGeometry.splines`: soni, keyin x,y) beriladi va yadro ular
orqali haqiqiy B-spline quradi (`GeomAPI_Interpolate`, boshi va oxiri ustma-ust bo'lsa
yopiq/davriy) — `kernel/src/main/cpp/src/modeling.cpp` dagi `sketchEdges`.

> ⚠️ Splayn yechuvchiga kirmaydi: unga cheklov qo'yib bo'lmaydi, Trim/Offset/Pattern ishlamaydi.

> ⚠️ Ekrandagi Catmull-Rom chizig'i va yadrodagi B-spline bir xil nuqtalardan o'tadi, lekin
> oraliqda biroz farq qiladi; hudud ajratilishi (yadro) chiziqdan sal chetga chiqishi mumkin.
> Sweep yo'li splaynni hali qabul qilmaydi.

Text: matn eskiz panelidagi maydonga yoziladi; tekislikka bosish asos chizig'i boshini,
yuqoriga sudrash balandlikni belgilaydi (oddiy bosish — 10 mm). Harf konturlari tizim
shriftidan (`Paint.getTextPath`, `PathMeasure` bilan namunalanadi) yopiq siniq chiziqlar
sifatida olinadi va profilga kesmalar bo'lib boradi (`TextOutline`), yechuvchidan tashqarida.

> ⚠️ Matn konturlari yechuvchiga kirmaydi va keyin tahrirlanmaydi (matnni o'zgartirish uchun
> Clear qilib qayta yozing). `TextOutline` Android API'ga bog'liq, host testida tekshirilmaydi.

> ⚠️ Trim obyektni o'chirib, qolgan bo'laklarni yangi obyekt sifatida qo'shadi: o'chirilgan
> obyektning cheklovlari (o'lcham, tutashish) yo'qoladi.

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

> ⚠️ O'zgaruvchilar (`Expression.variables`) faqat xotirada: loyihaga saqlanmaydi va
> qiymatlar o'zgarganda avval kiritilgan o'lchamlar qayta hisoblanmaydi.
