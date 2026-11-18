---
title: Modellashtirish va tarix
type: subsystem
created: 2026-11-17
updated: 2026-11-18
sources:
  - code: app/src/main/kotlin/com/sharpcad/app/MainActivity.kt
  - code: kernel/src/main/cpp/src/modeling.cpp
  - code: document/src/main/kotlin/com/sharpcad/document/Feature.kt
  - code: document/src/main/kotlin/com/sharpcad/document/ModelBuilder.kt
  - code: document/src/main/kotlin/com/sharpcad/document/Document.kt
  - code: app/src/main/kotlin/com/sharpcad/app/ModelController.kt
  - code: app/src/main/kotlin/com/sharpcad/app/MeshTools.kt
  - code: viewport/src/main/kotlin/com/sharpcad/viewport/AxisDrag.kt
  - code: kernel/src/main/kotlin/com/sharpcad/kernel/KernelNative.kt
  - code: app/src/main/kotlin/com/sharpcad/app/WorkspaceState.kt
  - code: document/src/main/kotlin/com/sharpcad/document/MeshExport.kt
verified_at: b9234ce
---

# Modellashtirish va tarix

Geometriyaning yagona manbasi — `:document` dagi xususiyatlar (feature) ro'yxati.
Har bir o'zgarish ro'yxatni almashtiradi va `ModelBuilder` barcha jismlarni noldan
qayta quradi; natija `Document.state` orqali UI ga keladi. Jism identifikatori — uni
yaratgan xususiyatning `id` si. Yadro amallari [[kernel-jni]] da, eskiz [[sketch-engine]]
da, talablar [[manual-requirements]] da.

## Loft

`LoftFeature` profillar ro'yxatidan jism quradi: har bir profil birinchi yuzasining
tashqi konturi `BRepOffsetAPI_ThruSections` ga beriladi (`kernel/src/main/cpp/src/modeling.cpp`).
Ilovada eskiz profili tanlanganda "Loft" tugmasi unga parallel barcha eskizlangan
tekisliklarni normal bo'yicha tartiblab ishlatadi. Buning uchun siljigan qurilish
tekisliklari bor: XY+50, XY+100, XZ+50, YZ+50 (`SketchSession.PLANES`).

## Sweep

