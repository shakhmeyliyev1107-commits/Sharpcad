---
title: Modellashtirish va tarix
type: subsystem
created: 2026-11-17
updated: 2026-11-17
sources:
  - code: kernel/src/main/cpp/src/modeling.cpp
  - code: document/src/main/kotlin/com/sharpcad/document/Feature.kt
  - code: document/src/main/kotlin/com/sharpcad/document/ModelBuilder.kt
  - code: document/src/main/kotlin/com/sharpcad/document/Document.kt
  - code: app/src/main/kotlin/com/sharpcad/app/ModelController.kt
  - code: app/src/main/kotlin/com/sharpcad/app/MeshTools.kt
  - code: app/src/main/kotlin/com/sharpcad/app/WorkspaceState.kt
  - code: document/src/main/kotlin/com/sharpcad/document/MeshExport.kt
verified_at: 22ed89d
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

## Sinov holati

Yadro amallari (C++ va JNI) va `ModelBuilder`/`Document` (haqiqiy yadro bilan, host'da)
test qilinadi. `ModelController` va panellar faqat qurilmada tekshiriladi.
