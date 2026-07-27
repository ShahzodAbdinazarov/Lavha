# Svipe — O'chirilgan/tahrirlangan xabarlar arxivini serverda sinxronlash (reja)

> Holat: **REJA (kod yozilmagan)**. 2026-07-27 muhokamasidan chiqqan, kod langarlari `ground-message-sync-plan` workflow (4 parallel o'quvchi) bilan grounded.
> Asos: mavjud **lokal** o'chirilgan/tahrirlangan xabarlar arxivi (P1–P3 `dev` da qurilgan) — bu reja uning **ustiga** server qatlamini qo'shadi, lokal qismga tegmaydi.
> Andoza: [[svipe-avatar-sync-plan]] backend + [[deleted-edited-messages-feature]] lokal arxiv.
> Repolar: mobil `~/StudioProjects/Lavha`, backend `~/StudioProjects/svipe-backend`.

---

## 0. Bir jumlada

Lokal "Recent Actions" arxivi (`svipe_deleted_messages`) **faqat P2P chatlarda va faqat ikkala tomon ham Svipe useri bo'lganda** server orqali sinxronlanadi: A ushlagan o'chirilgan/tahrirlangan xabar B ga ham ko'rinadi va aksincha. Guruh/kanal YO'Q; secret chat YO'Q; oddiy suhbat matni (`messages_v2`) YO'Q.

---

## 1. Maqsad va qamrov

**Manba — faqat `svipe_deleted_messages` jadvali.** Bu jadval allaqachon o'chirilgan xabarlar va tahrirdan oldingi versiyalarni saqlaydi (`MessagesStorage.java:770` fresh, `DatabaseMigrationHelper.java:1682` migratsiya 176→177). `messages_v2` ga **umuman tegilmaydi** — oddiy suhbat serverga chiqmaydi.

**Qat'iy chegaralar (kelishilgan):**
1. **Faqat P2P** (1:1 suhbat). Guruh/supergruppa/kanal YO'Q — sabab §5 (a'zolikni isbotlash uchun ishonchli manba yo'q).
2. **Ikkala tomon ham Svipe useri** bo'lishi shart (§6 tekshiruvi).
3. **Ikkala tomon ham "Suhbatdosh bilan" rejimini yoqqan** bo'lishi shart — bittasi yoqmagan bo'lsa hech narsa yuklanmaydi.
4. **Secret chat hech qachon** — lokal capture ham secret'ni istisno qiladi (`svipeArchiveMessage` `MessagesStorage.java:14922` da `isEncryptedDialog` bilan qaytadi); server ham tegmaydi.
5. **Faqat `KIND_DELETED` va `KIND_EDITED_PRIOR`** (`SvipeMessageArchiveStore.java:28-30`). `KIND_LIVE` hech qachon saqlanmaydi (o'qishda biriktiriladi).

**Lokal qatlam O'ZGARMAYDI.** Capture doim yoniq, barcha chatlarda, suhbatdosh Svipe useri bo'lishidan qat'i nazar. Sinxronlash faqat qo'shimcha qatlam — regressiya xavfi yo'q.

---

## 2. Uch rejim (foydalanuvchi tanlaydi)

Rozilik paytida (§3) uch variant ko'rsatiladi:

| # | Rejim | Serverga nima chiqadi | Kim o'qiydi | Suhbatdosh roziligi |
|---|---|---|---|---|
| 1 | **Suhbatdosh bilan** | shu chatdagi o'chirilgan/tahrirlangan xabarlar (ikkalasiniki) | ikkovingiz | **kerak** |
| 2 | **Faqat mening qurilmalarim** | faqat **siz** o'chirgan/tahrirlagan xabarlar | faqat siz (barcha qurilmalaringiz + web) | kerak emas |
| 3 | **Sinxronlanmasin** | hech narsa | — | — |

**Nega 2-rejim "faqat o'zimniki":** arxivning ichida suhbatdoshning o'chirgan xabarlari ham bor. Agar 2-rejim ularni ham yuklasa (faqat siz o'qisangiz ham), suhbatdoshning ma'lumoti uning roziligisiz serverga chiqadi — bu yorliq shaffof bo'lsa ham noto'g'ri, chunki ma'lumot egasi **suhbatdosh**. Shuning uchun 2-rejim faqat `out=1` (o'zingiz yuborgan) xabarlarni yuklaydi. Qiymati kamroq (boshqa qurilmada faqat o'z tahrir/o'chirish tarixingizni ko'rasiz), lekin halol va suhbatdosh Svipe useri bo'lishi shart emas.

**1-rejim qurilmalar aro sinxronlashni tekin beradi:** arxiv serverda bo'lgach, u boshqa qurilmangizda ham, web.svipe.uz da ham ko'rinadi. "Qurilmalar aro"ni alohida yoqish shart emas.

**3-rejim reciprocity ogohlantirishi (last-seen naqshi):** tanlanганda ko'rsatiladi — *"Sinxronlashni o'chirsangiz, suhbatdoshingiz o'chirgan xabarlar ham sizga sinxronlanmaydi. Xuddi 'oxirgi ko'rilgan vaqt' kabi."* Bu 2-rejimga tegmaydi (u yerda suhbatdosh xabarini baribir lokal ko'rasiz, faqat serverga chiqmaydi).

---

## 3. Rozilik oqimi (uch qadam)

**Qadam 1 — Svipe ToS (majburiy, kirishda, disclosure shu yerda).**
Hozir ilovada faqat **Telegram'ning** shartlari bor (`LoginActivity.java:1897` `currentTermsOfService`, dialog `:7703` `showTermsOfService`, gate `:8189` register `onNextPressed`). Svipe'ning o'z shartlari umuman yo'q. Yangi Svipe ToS qadami qo'shiladi (o'sha `showTermsOfService` dialog naqshi klonlanadi), unda qisqa band: *"Svipe ikkala tomon ham Svipe ishlatadigan shaxsiy suhbatlarda o'chirilgan/tahrirlangan xabarlarni sinxronlashi mumkin; buni istalgan payt Sozlamalarda boshqarasiz."* — bu Play "Prominent Disclosure" talabini qoplaydi. Qabul qilish ilovaga kirish sharti, lekin **sinxronlashni yoqmaydi**, faqat xabardor qiladi.

