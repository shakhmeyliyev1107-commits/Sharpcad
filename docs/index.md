# SharpCAD -- documentation

Android planshetlari uchun sensorli 3D CAD modeler: Kotlin + Compose, OCCT yadrosi (JNI), Filament render.

This directory is a wiki maintained by an LLM: linked markdown pages, nothing outside the
repository. The rules are in [`CLAUDE.md`](CLAUDE.md).

## Start here

1. [`../CLAUDE.md`](../CLAUDE.md) -- stek, arxitektura qoidalari, nomlash
2. [[kernel-jni]] -- geometriya yadrosi va JNI chegarasi
3. [[viewport-rendering]] -- Filament render, kamera, imo-ishoralar
4. [[manual-requirements]] -- nima qurilishi kerak va qaysi biri tayyor

Viki ish davomida o'sadi: yangi sohaga tegilganda uning sahifasi qo'shiladi.

---

## concepts

_(hali sahifa yo'q)_

## architecture

_(hali sahifa yo'q)_

## subsystems

- [[kernel-jni]] -- OCCT o'rami, JNI, xato kodlari, mesh buferlari, host testlari
- [[viewport-rendering]] -- kadr sikli, materiallar, kamera, imo-ishoralar
- [[sketch-engine]] -- PlaneGCS o'rami, cheklovlar, yopishish, eskiz rejimi
- [[modeling-history]] -- xususiyatlar tarixi, qayta qurish, avtomatik birlashtirish/kesish, tanlash

## decisions

_(hali sahifa yo'q)_

## reference

- [[manual-requirements]] -- qo'llanmadan olingan ish oqimi talablari va holati

## operations

_(hali sahifa yo'q)_

## history

_(hali sahifa yo'q)_

---

## Using this wiki

```
/docs-sync     right after a deploy or release -- reads only what changed since verified_at
/docs-lint     integrity check (orphans, broken links, broken sources, stale, unverified)
/docs-query    ask the docs. If it is not there, the answer says so
/docs-status   commits not yet reflected, stale pages, recent work
```

Every page carries `verified_at`, the commit it was checked against. Change code, run
`/docs-sync`.
