---
title: Ko'rinish oynasi va render
type: subsystem
created: 2026-11-17
updated: 2026-11-17
sources:
  - code: viewport/src/main/kotlin/com/sharpcad/viewport/ViewportRenderer.kt
  - code: viewport/src/main/kotlin/com/sharpcad/viewport/Viewport.kt
  - code: viewport/src/main/kotlin/com/sharpcad/viewport/OrbitCamera.kt
  - code: viewport/src/main/kotlin/com/sharpcad/viewport/NavigationGestures.kt
  - code: viewport/src/main/materials/overlay_color.mat
  - code: viewport/src/main/materials/cad_shaded.mat
  - code: tools/build-materials.sh
  - code: gradle/libs.versions.toml
verified_at: ad36c7d
---

# Ko'rinish oynasi va render

`:viewport` moduli Filament'ni Compose ichidagi `SurfaceView` da ishlatadi. U OCCT'ni
bilmaydi: geometriyani `RenderMesh` (to'g'ridan-to'g'ri `ByteBuffer`lar) ko'rinishida
oladi. Mesh qayerdan kelishi [[kernel-jni]] sahifasida.

## Tuzilishi

- `Viewport` (Composable) `SurfaceView` ni joylaydi va hayot siklini bog'laydi.
- `ViewportController` — ilovaning tutqichi: `setModel`, `fitView`, kadr statistikasi.
- `ViewportRenderer` — Filament dvigateli, sahna (to'r, o'qlar, bitta model) va kadr sikli.
- `OrbitCamera` — sof matematika (Z yuqoriga), JVM'da test qilinadi.
- `NavigationGestures` — bir barmoq surish, ikki barmoq aylantirish va chimdib
  masshtablash, ikki marta bosish sig'dirish.

## Navigatsiya va chizish kiritishi

Aylantirish tezligi ekranning qisqa tomoniga bog'langan: u bo'ylab bitta surish chorak
kam to'liq aylanish (270°) beradi, shuning uchun vertikal va gorizontal holatda bir xil
seziladi. `ViewportController.onSingleTouch` o'rnatilgan bo'lsa, bir barmoqli imo-ishora
avval unga taklif qilinadi (eskiz asboblari); u rad etsa, odatdagidek suriladi. Ikkinchi
barmoq tushishi bilan chizish bekor qilinadi va boshqaruv navigatsiyaga qaytadi.

Eskiz chiziqlari `setOverlayLines` orqali, chuqurlik tekshiruvisiz (`overlay_color`
materiali) hamma narsaning ustidan chiziladi.

> ⚠️ Aylantirish tezligini ekran balandligiga bog'lamang. Vertikal telefonda gorizontal
> surish shunda atigi ~80° beradi va to'liq aylantirish "og'ir va sekin" tuyuladi —
> foydalanuvchi aynan shundan shikoyat qilgan.

> ⚠️ Ikki barmoqli imo-ishora ham bitta barmoq tushishidan boshlanadi. Chizish asbobi
> `DOWN` da qilgan har qanday ishni `CANCEL` da to'liq qaytarishi kerak.

## Kadr sikli

Kadrlar Choreographer orqali chiziladi va faqat biror narsa o'zgarganda
(`requestRender`): bo'sh turgan oyna GPU ishlatmaydi. Kadr statistikasi vsync vaqt
belgilaridan olinadi va faqat ketma-ket chizilgan kadrlar orasida hisoblanadi.

> ⚠️ Kamera yoki sahnani o'zgartirgan har bir joy `requestRender()` chaqirishi kerak.
> Aks holda o'zgarish ekranga chiqmaydi, chunki sikl o'z-o'zidan qayta chizmaydi.

> ⚠️ Compose qayta kompozitsiyasi hech qachon renderni ishga tushirmasligi yoki
> to'smasligi kerak. Renderer holati `remember` ichida, Compose holatidan tashqarida.

## Materiallar

`*.mat` fayllar `matc` bilan `assets/materials/*.filamat` ga kompilyatsiya qilinadi va
natija git'ga qo'shiladi, shuning uchun oddiy Gradle yig'ilishiga `matc` kerak emas.
`cad_shaded` — "old chiroq" soyalash: yorqinlik sirt normali bilan qarash yo'nalishi
orasidagi burchakka bog'liq, sahna chiroqlari yo'q. Normal `custom0` atributi orqali
keladi.

> ⚠️ `matc` aynan `gradle/libs.versions.toml` dagi Filament versiyasidan bo'lishi shart.
> Boshqa versiyada kompilyatsiya qilingan material ilova ishga tushganda yuklanmaydi.
> Filament versiyasi o'zgarsa, `tools/build-materials.sh` ni qayta ishga tushiring.

> ⚠️ Material fayllarini `AssetManager.open` bilan o'qing, `openFd` bilan emas. APK ichida
> `.filamat` siqilgan holda saqlanadi va `openFd` `FileNotFoundException` tashlaydi —
> 0-bosqichda ilova aynan shu sababdan ochilmagan. Kutubxona modulidagi `noCompress`
> ilova APK'siga ta'sir qilmaydi.

> ⚠️ Filament 1.80.1 Kotlin stdlib 2.4 va compileSdk 37 ni talab qiladi. Kotlin yoki
> compileSdk'ni pasaytirish yig'ilishni buzadi; AGP 8.13 uchun ogohlantirish
> `gradle.properties` dagi `android.suppressUnsupportedCompileSdk` bilan o'chirilgan.

## Ranglar

Ranglar kodda sRGB ko'rinishida yoziladi va Filament'ga chiziqli qiymatga o'girilib
beriladi (`srgbToLinear`). Post-ishlov yoqilgan (MSAA, FXAA, dinamik aniqlik), ton
xaritasi chiziqli, shuning uchun ekrandagi rang yozilgan rangga mos keladi.

## Sinov holati

Kamera matematikasi va kadr statistikasi JVM testlari bilan qoplangan. Render,
imo-ishoralar va materiallarning ko'rinishi faqat qurilmada tekshiriladi; bu muhitda
emulyator yo'q.