**Qadam 2 — rejim tanlash (kontekstda, bir marta).**
Mos chatda (suhbatdosh Svipe useri, §6) **birinchi marta** xabar o'chirilib arxivga tushganda, chat tepasida banner chiqadi. Vidjet: `ChatActivity.topChatPanelView` (`ChatActivity.java:528`, yaratish `createTopPanel()` `:9525`, ko'rsatish `updateTopPanel()` `:28863`, × tugma naqshi `closeReportSpam` `:9697`, ustuvorlik `setPriority` `:9584` — yangi banner bo'sh priority raqami oladi, mavjudlari: pinned=1, topChatPanel=2, pendingRequests=3, … botAd=10, alert=11, translate=12).

Banner bosilganda pastdan varaq (BottomSheet) ochiladi: nima yuklanadi, kim o'qiydi, qanday o'chiriladi + uchta tanlov (§2). "Yoqish" bosilishi rozilik. Javob **global** (butun ilova uchun bir marta), har chat uchun qayta so'ralmaydi. × yoki "Hozir emas" → banner yopiladi, boshqa chiqmaydi.

**Qadam 3 — boshqaruv (istalgan payt).**
Sozlamalar → Maxfiylik → "O'chgan xabarlar sinxronlash" qatori (`PrivacySettingsActivity` da `svipeAvatarArchiveRow` yonida yangi row — `:103` field, `:745` joylashuv, `:426` klik, `:1137` value matni, `:1031` isEnabled). Ekran `SvipeMessageSyncSettingsActivity` — `SvipeAvatarSettingsActivity` naqshi (`:38`): radio-guruh (3 rejim, `:309` binding), "Serverdan hammasini o'chirish" (`:167` delete naqshi + qizil `TextSettingsCell`), reciprocity izohi.

