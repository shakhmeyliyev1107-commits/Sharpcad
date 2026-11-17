---
title: Geometriya yadrosi va JNI chegarasi
type: subsystem
created: 2026-11-17
updated: 2026-11-17
sources:
  - code: kernel/src/main/cpp/src/shape.cpp
  - code: kernel/src/main/cpp/include/cad/kernel/shape.h
  - code: kernel/src/main/cpp/jni/jni_kernel.cpp
  - code: kernel/src/main/cpp/CMakeLists.txt
  - code: kernel/src/main/kotlin/com/sharpcad/kernel/Shape.kt
  - code: kernel/build.gradle.kts
  - code: tools/build-occt.sh
verified_at: bac5213
---

# Geometriya yadrosi va JNI chegarasi

`:kernel` moduli OpenCASCADE (OCCT) ni uch qatlam orqali Kotlin'ga ochadi:

1. `cadkernel` — sof C++ (`cad::kernel::Shape`), JNI'siz; OCCT turlari faqat
   `kernel/src/main/cpp/src/shape.cpp` ichida, sarlavha faylida ko'rinmaydi.
2. `libcadcore.so` — `kernel/src/main/cpp/jni/jni_kernel.cpp`: faqat argumentlarni
   o'girish va istisnolarni tarjima qilish.
3. Kotlin API — `Kernel`, `Shape`, `Mesh`, `KernelException`.

Hozirgi imkoniyatlar: quti yaratish, STEP o'qish va yozish, chegaraviy quti, tessellyatsiya
(uchburchaklar, normallar va qirra kesmalari). Natija qanday chizilishi
[[viewport-rendering]] sahifasida.

## Xatolar

C++ tomonda OCCT'ning `Standard_Failure` istisnosi `KernelError` ga aylanadi; JNI
chegarasida har qanday C++ istisnosi `KernelException(code, message)` bo'lib Kotlin'ga
chiqadi. Kodlar barqaror: 1 noto'g'ri argument, 2 OCCT xatosi, 3 yopilgan tutqich,
4 fayl o'qish/yozish, 99 noma'lum. Ular `kernel/src/main/cpp/include/cad/kernel/error.h` va
`kernel/src/main/kotlin/com/sharpcad/kernel/KernelException.kt` da bir xil bo'lishi shart.

## Mesh buferlari

`nativeTessellate` to'rtta `ByteBuffer` qaytaradi. Ular JNI ichida
`ByteBuffer.allocateDirect` orqali JVM tomonidan ajratiladi va ma'lumot bir marta
ko'chiriladi, shuning uchun mesh `Shape` yopilgandan keyin ham yashaydi va uni GC
bo'shatadi.

> ⚠️ Mesh buferlarini `NewDirectByteBuffer` bilan native xotiraga bog'lamang. Filament
> buferni keyinroq, o'z oqimida yuklaydi; native vektor undan oldin bo'shatilsa,
> bo'shatilgan xotira o'qiladi.

## Testlar qurilmasiz ishlaydi

`./gradlew :kernel:hostTest` host uchun yig'ilgan OCCT bilan C++ testlarini (GoogleTest)
va JNI testlarini (oddiy JVM'da JUnit) ishga tushiradi. Buning uchun bitta
`CMakeLists.txt` ham Android, ham host uchun ishlaydi.

> ⚠️ Host yig'ilishida `-Wl,--disable-new-dtags` olib tashlanmasin. Usiz bog'lovchi
> RUNPATH yozadi, u esa tranzitiv bog'liqliklarga o'tmaydi va OCCT kutubxonalari
> bir-birini topa olmay test ishga tushmaydi.

> ⚠️ Test JVM'i `LC_ALL=C.UTF-8` va `-Dsun.jnu.encoding=UTF-8` bilan ishga tushiriladi
> (`kernel/build.gradle.kts`). Usiz Java lotin bo'lmagan fayl nomlarini boshqacha
> kodlaydi va STEP testi yiqiladi, garchi Android'da hammasi to'g'ri ishlasa ham.

## OCCT yig'ilishi

`tools/build-occt.sh android|host` OCCT'ni `native/third_party/occt/` ga yig'adi (git'da
yo'q). STEP uchun `DataExchange` moduli kerak, u esa XCAF orqali `ApplicationFramework`
va Visualization'ning oynasiz qismini (`TKService`, `TKV3d`) tortadi; OpenGL toolkitlari
yig'ilmaydi.

> ⚠️ OCCT faqat dinamik bog'lanadi va o'zgartirilmaydi (LGPL-2.1). Statik bog'lash yoki
> manbaga yamoq qo'yish litsenziya majburiyatlarini o'zgartiradi; `THIRD_PARTY_NOTICES.md`
> ga qarang.

> ⚠️ `build-occt.sh android` uchun `ANDROID_HOME` yoki `ANDROID_NDK` o'rnatilgan bo'lishi
> kerak, aks holda skript darhol to'xtaydi va eski kutubxonalar joyida qoladi — keyingi
> APK yig'ilishi eskirgan OCCT bilan o'tib ketishi mumkin.
