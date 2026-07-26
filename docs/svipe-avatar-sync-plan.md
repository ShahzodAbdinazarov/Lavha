# Svipe — Profil rasmlarini ilovalar aro sinxronlash (reja)

> Holat: **1–5-BOSQICH BAJARILDI** (2026-07-26). Backend **prod'da** (§16), lekin **feature O'CHIRILGAN** — R2 kaliti kelmaguncha (§11 oxiri). Mobil release ushlab turilibdi.
> Asos: mavjud [[profile-images-feature]] — lokal avatar-keeper (`SvipeAvatarStore` + `SvipeAvatarKeeper`).
> Repolar: mobil `~/StudioProjects/Lavha`, backend `~/StudioProjects/svipe-backend`.

---

## 1. Maqsad va qamrov

Hozir profil rasmlari **faqat o'sha qurilma onlayn ko'rgan** bo'lsa saqlanadi. Maqsad: Svipe foydalanuvchilari o'z arxivlarini **umumiy havza** orqali bo'lishsin — A user ushlab qolgan rasm, keyinchalik C user o'sha odamning profilini ochganda unga ham ko'rinsin.

**Uchta tamoyil (kelishilgan):**
1. **Serverga faqat O'CHIRILGAN rasmlar yuklanadi.** Joriy rasmlarni Telegram o'zi beradi — ularni saqlash isrof. (Lokal ushlash o'zgarmaydi: rasm tirikligida yuklanadi, serverga esa keyin yo'qolgani chiqadi.)
2. **Siqish YO'Q.** Asl bayt saqlanadi — aks holda asl ko'rinishni hech qachon ko'ra olmaymiz. Hajmni #1 tejaydi.
3. **Rasmlar shadow-user'ga bog'lanadi.** Server tomonda rasm egasining `tg_id` si bo'yicha yozuv yaratiladi (ushlagan odamga emas).

---

## 2. Hajm hisobi (nega bu ish qiladi)