**Muhim:** 3-qadamgacha (ya'ni "Yoqish" bosilmaguncha) **hech narsa serverga chiqmaydi**. Rozilikdan oldin yig'ish yo'q.

---

## 4. Kalit muammosi — P2P `mid` umumiy emas

**P2P da xabar id'lari har akkauntning o'z fazosida.** Bitta xabarni siz `mid=1247`, suhbatdoshingiz `mid=892` deb ko'radi (shuning uchun kodda ikki alohida metod bor: `TL_messages_getMessages` vs `TL_channels_getMessages`). Ya'ni `mid` bilan ikki tomonni birlashtirib bo'lmaydi.

**Yechim — kontent-hash birlashtirish kaliti:**
```
merge_key = sha256(from_tg_id ‖ date ‖ content_hash)
```
bu yerda `content_hash` = matn (yoki media file id) xeshi. Bir xabarni ikkala klient ham xuddi shu kalitga xeshlaydi → server dedup qiladi. To'qnashuv faqat bir odam bir soniyada bir xil matnni ikki marta yuborsa (amalda ahamiyatsiz).

**Nega majburiy:** 1-rejimda ikkala tomon ham o'sha o'chgan xabarni yuklaydi (lokal arxiv self-delete'ni ham saqlaydi — §capture seam). Dedupsiz arxivda hamma narsa ikki marta ko'rinadi. `merge_key` server tomonda `UNIQUE` bo'ladi.

**Chat kaliti (arxiv qaysi juftlikка tegishli):**
```
pair_key = "p2p:" + min(a_tg_id, b_tg_id) + ":" + max(a_tg_id, b_tg_id)
```
So'rovchining `tg_id` si JWT `sub` dan keladi (`app/api/deps.py:33`, `app/security.py:21` `type:"access"`). Server tekshiruvi arifmetik: *"so'rovchi shu juftlikning ichidami?"* — avatarlardagi `photo_id` isboti kabi yumshoq joy yo'q.

---

## 5. Nega guruh/kanal YO'Q

"Bu odam hozir G guruh a'zosimi?" ga javob beradigan ishonchli manba yo'q:
- klientning "men a'zoman" da'vosi — soxtalashtiriladi;
- bizning indexer akkauntimiz faqat **ochiq** kanallarni ko'radi, yopiq guruhlarni yo'q;
- yagona real variant — a'zolik isboti (yaqin xabarning `chat_id+msg_id+matn xeshi`), yopiq guruhda ishlaydi, ochiq guruhda ma'nosiz.

P2P da bu muammo umuman yo'q (§4 arifmetik kalit). Shuning uchun 1-bosqichda faqat P2P. Guruhlar — kelajakdagi alohida reja, agar kerak bo'lsa.

---

## 6. "Suhbatdosh Svipe useri mi?" — k-anonymity tekshiruvi

Sinxronlash boshlanishidan oldin klient suhbatdosh Svipe useri ekanini bilishi kerak. To'g'ridan-to'g'ri "`tg_id` X user mi?" so'rovi butun bazani `tg_id` bo'yicha sanab chiqishga yo'l ochadi (`tg_id` fazosi sanaladigan — avatar ishida ham qayd qilingan, `AvatarContact` docstring `models.py:707`).

**Yechim — prefiks-chelak (k-anonymity):** klient `sha256(tg_id)` ning qisqa prefiksini (masalan 16 bit) yuboradi; server o'sha chelakdagi **sinxronlashni yoqqan** userlar xeshlarini qaytaradi; klient mosini o'zi topadi. Server kim so'raganini yoki aniq kimni izlaganini bilmaydi. So'rov autentifikatsiyalangan + rate-limited (`distinct_subjects_since` naqshi `avatar_repo.py:257`).

**Faqat "yoqqanlar" qaytadi:** javob faqat 1-rejimni tanlagan userlarni ko'rsatadi — ya'ni ro'yxat sinxronlashga tayyorlarnigina fosh qiladi, boshqa Svipe userlarини emas.

---

## 7. Media siyosati — tiniq muqova (owner tasdiqlagan)

**Rasm — to'liq** (mavjud siqilmagan bayt). **Video/fayl — faqat TINIQ muqova** (blur emas). **Stiker/GIF — umuman yo'q** (ommaviy, `document id` bo'yicha qaytariladi). **Ovozli xabar + video note — kirsin** (~15 KB, qimmatli). **Rasmga ~2 MB chegara** (fayl sifatida yuborilgan katta rasm muqovaga tushsin).

**Muqova qayerdan (ushlash paytida, sinxron, tarmoqqa CHIQMAYDI):**
Media agent tasdiqladi: tiniq `TL_photoSize` **serialize qilingan xabar ichida YO'Q** — u faqat `type/w/h/size` saqlaydi, piksellari `file_reference` bilan yuklanadi (`TLRPC.java:44203`, `:29205`). Faqat blur `TL_photoStrippedSize` inline turadi (`:44108`). Xabar o'chgach `file_reference` o'ladi → tarmoqdan olib bo'lmaydi. Shuning uchun zanjir faqat **lokal manbalardan**:

1. **Tiniq thumb allaqachon keshda** (deyarli har doim — chat uni chizgan): `FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, katta_side, false, null, /*ignoreStripped*/ true)` (`FileLoader.java:1457`, `ignoreStripped` filtri `:1465`) → real `TL_photoSize`; keyin `getPathToAttach(photoSize, ...)` (`:1339`, `:1369`) → `copyFileSafe` (`AndroidUtilities.java:4148`). ~15–50 KB, 0 tarmoq.
2. **Thumb keshda yo'q, lekin video keshda** (aynan hozir 20 MB nusxalanayotgan holat): `SendMessagesHelper.createVideoThumbnail(path, MINI_KIND)` (`:11639`, MINI→512px tiniq JPEG kadr) → ~30–60 KB.
3. **Ikkalasi ham yo'q:** inline blur `TL_photoStrippedSize` (bepul, zaxira).

**Bu lokal arxivni ham tuzatadi:** hozir bitta 20 MB video `svipeTrimArchive` (`MessagesStorage.java:15000`) ning 32 MB/dialog kvotasi (`SvipeMessageArchiveStore.java:34`) ning 2/3 ini yeb FIFO bilan qolgan hammasini chiqarib tashlaydi. Video pin qilinmasa (faqat muqova) o'sha kvota ~150 rasmga yetadi. Ya'ni bu qoida serverdan **oldin** mavjud featureni yaxshilaydi — 1-bosqichда lokal `svipeArchiveMessage` media-pin bloki (`:14948`) muqova-only ga o'zgartiriladi.

**Hajm:** 50k user ≈ yiliga 350–370 GB (R2 ~$5/oy). Video baytlari bilan bo'lsa ~9 TB ≈ $135/oy — **~25×** farq. Muqova rasmlar yonida amalda bepul.

---

## 8. Shifrlash — at-rest (E2E EMAS)

Telegram o'zi faqat secret chatni E2E qiladi, qolganini server ko'radi (biz secret'ni umuman arxivlamaymiz — shu qarorni hurmat qilamiz). Biz ham E2E qilmaymiz — **parol/tiklash iborasi ishqalanishisiz** va multi-device o'z-o'zidan ishlashi uchun.

**Lekin at-rest shifrlash qoladi:** server'da baytlar shifrlangan holda yotadi, kalit esa **Postgres bazasidan alohida** (env/secret store). Shunda DB dump yoki zaxira o'z-o'zidan ochilmaydi. Foydalanuvchi buni sezmaydi. Bu aynan Telegram "yurisdiksiyalarga bo'lingan kalit" deб aytgan narsaning kichik ko'rinishi.

**OCHIQ SAVOL:** 2-rejim ("faqat o'zimniki") uchun foydalanuvchi-kaliti (haqiqiy E2E) qo'shilsinmi? U kuchliroq, lekin kalit yo'qolsa arxiv o'qib bo'lmaydi (tiklash iborasi kerak bo'lardi). 1-bosqichда **at-rest yetarli**, foydalanuvchi-kaliti keyingi bosqichга qoldiriladi.

---

## 9. Saqlash muddati va kvotalar

- **Retention: 90 kun** (taklif; `avatar_retention_days` naqshi `config.py`, sweep `expired_photos` `avatar_repo.py:233`). Barqaror holatda serverda oxirgi 90 kun turadi.
- **Backfill rozilik paytida:** ikkala tomon ham yoqganda oxirgi 90 kunlik lokal arxiv serverga chiqadi (lokal eviction chegarasida — `MAX_PER_DIALOG=2000`/`32 MB`). Shunda ekran birinchi ochilishдаyoq tirik ko'rinadi, yangi ochiqlik yaratmaydi (barqaror holatda ham o'sha 90 kun turadi).
- **Per-pair kvota** + **muallif opt-out**: xabar **muallifi** o'z xabarlarini havzadan chiqara olishi kerak (`delete_subject_archive` naqshi `:217`).

---

## 10. Backend (`~/StudioProjects/svipe-backend`) — avatar naqshini ko'zguqiladi

**Yangi jadvallar** (`app/db/models.py` oxiriga, `base.py:213` naqshi — brand-new jadvallar `create_all` bilan quriladi, ALTER kerak emas):
- `msg_sync_pair` — `pair_key` PK, `a_tg_id`, `b_tg_id`, `a_mode`, `b_mode` (1/2/3), timestamps. (Har juftlik uchun ikkala tomon rejimи.)
- `msg_sync_item` — `merge_key` PK (§4), `pair_key` FK/index, `author_tg_id`, `date`, `edit_date`, `kind` (deleted/edited_prior), `has_media`, `object_key` (media muqova R2 da), `ciphertext`/`nonce` (at-rest shifrlangan TL bayt), `status` (pending/stored), timestamps.
- `msg_sync_access_log` — audit + rate-limit manbai (`AvatarAccessLog` naqshi `:671`).
- (2-rejim uchun) `msg_sync_self` — faqat `out=1` xabarlar, `owner_tg_id` bo'yicha, `pair_key` emas.

**Repo** (`app/db/msg_sync_repo.py`, `avatar_repo.py` naqshi) — idempotent upsert'lar: `ensure_pair`, `set_mode`, `both_enabled(pair)`, `register_item`, `stored_keys`, `mark_stored`, `list_pair_items`, `delete_pair_items`, `delete_self_items`, `log_access`, `distinct_pairs_since`, `uploaded_bytes_since`, `expired_items`.

**API** (`app/api/msg_sync.py`, `avatars.py` naqshi):
- `decide_pair_access(requester, pair_key, a_mode, b_mode)` — pure funksiya (`decide_access` `:73` naqshi): so'rovchi juftlik ichidami + ikkala rejim ham 1 mi.
- `_deny(...)` — **commit-before-raise** (`:58` naqshi; `get_session` `base.py:39` exception'да rollback qiladi — busiz audit + rate-limit yozuvi o'chardi).
- `POST /peers/check` — k-anonymity chelak (§6).
- `POST /me/mode` — juftlik rejimini o'rnatish (1/2/3); 3 → `delete_pair_items`.
- `POST /observed` — juftlik + item'larni report qilib `missing` merge_key'larni qaytarish (`avatars.py:137` naqshi, enumeration brake bilan).
- `POST /upload-url` + `POST /commit` — media muqova uchun presigned PUT (`:188`/`:231`; `store.head` bilan tekshirish, hash-mismatch 409).
- `GET /pair/{pair_key}` — juftlik arxivini o'qish, `decide_pair_access` bilan (`:384` naqshi, oxirida e'lon qilinadi).
- `DELETE /me` — o'z xabarlarimni hamma juftlikdan o'chirish.

**Blobstore** — mavjud `R2Store` (`blobstore.py:96`) qayta ishlatiladi; yangi `object_key`:
```
msg/{pair_key}/{merge_key}.jpg    (muqova)
```
Bucket alohida bo'lishi mumkin (`svipe-messages`) yoki bir bucketда prefiks bilan.

**Config** (`app/config.py`, `:224` avatar bloki naqshi): `msg_sync_enabled`, `msg_sync_retention_days=90`, `msg_sync_max_bytes`, TTL, rate-limit, byte-quota + at-rest `msg_sync_encryption_key` (bazadan alohida).

**Auth** o'zgarmaydi: `CurrentUserDep` (`deps.py:33`), token `type:"access"` (`security.py:21`).

**Privacy** (`app/privacy.py`) — yangi raqamli bo'lim (§4 "Profile photo archive" yonida): *"Synced deleted & edited messages"* — nima, kim ko'radi, ixtiyoriy, o'chirilishi mumkin, uchinchi tomonga berilmaydi. §1 "Information we collect" `<ul>` (`:23`) ga yangi `<li>` qo'shiladi. **Bu bo'lim yoqishдан OLDIN yoziladi** (avatar ishidagidek).

---

## 11. Klient (mobil, `org.telegram.svipe`)

**Capture — o'zgarmaydi, faqat muqova.** 4 seam joyida qoladi (`MessagesStorage.java:14236`, `:15216`, `:16101`; `SendMessagesHelper.java:2866`). Media-pin bloki (`:14948`) muqova-only ga o'zgartiriladi (§7). Sinxronlash bu seam'lardan **keyin** ishlaydi — arxivга tushган item mos juftlik bo'lsa navbatga qo'yiladi.

**Yangi klass `SvipeMessageSync.java`** (`SvipeAvatarSync.java` naqshi):
- `onArchived(account, dialogId, mid)` — capture'дан keyin chaqiriladi; P2P + suhbatdosh Svipe user + rejim tekshiruvi; navbat.
- `uploadItem` — control-plane JSON (`SvipeApi.put` `:36`) + media muqova `putFile` (`:50`, presigned, bearer'siz).
- `fetchPair` — `GET /pair`, kelgan item'larни lokal DB ga yozish, `getFile` (`:95`, .tmp+rename) bilan muqova.
- `checkPeer` — §6 k-anonymity.
- `setMode` / `deleteMyArchive` / `loadMySettings`.
- Kill-switch: server `enabled:false` qaytarsa to'xtaydi (`SvipeAvatarSync.serverDisabled` naqshi).

**Merge — o'qishда.** Serverdan kelgan item'lar lokal `svipe_deleted_messages` ga yoziladi (yoki alohida ko'rsatiladi); `SvipeDeletedLogActivity` (`:100`) va `SvipeMessageHistorySheet` (`:75`) ularni ham ko'rsatadi. Yangilanganда `NotificationCenter.svipeMessageSyncUpdated` (per-account, `:386` naqshi) post qilinadi; Recent Actions ekrani observe qiladi.

**Sozlamalar UI** — `SvipeMessageSyncSettingsActivity` (§3 qadam 3).

**Rozilik** — Svipe ToS (`LoginActivity`) + banner (`ChatActivity.topChatPanelView`) + varaq (§3).

**SvipeConfig** — `PREF_MSG_SYNC_MODE` (per-account String cache, `PREF_AVATAR_VISIBILITY` `:102` naqshi) + `msgSyncModeLabel()` static (Privacy row value uchun).

---

## 12. Bosqichlar

1. **Media muqova (lokal)** — `svipeArchiveMessage` media-pin'ni muqova-only ga o'zgartirish (§7). Serverdan mustaqil; lokal 32 MB kvotani darhol ~150 rasmga yetkazadi. JVM-test bilan.
2. **Backend poydevor** — jadvallar + repo + `msg_sync_repo` unit-test + SigV4 golden (mavjud test naqshi). API skeleti + `decide_pair_access` pure test.
3. **Svipe ToS** — `LoginActivity` ga qabul qadami + disclosure matni + privacy.py bo'limi. (Bu mustaqil qiymat: ilovada Svipe ToS umuman yo'q edi.)
4. **k-anonymity peer-check** + rejim tanlash banner + sozlamalar ekrani (yuklashsiz — faqat rejim).
5. **Yuklash** — `POST /observed`/`upload-url`/`commit`, media muqova, at-rest shifrlash, backfill.
6. **O'qish + merge** — `GET /pair`, lokal merge, Recent Actions ko'rsatish, reciprocity.
7. **Retention + prod** — sweep, kvotalar, Play data-safety ("Messages: Collected yes, Shared no"), prod deploy + E2E (avatar `avatar_e2e.py` naqshi).

Har bosqichdan keyin owner ko'rib tasdiqlaydi (avatar ishidagidek).

---

## 13. Xavflar

- **Play Data safety** "Messages" toifasiga tushadi — bir foizini yuklash ham hisoblanadi. Toraytirish (faqat o'chirilgan, P2P) toifani o'zgartirmaydi, lekin siyosat matnini aniq va himoya qilinadigan qiladi. Prominent Disclosure + affirmative consent MAJBURIY (§3) — ToS'ga yozib qo'yish yetarli emas (Google ochiq aytadi).
- **TOFU / kalit ishonchi** (agar 2-rejim foydalanuvchi-kalitiga o'tsa) — ochiq kalitlarni biz tarqatamiz; keyinchalik "safety number" ko'rsatish. 1-bosqichда at-rest bilan bu masala tug'ilmaydi.
- **Backfill ochiqligi** — yo'q: barqaror holatда ham 90 kun turadi, backfill shunchaki o'sha oynani to'ldiradi.
- **Suhbatdosh keyinroq Svipe o'rnatsa** — ikkala rozilik mavjud bo'lgan payt 90 kunlik oyna to'ldiriladi (izchil qoida).

---

## 14. Ochiq savollar

1. **At-rest yetarlimi yoki 2-rejim foydalanuvchi-kalitiga o'tsinmi?** (§8) — tavsiya: 1-bosqichда at-rest.
2. **Retention aniq 90 kunmi?** (§9) — tunable, tasdiq kerak.
3. **Media muqova bucket:** alohida `svipe-messages` yoki mavjud bucketда prefiks?
4. **Serverdan kelган item lokal `svipe_deleted_messages` ga yozilsinmi yoki alohida ko'rsatilsinmi?** (reload xatti-harakati) — tavsiya: alohida "synced" bayroq bilan lokalга yoziladi.

---

*Andoza fayllar: `docs/svipe-avatar-sync-plan.md` (backend/prod naqshi), `docs/svipe-deleted-edited-messages-plan.md` (lokal arxiv). Barcha langarlar `ground-message-sync-plan` workflow (2026-07-27) natijasi.*