`SweepFeature` profil birinchi yuzasini yo'l bo'ylab `BRepOffsetAPI_MakePipe` bilan
suradi. Yo'l — profilni kesib o'tuvchi (parallel bo'lmagan) birinchi eskizlangan
tekislikdagi chiziq va yoylar; aylanalar yo'lga kirmaydi (`ModelController.sweepFromRegion`).

> ⚠️ Yo'l qirralari `BOPAlgo_Tools::EdgesToWires` bilan bitta simga yig'iladi; uzilgan yo'lda
> faqat birinchi bo'lagi ishlatiladi.

> ⚠️ Loft har profilning faqat birinchi yuzasini oladi; ichki teshiklar e'tiborga olinmaydi.

## Xususiyatlar

Import (STEP/IGES), Extrude, Revolve, Fillet, Chamfer, Push/Pull, Shell, Boolean, Move/Copy, Mirror, Pattern, Delete body. Yuza tanlanganda uning maydoni va jism hajmi ko'rsatiladi. Extrude va
Revolve eskiz geometriyasining nusxasini (`Profile`) saqlaydi, shuning uchun eskiz
keyin o'zgarsa model jim o'zgarmaydi. Tanlangan hududlar `Profile.regions` da indeks
sifatida turadi.

Avtomatik natija (`Operation.AUTO`): yangi jism hech bir jismga tegmasa — yangi jism;
butunlay jism ichida bo'lsa — kesish; qisman kirsa yoki tegib tursa — birlashtirish.
Eng yangi jismdan boshlab tekshiriladi.

> ⚠️ Barcha yadro chaqiruvlari bitta `KernelDispatcher` oqimida bajariladi. OCCT
> obyektlari oqimga xavfsiz emas; `Shape` ni boshqa oqimda ishlatmang.

> ⚠️ Yuza indekslari (`Selection.face`, `PushPullFeature.face`) qayta qurishdan keyin
> boshqa yuzani ko'rsatishi mumkin (topologik nomlash muammosi). Hozircha shunday
> qabul qilingan; oldingi xususiyat o'zgarsa, keyingilari xato berishi yoki boshqa
> yuzaga qo'llanishi mumkin.

> ⚠️ `BuildResult` jismlarga egalik qiladi. Jismni almashtirganda eskisini yoping
> (`replace`), aks holda native xotira oqib ketadi; boshqa joyda saqlab qolmang.

## Fayllar

Import: STEP va IGES (o'quvchi kengaytmadan tanlanadi). Eksport: STEP va IGES (aniq
geometriya), STL (binar), OBJ va 3MF (mesh, 0.01 mm og'ish bilan qayta
tessellyatsiya qilinadi, millimetrda). Mesh yozuvchilari sof Kotlin (`MeshExport`).

> ⚠️ Pattern nusxalari jism identifikatori `feature.id * 1000 + k` bilan yaratiladi.
> Boshqa xususiyatlarning id lari 1000 dan oshsa to'qnashuv bo'lishi mumkin; nusxalar
> soni 200 bilan cheklangan.

## Bekor qilish va qaytarish

Butun xususiyat ro'yxatlari stekda saqlanadi (100 tagacha). Yangi o'zgarish qaytarish
stekini tozalaydi. Import qilingan STEP fayl ilova xotirasiga
(`filesDir/imports`) ko'chiriladi va har qayta qurishda o'sha yerdan o'qiladi.

## Ilovadagi oqim

`ModelController` tegishni boshqaradi: avval eng yaqin eskiz hududi, keyin jism
yuzasi tanlanadi; bo'sh joy tanlovni bekor qiladi. Tanlovga qarab panelda mos asboblar
chiqadi (hudud: Extrude/Revolve; yuza: Push/Pull, fillet, faska, bul amallari).
Yangi xususiyat qo'shilgach, uning parametrlari darhol tahrir panelida ochiladi.

> ⚠️ Chizish asbobi faol bo'lganda tegish tanlovga yuborilmaydi
> (`MainActivity`): GestureDetector bitta tegishni barmoq ko'tarilgandan keyin
> bildiradi, o'shanda chizish allaqachon tugagan bo'ladi.

## To'g'ridan-to'g'ri tahrirlash (sudrash)

Eskiz rejimi o'chiq bo'lsa, bir barmoqli tegish `ModelController.onDrag` ga boradi
(`MainActivity` dagi `viewport.onSingleTouch`):

- tanlangan yuzadan sudrash — yuza normali bo'ylab Push/Pull; masofa ekran nuri va
  normal chizig'ining eng yaqin nuqtasidan olinadi (`AxisDrag.along`);
- tanlangan qirradan sudrash — shu qirraning Fillet radiusi (ekrandagi sudrash uzunligi × mm/piksel);
- Move paneli ochiq bo'lsa, jismni sudrash — kameraga eng tik dunyo tekisligida siljitish;
  paneldagi "Drag: Rotate" tanlansa, gorizontal sudrash tanlangan o'q atrofida buradi (0.5°/piksel, 5° qadam).

Birinchi harakatda xususiyat `Document.add` bilan qo'shiladi, keyingilari
`Document.amend` (undo qadamisiz) bilan yangilanadi; barmoq ko'tarilganda aniq qiymat
uchun panel ochiladi. Qirra tanlash: tegish qirradan 10 pikselgacha bo'lsa, qirra
yuzadan ustun (`Shape.pickEdge`, ajratilgan qirra eskiz overlay'i orqali chiziladi).

> ⚠️ `viewport.onSingleTouch` bitta slot: uni faqat `MainActivity` o'rnatadi va
> eskiz faolligiga qarab `SketchSession.onTouch` yoki `ModelController.onDrag` ga
> yo'naltiradi. `SketchSession` uni o'zi qayta yozmasin, aks holda sudrash ishlamay qoladi.

> ⚠️ DOWN da tegishni egallash (true qaytarish) sinxron hal qilinadi: yadroga murojaat
> qilib bo'lmaydi, shuning uchun yuza/jism urilishi Kotlin'da mesh uchburchaklari bo'yicha
> tekshiriladi (`MeshTools.rayHitFace`). Aks holda bo'sh joydan aylantirish o'g'irlanadi.

> ⚠️ Qirra va yuza indekslari xususiyatda saqlanadi; oldingi xususiyat topologiyani
> o'zgartirsa, indeks boshqa qirraga tushishi mumkin (yuza uchun ham shunday).

## Rail tugmalari (Add, Transform, Measure, Views, Screenshot)

`MainActivity` qo'shimcha rail tugmalarini `Workspace(railActions = …)` orqali ulaydi:
Add — kub/silindr/shar (`ModelController.addPrimitive`, yangi jism, keyin tahrir paneli);
Transform — tanlangan jismga Move; Measure — modelda ikki nuqta orasidagi masofa
(nuqta = `Document.pick` urilish nuqtasi, chiziq eskiz overlay'ida); Snap settings —
eskizda yopishishni yoqish/o'chirish (`SketchSession.toggleSnapping`); Views — Ustdan/Old/
O'ngdan/Izometrik; Screenshot — `PixelCopy` bilan Pictures/SharpCAD ga PNG
(`WorkspaceState.screenshot`). Search, Tools, Units hali ulanmagan.

> ⚠️ Add va Measure ham `closePanels()` ro'yxatida: yangi yon panel qo'shsangiz uni ham
> shu yerga qo'shing, aks holda panellar yana bir-birining ustiga chiqadi.

> ⚠️ Shar `RevolveAxis.U` atrofida XY dagi yarim doiradan quriladi; U o'qi tekislikning
> X o'qi bo'ylab boshlang'ich nuqtadan o'tadi (`DocumentTest.primitivesFromTheAddPanel`).

## Sinov holati

Yadro amallari (C++ va JNI) va `ModelBuilder`/`Document` (haqiqiy yadro bilan, host'da)
test qilinadi. `ModelController` va panellar faqat qurilmada tekshiriladi.