- Bitta avatar ≈ **100 KB** (biz saqlaydigan o'lchamda, siqilmagan).
- **Global dedup:** `photo_id` Telegram bo'yicha yagona → bir rasm 1000 kishi ko'rsa ham bir marta saqlanadi.
- Faqat o'chirilganlar saqlangani uchun umumiy ushlangan rasmlarning taxminan **20-30%** i serverga chiqadi.

| Ko'rilgan odamlar | Ushlangan (~3/kishi) | Serverda (~25%) | Hajm |
|---|---|---|---|
| 50 000 | 150k | ~40k | **~4 GB** |
| 500 000 | 1.5M | ~375k | **~37 GB** |

**Xulosa:** Cloudflare R2 ning bepul 10 GB'i birinchi bosqichga yetadi; oshsa $0.015/GB/oy (37 GB ≈ **$0.6/oy**). Prod diskda hozir atigi 17 GB bo'sh — shuning uchun baytlar **app serverda saqlanmaydi**.

---

## 3. Saqlash: Cloudflare R2

- **Nega R2:** 10 GB bepul, va eng muhimi **egress (trafik) bepul**. Rasm xizmatini odatda trafik o'ldiradi, storage emas.
- S3-mos API → `boto3` bilan ishlaydi (backend'da hali S3/R2 integratsiyasi yo'q, yangi qo'shiladi).
- **Baytlar app server orqali o'tmaydi:** klient **presigned URL** olib, to'g'ridan-to'g'ri R2 ga PUT/GET qiladi. Bu app serverni ham diskdan, ham trafikdan xalos qiladi.
- Obyekt kaliti: `av/{tg_id}/{photo_id}.jpg` (dedup tabiiy: bir xil kalit = bir xil obyekt).
- Zaxira variant: Backblaze B2 (10 GB bepul) yoki Hetzner Volume (~€0.05/GB/oy, lokal).

---

## 4. Ruxsat modeli (ikki qatlam)

Muammo: rasm egasi Svipe ishlatmasa, uning Telegram maxfiylik sozlamasini bilmaymiz.

### Qatlam 1 — rasm egasi Svipe useri bo'lsa
Uning **o'z sozlamasi** serverda saqlanadi. **MUHIM: bu sozlama faqat CHEKLAYDI, hech qachon KENGAYTIRMAYDI** — ya'ni Qatlam 2 (Telegram-gate) har doim ustidan qo'llaniladi. Aks holda Svipe o'rnatgan odam Telegram'dagidan ko'proq ochilib qolardi.

- **`everyone` — STANDART** (Telegram'ning o'zida ham profil rasmi standarti "Everybody"). Qo'shimcha cheklov yo'q → Telegram ruxsati qanday bo'lsa shunday: egasi "faqat kontaktlarga" qo'ygan bo'lsa, arxivni ham faqat kontaktlar ko'radi.
- `contacts` — faqat mening kontaktlarim (2026-07-26 da qo'shildi, §15). Isbot ustidan **qo'shimcha** toraytirish: kontakt bo'lish isbotni almashtirmaydi.
- `nobody` — hech kimga, kontaktlarga ham berilmaydi.
- `off` (opt-out) — **umuman arxivlanmaydi**, mavjud arxivi o'chiriladi.

Bu bizning userlarga haqiqiy nazorat beradi va feature'ni himoya qilinadigan qiladi.

### Qatlam 2 — rasm egasi Svipe useri bo'lmasa (aksariyat holat)
Telegram'ning o'z ruxsatidan kelib chiqamiz. **Kalit g'oya: ko'ruvchining o'z klienti allaqachon biladi.**

> C ga X ning arxiv rasmlari beriladi **faqat** C ning klienti X ning **joriy** rasmini Telegram'dan ko'ra olsa.

**Isbot mexanizmi:** klient so'rovda o'zi **hozir ko'rib turgan `photo_id`** ni yuboradi. Uni faqat ruxsati bor odam Telegram'dan ola oladi.
- Server bu `photo_id` X uchun **yaqinda ko'rilgan tirik to'plam**da borligini tekshiradi (bu to'plamni kuzatuvchi klientlar o'zlari xabar qiladi — §6.1).
- X "faqat kontaktlarga" qo'ygan → kontakt bo'lmagan C Telegram'dan hech narsa olmaydi → isbot yo'q → arxiv berilmaydi ✓
- **Xavfsiz tomonga xato:** X hamma rasmini o'chirgan bo'lsa, kontaktlar ham joriy rasm ko'rmaydi → ularga ham berilmaydi.
- **Cheklov (ochiq):** o'zgartirilgan APK yolg'on gapirishi mumkin; ilgari kontakt bo'lgan odam eski `photo_id` ni ishlatishi mumkin. Shuning uchun ustiga rate-limit + audit + tirik-to'plam eskirishi (TTL) qo'yiladi.

---

## 5. Backend (`~/StudioProjects/svipe-backend`)

Mavjud konvensiyalar: FastAPI, SQLAlchemy (`app/db/models.py`), sxema `create_all` + `app/db/base.py` dagi idempotent mikro-migratsiyalar, auth `app/api/deps.py` → `CurrentUserDep` (**tg user id faqat tokendan**, hech qachon body'dan — v0 impersonation teshigi shunday yopilgan).

### 5.1 Jadvallar (`app/db/models.py`)

```
avatar_subject          # shadow-user: rasm EGASI
  tg_id            BIGINT PK
  visibility       TEXT     # 'everyone' | 'nobody' | 'off'  (Svipe useri bo'lmasa NULL → Qatlam 2)
  is_svipe_user    BOOL
  updated_at

avatar_photo            # o'chirilgan rasm (global dedup)
  photo_id         BIGINT PK          # Telegram photo id — global yagona
  subject_tg_id    BIGINT FK -> avatar_subject.tg_id  (index)
  photo_date       INT                # rasm qo'yilgan vaqt (tartiblash uchun)
  bytes            INT
  sha256           TEXT
  object_key       TEXT               # av/{tg_id}/{photo_id}.jpg
  uploaded_by      BIGINT             # audit uchun (ko'rsatilmaydi)
  first_seen_at, uploaded_at

avatar_live             # X uchun YAQINDA ko'rilgan tirik photo_id lar (isbot tekshiruvi uchun)
  subject_tg_id    BIGINT
  photo_id         BIGINT
  last_seen_at     TIMESTAMP          # TTL bilan eskiradi
  PK(subject_tg_id, photo_id)

avatar_access_log       # abuse audit
  requester_tg_id, subject_tg_id, granted, at
```

### 5.2 Endpointlar (`app/api/avatars.py` — yangi)

Hammasi `CurrentUserDep` bilan himoyalangan.

| Metod | Yo'l | Vazifa |
|---|---|---|
| `POST` | `/v1/avatars/observed` | Klient X ni ko'rdi: `{subject_tg_id, live_photo_ids[], deleted_photo_ids[]}`. Server `avatar_live` ni yangilaydi va **qaysi o'chgan rasmlar hali yo'qligini** qaytaradi (`missing[]`). |
| `POST` | `/v1/avatars/upload-url` | `{subject_tg_id, photo_id, bytes, sha256}` → **presigned PUT** URL. Faqat `missing` ro'yxatidagilar uchun. |
| `POST` | `/v1/avatars/commit` | Yuklash tugadi: server R2 dan hajm/hash tekshiradi, `avatar_photo` ga yozadi. |
| `GET` | `/v1/avatars/{subject_tg_id}?proof_photo_id=...` | Ruxsat tekshiruvi (§4) → arxiv ro'yxati + **presigned GET** URL lar. |
| `GET`/`PUT` | `/v1/avatars/me/settings` | O'z visibility sozlamam. |
| `DELETE` | `/v1/avatars/me` | **Opt-out:** o'z arxivimni butunlay o'chirish (R2 obyektlari ham). |

**Muhim:** `subject_tg_id` body'dan keladi (bu rasm egasi, so'rovchi emas) — lekin **so'rovchi** har doim tokendan olinadi. `uploaded_by` ham tokendan.

### 5.3 Abuse himoyasi
- Rate-limit: kuniga N ta `GET /v1/avatars/{id}` (masalan 200) va soatiga M ta yangi `subject_tg_id`.
- Ketma-ket id larni "qazish" (enumeration) aniqlash: qisqa vaqtda ko'p turli `subject_tg_id` → bloklash.
- Har bir ruxsat berish `avatar_access_log` ga yoziladi.
- Yuklash kvotasi: bir user kuniga X MB dan ko'p yuklay olmaydi (spam/zaharlash).
- **Zaharlanish xavfi:** kimdir `photo_id` ga soxta rasm yuklashi mumkin. Chora: `sha256` ni birinchi yuklovchidan qat'iylashtirish + bir necha mustaqil kuzatuvchi tasdig'i (v2).

---

## 6. Klient (mobil, `org.telegram.svipe`)

### 6.1 Kuzatish va yuklash
- `SvipeAvatarKeeper.onDialogPhotos(dialogId, photos)` allaqachon har profil ko'rilganda **tirik** ro'yxatni oladi → shu yerdan `POST /v1/avatars/observed` yuboriladi (tirik `photo_id` lar).
- O'chirilganini aniqlash allaqachon bor: `SvipeProfileImages` = (ushlangan to'plam) − (tirik to'plam). Shu farq `deleted_photo_ids` sifatida yuboriladi.
- Server `missing[]` qaytarsa → har biri uchun `upload-url` → **to'g'ridan-to'g'ri R2 ga PUT** → `commit`.
- Yuklash **faqat Wi-Fi**da va navbat bilan (batareya/trafik); `SvipeReelQueue`/`SvipeUpdater` dagi navbat naqshini qayta ishlatish.

### 6.2 Ko'rsatish
- Profil ochilganda: `GET /v1/avatars/{tg_id}?proof_photo_id=<hozir ko'rinayotgan>` → arxiv ro'yxati.
- Kelgan rasmlar lokal `SvipeAvatarStore` ga qo'shiladi (bir xil `photo_id` — dedup tabiiy) va **Profil rasmlari** tab'ida hozirgidek chiqadi.
- Manba farqlanmaydi (lokal ushlangan/serverdan kelgan) — foydalanuvchi uchun bir xil.

### 6.3 Sozlamalar UI
- Settings ichida: "Profil rasmlarim" → `everyone` / `nobody` / **"Meni arxivlamang"** (opt-out, arxivni o'chiradi).
- Sinxronni butunlay o'chirish tugmasi (lokal ushlash qoladi).

### 6.4 Yangi/tegiladigan fayllar
| Fayl | O'zgarish |
|---|---|
| `svipe/SvipeAvatarSync.java` | **YANGI** — observed/upload/commit/fetch navbati, Wi-Fi gate, throttle |
| `svipe/SvipeAvatarKeeper.java` | `onDialogPhotos` dan sync'ga xabar berish (bir qator) |
| `svipe/SvipeProfileImages.java` | Serverdan kelgan yozuvlarni birlashtirish |
| `svipe/SvipeApi.java` | Presigned URL ga **binary PUT** qo'shish (hozir faqat JSON) |
| `svipe/SvipeConfig.java` | Sync pref kalitlari |
| Settings ekrani | Visibility + opt-out UI |
| strings en/uz/ru | `checkSvipeStrings` majburiy |

---

## 7. Huquqiy / siyosat

- **Maxfiylik siyosati** (`app/privacy.py` mavjud) yangilanadi: nima saqlanadi, qancha, kim ko'radi, qanday o'chiriladi.
- **Takedown yo'li:** har kim (Svipe useri bo'lmasa ham) o'z rasmlarini o'chirishni so'ray oladigan aloqa kanali.
- **Saqlash muddati:** o'chgan rasm abadiy emas — masalan 12 oy TTL (ochiq savol).
- Play Store User Data siyosati: ma'lumot to'plash deklaratsiyasi yangilanadi.
- Bu feature odam ataylab o'chirgan rasmni saqlaydi — ruxsat qatlamlari xavfni kamaytiradi, **nolga tushirmaydi**. Bu ongli qaror sifatida qabul qilingan.

---

## 8. Bosqichlar

1. ~~**Backend poydevor**~~ — ✅ **BAJARILDI 2026-07-26** (commit `3a4e145`, `dev` branch, dev'da tirik). Batafsil §11.
2. ~~**Klient yuklash**~~ — ✅ **BAJARILDI 2026-07-26** (commit `b61d755`, mobil `dev`). Batafsil §12.
3. ~~**Klient ko'rsatish**~~ — ✅ **BAJARILDI 2026-07-26** (commit `a564363`, mobil `dev`). Batafsil §13.
4. ~~**Ruxsat va sozlamalar**~~ — ✅ **BAJARILDI 2026-07-26** (commit `1abc8d4`, mobil `dev`). Batafsil §14.
5. ~~**Siyosat + release**~~ — ✅ **BAJARILDI 2026-07-26** (backend `3f1f7cd` prod'da; mobil release **ushlab turilibdi**, sabab §16).

---

## 9. Xavflar

- **Isbot mexanizmi yumshoq** — o'zgartirilgan klient chetlab o'tishi mumkin. Rate-limit + audit bilan cheklanadi, butunlay yopilmaydi.
- **Eski kontakt** — ilgari ruxsati bor odam eski `photo_id` bilan so'rashi mumkin (tirik-to'plam TTL bilan kamaytiriladi).
- **Zaharlash** — soxta rasm yuklash (§5.3 chora).
- **Hajm o'sishi kutilganidan tez** bo'lsa — R2 narxi baribir arzon, lekin monitoring kerak.
- **Trafik** — R2 egress bepul, lekin klient tomonda mobil trafik: Wi-Fi gate majburiy.

## 10. Ochiq savollar

1. ~~Standart visibility~~ → **HAL QILINDI (owner, 2026-07-26): `everyone`**, Telegram'ning o'z standarti kabi. Shart: Qatlam 1 faqat cheklaydi, Telegram-gate'ni kengaytirmaydi (§4).
2. Arxiv saqlash muddati (TTL): 12 oy? cheksiz?
3. Yuklashni faqat Wi-Fi bilan cheklashmi yoki foydalanuvchiga tanlov?
4. v1 da faqat rasm, keyin video-avatar ham qo'shiladimi?
5. Bir rasmni necha mustaqil kuzatuvchi tasdiqlasa "ishonchli" deb hisoblaymiz (zaharlashga qarshi)?

---

## 11. 1-bosqich — bajarilgan ish (2026-07-26)

Backend `dev` branch'ida, `lavha-dev.abdinazarov.uz` da tirik. Commit `3a4e145`.

### Nima yozildi
| Fayl | Mazmuni |
|---|---|
| `app/db/models.py` | 4 jadval: `avatar_subject`, `avatar_photo`, `avatar_live`, `avatar_access_log` (rejadagidek + `status`, `kind` ustunlari) |
| `app/db/avatar_repo.py` | Barcha yozuvlar idempotent upsert; ruxsat qarori bu yerda EMAS |
| `app/api/avatars.py` | `observed` / `upload-url` / `commit` / `GET {subject}` / `me/settings` / `DELETE me` / `blob` |
| `app/stores/blobstore.py` | R2 (SigV4 qo'lda, botocore bilan solishtirib tekshirilgan) + `local` dev backend |
| `app/config.py`, `.env.example` | `LAVHA_AVATAR_*`, `LAVHA_R2_*` |
| `tools/provision_r2.py` | Bucket + lifecycle + R2 kaliti + `.env` — bitta buyruqda |
| `tools/integration_db.sh` | Dev serverda bir martalik Postgres ko'tarib DB testlarini yuritadi |
| `tools/avatar_e2e.py` | Tirik muhitga qarshi 13 qadamlik E2E |

### Rejaga nisbatan qo'shilgan qarorlar
- **`local` blob backend.** R2 bucket hali yo'q (quyida), lekin dev'da butun oqim tekshirilishi kerak edi. Klient protokoli bir xil (presigned URL ga PUT/GET) — mobil kod bir marta yoziladi, prod'da faqat `LAVHA_AVATAR_STORAGE=r2` bo'ladi.
- **boto3 EMAS.** SigV4 stdlib `hmac` bilan yozildi (~80 qator) va botocore'ning o'zi bilan bayt-ma-bayt solishtirildi (PUT/GET presign + HEAD/DELETE header — 4/4 mos). boto3 ~50 MB bog'liqlik olib kelardi va sinxron.
- **Rate-limit `avatar_access_log` ustida** (Redis emas): restart'dan omon qoladi va audit bilan bir xil manba.
- **Rad etish ham commit qilinadi** (`_deny`). `get_session` xatolikda rollback qiladi — busiz har bir rad etish o'z audit yozuvini ham, rate-limit hisobini ham o'chirib yuborardi.
- **`photo_id` bitta odamniki.** Boshqa `subject_tg_id` ostiga yozishga urinish 400 bilan rad etiladi; `sha256` birinchi yuklovchida qotib qoladi (zaharlashga qarshi arzon yarmi).

### Tekshiruv
- To'liq suite: **351 passed, 30 skipped** (yangi 21 unit test).
- DB testlari (`tools/integration_db.sh`, haqiqiy Postgres): **7/7**.
- Dev deploy'ga qarshi E2E (`tools/avatar_e2e.py`): **13/13 PASS** — B ushlaydi → yuklaydi → C isbot bilan oladi va **baytlar aynan bir xil qaytadi**; isbotsiz/xato isbot bilan 403; `nobody` haqiqiy isbotni ham yengadi; opt-out arxivni ham, baytlarni ham o'chiradi.

### ⚠️ Qolgan yagona qo'lda qadam: R2 kaliti
Handoff'dagi Cloudflare token faqat **DNS** uchun — R2 ni umuman ko'rmaydi (tekshirildi: `Authentication error`). Birinchi kalitni faqat dashboard'da yasash mumkin (yoki Global API Key bilan) — buni skript qila olmaydi. Undan keyingisi avtomatik:

```bash
# Cloudflare dashboard -> My Profile -> API Tokens -> Create Token
#   ruxsat: "Workers R2 Storage: Edit" (+ istasa "User API Tokens: Edit")
CF_API_TOKEN=<yangi token> tools/provision_r2.py --bucket svipe-avatars \
    --write-env root@49.12.47.209:/home/main/lavha-dev --ssh-key ~/.ssh/lavha_deploy
```

Shu buyruq bucket'ni yaratadi, S3 kalitlarini oladi, `.env` ni yangilaydi va app'ni qayta ko'taradi. Undan keyin dev `local` dan `r2` ga o'tadi va prod uchun ham xuddi shu buyruq (`/home/main/svipe-prod`) ishlaydi.

### Keyingi qadam
2-bosqich: klient tomonda `SvipeAvatarSync` (observed + o'chganlarni yuklash, Wi-Fi gate) — mobil repo.

---

## 12. 2-bosqich — klient yuklash (2026-07-26)

Mobil `dev` branch, commit `b61d755`. Emulyatorda dev backend'ga qarshi tekshirilgan.

### Nima yozildi
| Fayl | Mazmuni |
|---|---|
| `svipe/SvipeAvatarSync.java` | **YANGI** — har profil ko'rilganda tirik + o'chgan id larni xabar qiladi, `missing[]` ni yuklaydi (upload-url → PUT → commit) |
| `svipe/SvipeApi.java` | `putFile()` — absolut presigned URL ga oqim bilan PUT (**bearer YO'Q** — imzoning o'zi ruxsat) + `RawCallback` |
| `svipe/SvipeAvatarKeeper.java` | Bir qator ulanish (lokal ushlash bilan bir xil seam) |
| `svipe/SvipeConfig.java` | `PREF_AVATAR_SYNC` (standart yoqiq), `PREF_AVATAR_SYNC_WIFI_ONLY` (standart yoqiq) |
| `test/.../SvipeAvatarSyncTest.java` | 6 test: to'liqlik sharti, o'chganlar farqi, signature, throttle, yuklash tanlash/cheklash |

### Ikki muhim qoida (kod ichida)
1. **O'chganini taxmin qilmaymiz.** Telegram rasmlar ro'yxatini sahifalab beradi va modelda yuklanmagan sahifalar o'rniga `null` turadi — ya'ni id yo'qligi ko'pincha "hali kelmagan" degani, "o'chirilgan" emas. Shuning uchun o'chirish faqat ro'yxat **isbotlangan to'liq** bo'lganda xabar qilinadi (`liveSetComplete`: `loaded && !fromCache && slots == loadedPhotos`). Tab taxmin ko'rsatishi mumkin, yuklash — yo'q.
2. **Kam xabar, xushmuomala yuklash.** Har subject uchun signature + 6 soat oralig'i → o'sha profilni qayta ochish jim; haqiqiy o'zgarish esa darhol ketadi. Yuklash: faqat Wi-Fi (standart), bir ko'rishda ko'pi bilan 3 ta, birin-ketin.

### Tekshiruv (svipe_test emulyator → lavha-dev)
- Kontakt profili ochildi → `avatar_subject` da so'rovchi (`everyone`) + shadow-user (visibility NULL), `avatar_live` da 3 ta tirik id, `avatar_access_log` da `observe/granted`.
- Ushlangan-so'ng-o'chgan rasm holati yaratilib qayta ochildi → `upload-url` → PUT → `commit`: qator `stored`, 41680 bayt, `uploaded_by` = so'rovchi.
- **Serverdagi blob qurilmadagi fayl bilan bayt-ma-bayt bir xil** (sha256 `c174d14e57ec…`).
- Rasmsiz profil ochilganda hech narsa yuborilmadi (to'g'ri: xabar qilishga narsa yo'q).
- Test qatorlari va bloblari tozalandi; qurilmadagi soxta yozuv qaytarildi.

### Eslatma
Yuklash standart holatda **yoqiq**, lekin UI hali yo'q — foydalanuvchiga ko'rinadigan sozlama 4-bosqichda, maxfiylik matni 5-bosqichda qo'shiladi. Hozircha bu faqat beta build'da (dev backend) ishlaydi.

### Keyingi qadam
3-bosqich: `GET /v1/avatars/{id}` + isbot bilan o'qish va Profil rasmlari tab'iga birlashtirish — A ushlagan rasm C da ko'rinsin.

---

## 13. 3-bosqich — klient ko'rsatish (2026-07-26)

Mobil `dev`, commit `a564363`. Halqa yopildi: ushlangan rasm serverga chiqadi va boshqa qurilmaga qaytadi.

### Nima yozildi
| Fayl | Mazmuni |
|---|---|
| `svipe/SvipeAvatarSync.java` | `fetchArchive()` — `GET /v1/avatars/{id}?proof_photo_id=…`, `pickDownloads()`, birin-ketin yuklab olish (bir ko'rishda 5 tagacha) |
| `svipe/SvipeApi.java` | `getFile()` — presigned GET to'g'ridan-to'g'ri diskka, `.tmp` + rename (uzilgan yuklanish yarim rasm qoldirmaydi) |
| `messenger/NotificationCenter.java` | `svipeAvatarArchiveUpdated` (akkaunt bo'yicha) |
| `ui/Components/SharedMediaLayout.java` | Shu xabarda `updateTabs(false)` + adapter yangilash |

### Uch qaror
1. **Isbot — hozir ko'rinayotgan rasm id si.** Uni faqat Telegram ko'rsatgan odam ola oladi. Tirik rasm yo'q bo'lsa umuman so'ramaymiz (403 va bekorga log yozilmaydi). **O'z arxivimga isbot kerak emas** — yangi qurilmada o'z o'chgan rasmlarim shu yo'l bilan qaytadi.
2. **Fetch `observed` dan KEYIN.** Biz endigina yuborgan tirik id lar serverdagi "tirik to'plam"ni yangilaydi — ya'ni o'z isbotimiz tekshiriluvchi bo'ladi. (Ma'lum cheklov: eski kontakt eski id ni qayta-qayta xabar qilib TTL ni cho'zishi mumkin — §4 dagi bir xil xavf, yopilmagan.)
3. **Kelgan rasm lokal store'ga yoziladi**, ya'ni tab uchun "serverdan kelgan" degan tushuncha yo'q — `SvipeProfileImages` uni odatdagidek "Deleted" deb ko'rsatadi. Lekin tabning **ko'rinishi** shu songa bog'liq, shuning uchun yangilash `updateTabs` orqali: hamma rasmi o'chirilgan odamda tab umuman yo'q edi, havza javob bermaguncha.

### Tekshiruv (svipe_test emulyator → lavha-dev)
- Profil ochildi → `fetch / granted=t / reason=ok` (haqiqiy isbot bilan).
- Rasm lokal o'chirildi (fayl + ledger) → profil qayta ochildi → **havzadan bayt-ma-bayt qaytdi** (41680) va ledger'ga yozildi.
- **Profil rasmlari tabida "Deleted" yorlig'i bilan ko'rindi** (skrinshot bilan tasdiqlangan).
- Test qatorlari, bloblari va qurilmadagi soxta holat tozalandi.

### Keyingi qadam
4-bosqich: visibility/opt-out sozlamalari UI (`everyone` / `nobody` / "meni arxivlamang") + sync'ni o'chirish tugmasi; strings en/uz/ru (`checkSvipeStrings` majburiy).

---

## 14. 4-bosqich — ruxsat va sozlamalar UI (2026-07-26)

Mobil `dev`, commit `1abc8d4`. Emulyatorda dev backend'ga qarshi tekshirilgan.

### Qayerda
**Settings → Privacy and Security → "Profile photo archive"** — Telegram'ning o'z "Profile Photos" qatori ostida, chunki bu o'sha savolning bir qatlam davomi. Qator qiymati qo'shnilari kabi joriy tanlovni ko'rsatadi (lokal kesh; server manba bo'lib qoladi).

### Ekran (`SvipeAvatarSettingsActivity`)
| Bo'lim | Nima |
|---|---|
| Arxivdagi rasmlarimni kim ko'radi | Radio: **Hamma** / **Hech kim** / **Rasmlarimni arxivlamang** — qiymat **serverda** saqlanadi (boshqa odamlarning qurilmalaridan kelgan so'rovlarga ta'sir qilishi kerak) |
| Izoh | "Faqat Telegram allaqachon ruxsat bergan odamlarga ko'rinadi; bu sozlama faqat toraytiradi, kengaytirmaydi" |
| Sinxronlash | 2 ta lokal switch: "Saqlagan rasmlarimni ulashish" + "Faqat Wi-Fi orqali yuklash" (ikkinchisi birinchisiga bog'liq, o'chsa kulrang bo'ladi) |
| O'chirish | "Arxivdagi rasmlarimni o'chirish" — **sonini ko'rsatadi**, tasdiq so'raydi |

- "Rasmlarimni arxivlamang" — haqiqiy opt-out: mavjud arxivni ham o'chiradi, shuning uchun oldin tasdiq so'raladi.
- Switch'lar **lokal ushlashga tegmaydi** — o'chirsang ham Profil rasmlari tabi ishlayveradi, faqat ulashish to'xtaydi.
- Ekran ilovaning **o'z cell'laridan** qurilgan (`HeaderCell`/`RadioCell`/`TextCheckCell`/`TextSettingsCell`/`TextInfoPrivacyCell`), ya'ni boshqa sozlama ekranlariga taqlid emas, o'zi.

### Strings
`SvipeAvatar*` kalitlari en/uz/ru da — `checkSvipeStrings`: **109 kalit, hammasi uch tilda, kodda qattiq yozilgan matn yo'q**.

### Tekshiruv (svipe_test emulyator → lavha-dev)
- Ekran ochilganda joriy qiymat serverdan yuklandi (`everyone`, arxiv 0).
- **Hech kim** → `avatar_subject.visibility = nobody` ✓
- **Rasmlarimni arxivlamang** → tasdiq oynasi → `visibility = off` ✓
- **Hamma** → `visibility = everyone` ✓
- Master switch o'chirildi → pref `svipe_avatar_sync=false`, Wi-Fi qatori kulrang/bosilmas ✓
- Privacy qatorida qiymat "Everyone" bo'lib chiqdi ✓
- Test qatorlari tozalandi.

### Keyingi qadam
5-bosqich: maxfiylik siyosati (`app/privacy.py`), Play Store data-safety deklaratsiyasi, takedown kanali, R2 ulash, dev→prod va `.web` release.

---

## 15. "Kontaktlarim" varianti (2026-07-26)

Backend `a49456f`, mobil `1fb282b`. Owner so'rovi bilan qo'shildi — dastlab qoldirilgan edi.

### Nega alohida ma'lumot kerak
"Bu so'rovchi subject'ning kontaktimi?" degan savolga **boshqa hech narsa javob bera olmaydi**: Telegram uchinchi tomonga kontakt ro'yxatini aytmaydi, so'rovchining o'z da'vosi esa aynan hujumchi aytadigan gap. Shuning uchun javob **subject'ning o'z qurilmasidan** keladi.

### Narxi ochiq aytilgan
Bu — feature'ning **ijtimoiy graf saqlaydigan yagona joyi**. Shuning uchun imkon qadar tor:
- faqat `contacts` tanlangan paytda yoziladi (`PUT /v1/avatars/me/contacts` boshqa holatda **409**);
- butunlay almashtiriladi (tarix yo'q);
- boshqa variant tanlansa / opt-out / arxiv o'chirilsa — **darhol o'chiriladi** (`set_visibility` ichida);
- faqat **id lar** — ism, telefon va boshqa hech narsa emas; server chegarasi 5000.

**Xeshlanmaydi — ataylab.** Telegram id maydoni kichik va sanab chiqiladigan, ya'ni har qanday xesh brute-force bilan qaytariladi: himoya ko'rinishini beradi-yu, bermaydi, faqat ma'lumotni **bizning o'zimiz** uchun audit qilish va o'chirishni qiyinlashtiradi.

### Mantiq
`decide_access`: `contacts` — isbot **ustidan** toraytirish. Kontakt bo'lish isbotni almashtirmaydi (`no_proof`), isbot esa kontakt emaslikni yengmaydi (`not_contact`).

### UI va rozilik matni
Radio ro'yxatida "Kontaktlarim" (Hamma dan keyin). Tanlashdan **oldin** dialog to'liq aytadi (uch tilda):

> "Kim kontaktingiz ekanini tekshirish uchun Svipe kontaktlaringizning Telegram ID larini **o'z serverida** saqlaydi. Ular **faqat shu tekshiruv uchun** ishlatiladi — **hech qachon uchinchi tomonga berilmaydi va boshqa hech qanday maqsadda ishlatilmaydi** — hamda boshqa variantni tanlashingiz bilan **o'chiriladi**."

Bu va'da 5-bosqichdagi maxfiylik siyosatida **so'zma-so'z takrorlanishi shart** (va Play data-safety deklaratsiyasiga "Contacts — collected, not shared" bo'lib tushishi kerak).

### Tekshiruv
- Haqiqiy Postgres: 8/8 integration (yangi to'liq kontakt ssenariysi bilan) + 355 unit.
- Emulyator → dev: "Kontaktlarim" tanlandi → **430 ta kontakt id yozildi**, `visibility=contacts`; "Hamma" ga qaytarildi → **0 qator qoldi**.

---

## 16. 5-bosqich — siyosat va prod (2026-07-26)

### Maxfiylik siyosati (`app/privacy.py`, svipe.uz/privacy — **tirik**)
Yangi **4-bo'lim "Profile photo archive"**: nima saqlanadi, **kim ko'ra oladi** (faqat Telegram o'zi joriy rasmni ko'rsatadigan odam — isbotsiz so'rov rad etiladi), foydalanuvchi nazorati (Everyone/My contacts/Nobody/"arxivlamang" + o'chirish), va **Svipe ishlatmaydigan odam uchun** takedown yo'li. Kontaktlar haqidagi va'da 3-bo'limda **ilovadagi rozilik matni bilan bir xil so'zlarda**: faqat shu tekshiruv uchun, uchinchi tomonga berilmaydi, boshqa maqsadda ishlatilmaydi, sozlama o'zgarsa o'chiriladi.

### Takedown avtomatlashtirildi
`tools/avatar_takedown.py --tg-id <id>` — bitta buyruq: rasm qatorlari + **baytlar** + tirik to'plam + kontakt ro'yxati o'chadi, `visibility='off'` qo'yiladi. **Access log ataylab qoldiriladi** — bu "kim so'ragan" yozuvi, ya'ni o'sha odamning o'zi ko'rishi mumkin bo'lgan narsa. Skript **image ichida** (`docker/Dockerfile`), ya'ni so'rov kelganda serverda darhol ishlaydi (dev'da haqiqiy ma'lumotda sinovdan o'tkazildi).

### Play Data safety
`docs/svipe-play-data-safety.md` — Play Console formasiga so'zma-so'z ko'chiriladigan javoblar. Muhimi: **Contacts — Collected: Yes, Shared: No, Optional**; **Photos — Collected: Yes, Shared: No**.

### Prod holati (MUHIM)
Backend prod'da (`main` `3f1f7cd`), lekin **`LAVHA_AVATAR_SYNC_ENABLED=false`**:
- `POST /v1/avatars/observed` → `{"enabled": false}` (klient shu javobdan keyin sessiya davomida umuman so'ramaydi — `serverDisabled`);
- `PUT /v1/avatars/me/contacts` → **503** (o'chirilgan holat "hech narsa yig'ilmaydi" degani bo'lishi kerak);
- prod bazasida 5 ta jadval yaratildi, **0 qator**.

**Sabab:** R2 bucket hali yo'q, prod diskda esa ~17 GB bo'sh — `local` saqlashni prod'da yoqish rejaga zid. Kalit kelgach: `tools/provision_r2.py --write-env root@23.88.110.173:/home/main/svipe-prod` → `.env` da `LAVHA_AVATAR_STORAGE=r2` + `LAVHA_AVATAR_SYNC_ENABLED=true`.

### Ushlab turilgan ish
- **R2 kaliti** (owner dashboard'dan yasashi kerak) — §11 oxiridagi buyruq.
- **Mobil release** (.web / Play) — storage yo'q ekan, feature ishlamaydi; kalit ulangach chiqariladi.
