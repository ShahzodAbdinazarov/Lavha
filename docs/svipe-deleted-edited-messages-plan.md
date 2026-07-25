# Svipe — O'chgan va tahrirlangan xabarlar arxivi (implementatsiya rejasi)

> Holat: **REJA** (kod yozilmagan). Manba: `dev` branch, 2026-07-24.
> Mualliflik andozasi: bu feature shipped **Profile Images / avatar-keeper** (`SvipeAvatarStore` + `SvipeAvatarKeeper`) me'morasining aynan davomi.
> Barcha `file:line` havolalar shu commit holatiga nisbatan; implement paytida qayta tekshiriladi.

---

## 1. Xulosa

**Feasible, o'rtacha murakkab.** Ushlash qatlami va jurnal ekrani avatar-keeper'ning deyarli nusxasi. Egawith tasdiqlangan 4 qaror rejaga singdirilgan:

1. **O'chgan xabar DB'da qoladi** — asosiy `messages_v2`ni ifloslamasdan, **alohida parallel jadval** (`svipe_deleted_messages`, aynan shu DB ichida) orqali. Chat tarixi yuklanganda "show in chat" yoqilgan bo'lsa shu jadval merge qilinadi → **reload'dan keyin ham yo'qolmaydi**, joylashuv/guruhlanish/reply tabiiy.
2. **O'zi o'chirgan xabarlar ham arxivlanadi** (self-delete filtri yo'q).
3. **Kanal bulk-sweep (`clear history` / `delete up to`) ham hook qilinadi** — 14876 overload'i ham har xabarni deserialize qilar ekan, shuning uchun simmetrik hook oson; eviction budjeti bilan chegaralanadi.
4. **Eviction: ~2000 yozuv / ~32MB har dialog** uchun.

**Haqiqiy yangi ish:** edit-dedup guard, qizil "Deleted" yorlig'i (bubble kengligini rezerv qilish), pin-qilingan media'ni ko'rsatishga ulash, va load-merge injection (parallel jadvaldan).

---

## 2. Talablar (qat'iy) va qarorlar

| # | Talab |
|---|-------|
| R1 | Ko'rsatish Telegram guruh "Recent Actions" (admin-log) uslubida — har chat uchun bitta jurnal sirti |
| R2 | Barcha dialoglar: 1:1, guruh, kanal. **Faqat secret chat** istisno. Auto-delete (`ttl_period>0`) chatlar **saqlanadi** |
| R3 | Har chatda "Show in chat" toggle |
| R4 | "Show in chat" ON bo'lsa: o'chgan xabar chatda qoladi, **qizil "Deleted"** yorlig'i bilan ("edited" kabi, faqat qizil) |
| R5 | "Show in chat" ON bo'lsa: tahrir tarixi ko'rinadi — long-press menyuga **"Message history"** → bottom-sheet'da barcha versiyalar |
| R6 | **Ushlash DOIM yoniq** (hech qanday toggle bilan bloklanmaydi). Barcha versiyalar saqlanadi; jurnal doim to'la |
| R7 | **Standart: "Show in chat" O'CHIQ** (per-chat). Ushlash baribir yoniq |
| R8 | Auto-delete chatlar istisno EMAS. **Faqat secret chat** istisno |
| R9 | Yuklab olish parametrlariga tegilmaydi (FileLoader download xatti-harakati o'zgarmaydi) |
| R10 | O'chish paytida yuklab ulgurilmagan media'ni yuklashga urinilsa — **error xabari** (qayta yuklamaydi) |

---

## 3. Me'moriy ko'rinish

```
                    (DOIM yoniq — R6)
   O'chirish/tahrir  ─────────────►  SvipeMessageArchiveKeeper (per-account)
   (storage queue)                        │  (secret chat istisno)
                                          ▼
                          ┌───────────────────────────────┐
                          │  svipe_deleted_messages (jadval)│  same DB, messages_v2 ustunlari
                          │  + pinned media (files dir)     │  + kind/version/captured_at
                          └───────────────────────────────┘
                                 │                    │
                 ┌───────────────┘                    └──────────────┐
                 ▼                                                     ▼
     Display A: SvipeDeletedLogActivity                Display B: chat-ichi (opt-in, R7)
     (har chat — R1, doim mavjud)                      • live: o'chirishni bostirish
                                                        • reload: getMessagesInternal merge
                                                        • qizil "Deleted" yorlig'i (R4)
                                                        • "Message history" sheet (R5)
```

---

## 4. Ushlash dvigateli — 3 seam

Uchalasi ham **storage queue'da sinxron** ishlaydi (hook joyida), shuning uchun media `copyFileSafe` pin `deleteFiles`'dan (`MessagesStorage.java:14314`) oldin ulguradi.

### Seam A — o'chirish (aniq ID ro'yxati)
`MessagesStorage.markMessagesAsDeletedInternal(dialogId, messages[], deleteFiles, mode, threadMessageId)` (~14072).
Hook **~14192** — `message.readAttachPath(data, currentUser)` (14191)dan keyin, `addFilesToDelete` (14196)dan oldin:

```java
// Svipe
if (!DialogObject.isEncryptedDialog(did)) {
    SvipeMessageArchiveKeeper.getInstance(currentAccount).onMessageDeleted(did, message, dataBytes);
}
```
- Faqat `deleteFiles=true` bo'lganda ishlaydi (aks holda 14185'da `continue`). Server-revoke yo'li `deleteFiles=true` beradi (`MessagesController.java:21124`).
- `dataBytes` = kursor bergan xom blob (deserialize'dan oldin) — jadvalga to'g'ridan-to'g'ri yozamiz, qayta serialize/normalize shart emas.

### Seam B — kanal bulk-sweep (clear history / delete up to)
`MessagesStorage.markMessagesAsDeletedInternal(channelId, mid, deleteFiles)` (14876).
Bu **boshqa funksiya** — `WHERE uid=-channelId AND mid<=mid` bo'yicha hammani supuradi. Tasdiqlandi: **u ham har xabarni deserialize qiladi** (`TLdeserialize` + `readAttachPath` + `addFilesToDelete`). Hook symmetric, uning `readAttachPath`'idan keyin:

```java
// Svipe
if (!DialogObject.isEncryptedDialog(did)) {
    SvipeMessageArchiveKeeper.getInstance(currentAccount).onMessageDeleted(did, message, dataBytes);
}
```
- **Eviction bilan chegaralanadi** (bir "clear history" mingta xabarni tashlashi mumkin).

### Seam C — tahrir (pre-image)
`MessagesStorage.putMessages(...)` `load_type == -2` shoxobi.
Hook **~15795** — `oldMessage.readAttachPath` (15794)dan keyin, REPLACE-INTO (~15820)dan oldin:

```java
// Svipe — dedup guard MAJBURIY
if (!DialogObject.isEncryptedDialog(dialogId)
        && oldMessage != null
        && message.edit_date > oldMessage.edit_date) {
    SvipeMessageArchiveKeeper.getInstance(currentAccount).onMessageEdited(dialogId, oldMessage, message);
}
```
- **`edit_date > oldMessage.edit_date` guard'i shart.** `-2` shoxobi faqat-edit EMAS: webpage-preview hal bo'lishi (`MessagesController.java:11826`) o'sha xabarni `edit_date` o'zgarmagan holda qayta put qiladi; `getDifference` ham qayta o'ynatadi. Qat'iy `>` ikkalasini ham filt르laydi.
- Birinchi tahrir to'g'ri: `oldMessage.edit_date==0 < yangi` → asl versiya arxivlanadi, zanjir `[asl, edit1, edit2…]`.
- `oldMessage==null` (mahalliy DB'da yo'q) → o'tkazib yuboriladi.

### Umumiy jihatlar
- **normalizeFlags xavfi:** hook'dan keyin `message`/`oldMessage` mavjud kod tomonidan ishlatiladi. In-flight obyektni qayta `normalizeFlags` QILMANG. Eng toza: kursor bergan xom `data` baytlarini o'zini jadvalga yozish (delete seam'lar) yoki `oldMessage`ni klon qilib serialize qilish (edit seam). `data.reuse()` ni buzmang.
- **Media pin (R9):** faqat `FileLoader.getPathToMessage(msg)` (`FileLoader.java:1268`) diskda mavjud bo'lsa → `AndroidUtilities.copyFileSafe` (`AndroidUtilities.java:4148`) arxiv katalogiga. **`loadFile` hech qachon chaqirilmaydi.**
- **Bulk-latency:** blob-yozishni Svipe arxiv navbatiga qo'yish mumkin (deserialize'langan `Message` xavfsiz nusxa), **ammo media `copyFileSafe` sinxron qolishi shart** (fayl 14314'da o'chadi).

---

## 5. Storage — parallel jadval + pin media

**Jadval `messages_v2` bilan bir xil DB ichida** (merge oson bo'lsin). Migratsiya: `LAST_DB_VERSION 176 → 177`, `updateDbToLastVersion` (776) ga branch, `createTables`ga CREATE.

```sql
CREATE TABLE svipe_deleted_messages(
  uid INTEGER, mid INTEGER, svipe_version INTEGER,
  data BLOB,                    -- messages_v2.data bilan bir xil format
  date INTEGER, edit_date INTEGER, out INTEGER, media INTEGER, group_id INTEGER,
  from_id INTEGER,
  svipe_kind INTEGER,           -- 0 = o'chgan, 1 = tahrir-oldi versiya
  svipe_media_path TEXT,        -- pin qilingan media (files dir), yoki NULL
  captured_at INTEGER,
  PRIMARY KEY(uid, mid, svipe_version));
CREATE INDEX svipe_del_uid_date ON svipe_deleted_messages(uid, date);
```

- **Version zanjiri:** `svipe_version = 1 + max(existing for (uid,mid))`. O'chgan xabar odatda `version=1`; tahrir-oldi versiyalar ketma-ket o'sadi.
- **Media:** `ApplicationLoader.getFilesDirFixed("svipe_msg_archive")/a<account>/d<dialog>/<mid>_<version>.media` — cache emas, **files dir**, Clear Cache'da qoladi (`ApplicationLoader.java:177`).
- **Blob kodek** (o'qishda): `TLRPC.Message.TLdeserialize(buf, buf.readInt32(false), false)` + `readAttachPath(buf, UserConfig.getInstance(account).clientUserId)`, `try/catch` bilan (TL-layer bump'ga chidamli).
- **Eviction (`trim`):** har dialog uchun `count > ~2000` YOKI `bytes > ~32MB` bo'lsa eng eski `captured_at` bo'yicha o'chir — **jadval qatorini VA `.media` faylni ham** o'chir (avatar store faqat metadatani trim qilardi).
- **Jimshim:** SQLite ishi `MessagesStorage` ichida (`database` handle + migratsiya kerak), ustidan yupqa `SvipeMessageArchiveStore` fasadi (katalog/media/eviction siyosati + JVM-test qilinadigan sof `version`/`trim` funksiyalari).

---

## 6. Display A — per-chat jurnal (doim mavjud)

**`SvipeDeletedLogActivity(int account, long dialogId)`** — kafolatlangan, doim to'la sirt (R1/R6). 1:1/guruh/kanalda ishlaydi (manba mahalliy jadval, admin-log RPC emas).

- Reuse: `ChannelAdminLogActivity`'ning **faqat render mashinasi** — `ListAdapter` + `ChatMessageCell`/`ChatActionCell` (`onCreateViewHolder ~2894`, `getItemViewType ~3454`, `chatMessageCellsCache ~915`, guruhlash `~3401`). Klassni emas: u `TL_channels_getAdminLog`/`InputChannel`ga qattiq bog'langan (`:462/:540`), admin-only, ~4533 qator. Shuning uchun **lean reimplement**, manba `SvipeMessageArchiveStore.getForDialog(account, dialogId)`.
- MessageObject blobdan: `new MessageObject(account, blobMsg, true, false)`. **`from_id` User/Chat `MessagesController` kesh'idan olinadi** (blob faqat `Message`ni saqlaydi).
- Bo'limlar: `ChatActionCell`/sana-sarlavhalari bilan *o'chgan* va *tahrir-oldi* qismlarini ajratish.
- Ochilishi: header menyudan `svipe_deleted_log`.

---

## 7. Display B — chat-ichi (opt-in, standart O'CHIQ)

Ikki qism birga ishlaydi: **live suppression** (o'sha sessiyada) + **load-merge** (reload'da).

### 7.1 Live: o'chirishni bostirish
`ChatActivity.processDeletedMessages` (~26074). Toggle ON bo'lsa, **~26183**dagi o'chirishni almashtir:
- `obj.svipeDeleted = true; chatAdapter.notifyItemChanged(...)` — `messages.remove` + `notifyItemRemoved` O'RNIGA.
- `removedIndexes.add` (~26208), `messagesDict.remove` (~26228), `messagesByDays`/`groupedMessagesMap` tozalash va Thanos yo'lini **o'tkazib yubor**.
- **Ham `messages`, ham `chatAdapter.filteredMessages`** (alohida indeks hisobi). Saqlangan qator uchun `removedIndexes`ga qo'shsak RecyclerView crash beradi.
- Reply-count kamaytirish (~26148-26165) o'chirishdan OLDIN bo'ladi — saqlangan bubble ota-xabar reply-count'ini kamaytiradi; kerak bo'lmasa gate qil (ochiq savol).

### 7.2 Reload: getMessagesInternal merge (reload-safe)
`MessagesStorage.getMessagesInternal` (8865). Dialog uchun toggle ON bo'lsa: `svipe_deleted_messages`dan `svipe_kind=0` qatorlarni o'sha `mid`/`date` oynasida so'rab, `res.messages`ga qo'sh (mavjud sort ularni to'g'ri joylashtiradi). Injektlangan mid'larni belgilab qo'y, `processLoadedMessages` (`MessagesController.java:11883`) ularning MessageObject'iga `svipeDeleted=true` qo'ysin.
- **Faqat o'chgan (kind=0) inline injekt qilinadi.** Tahrirlanган xabarning joriy versiyasi allaqachon `messages_v2`da bor — inline'da unga faqat "Message history" affordance qo'shiladi.
- **Diqqat:** merge `load_type`/pagination oynalarini hurmat qilishi kerak — injektlangan qatorlar gap-mantiqni buzmasin yoki takrorlanmasin. Bu DB-yondashuvining asosiy xavfi.

### 7.3 Qizil "Deleted" yorlig'i (R4)
`ChatMessageCell`:
- `deletedLayout` yasab (~13724), transient `animateEditedLayout` (~28322) andozasida, `messageObject.svipeDeleted` bo'yicha.
- **Kengligini rezerv qil** — o'lchov joyida `timeWidth`/`availableTimeWidth`ga `deletedLayout` kengligi + gap qo'sh (aks holda kontent bilan ustma-ust yoki bubble chekkasida kesiladi).
- `timeLayout`dan keyin (~23988) **maxsus qizil paint** bilan chiz; keyin `Theme.chat_timePaint` rang/alfasini **tikla** (u ulashilgan, `:23708-23712`da har kadr o'zgaradi — aks holda keyingi hamma vaqt qizarib ketadi).
- Guruh-o'chgan uchun `:23686`dagi erta-return'ni `svipeDeleted` uchun **yumshat** (aks holda albom yorlig'i chizilmaydi).

### 7.4 "Message history" (R5)
- `ChatActivity.createMenu` (~30459) ga long-press menyu item: yangi `options`-int case, faqat arxivda `kind=1` yozuvi bor xabar uchun ko'rinsin.
  > ⚠️ **Bu seam eng kam tasdiqlangan** (mapper agent shu qismда yiqilgan). Implement'dan oldin `createMenu` + option-dispatch mexanizmini tez tekshir.
- `SvipeMessageHistorySheet(account, dialogId, messageObject)` — `BottomSheet` + `RecyclerListView`, har versiyani (`kind=1`, `version` asc + joriy jonli versiya) `ChatMessageCell` bilan chizadi.

---

## 8. Toggle'lar

- **Ushlash:** toggle YO'Q (R6).
- **"Show in chat":** per-chat, standart O'CHIQ (R7).
  - Menyu id: `svipe_show_in_chat = 75` (`ChatActivity.java:1652`, 75+ bo'sh).
  - `headerItem.lazilyAddSubItem` (~4405), `setCheckable(true)`, **secret chatда yashirin**.
  - Holat: `getMainSettings().getBoolean("svipe_show_in_chat_" + getDialogId(), false)` — menyu har ko'rsatilganda qayta o'qiladi.
  - `onItemClick` (~3799) flip + persist (per-dialog idiom `:9723`) + ko'rinadigan qatorlarni qayta belgila.
- **Jurnal:** header menyuda `svipe_deleted_log` item → `SvipeDeletedLogActivity`.

---

## 9. Media va yuklab olish

- **R9:** `FileLoader` yuklab olish parametrlari butunlay tegilmaydi; ushlash faqat diskda mavjud faylni `copyFileSafe` qiladi.
- **R10:** intercept `ChatMessageCell.didPressButton` (~17783) boshida — `currentMessageObject.svipeDeleted || svipeArchived` VA maqsad fayl diskda YO'Q bo'lsa: delegate orqali error bulletin (`R.string.MediaUnavailable`) + `return` (yuklash boshlanmaydi). Xuddi shu intercept `SvipeDeletedLogActivity` va `SvipeMessageHistorySheet` katakларида ham.
- **Pin-media'ni ko'rsatishga ulash:** saqlangan/injektlangan obyektning `attachPath`ini arxiv `.media` nusxasiga yo'naltir (photo uchun `SvipeProfileImagesAdapter` hiylasi: `mediaExists=true` + `mediaThumb=ImageLocation.getForPath(local)`). Media yo'q bo'lsa — placeholder, hech qachon osilgan spinner emas.

---

## 10. Yangi fayllar + upstream tegish nuqtalari

### Yangi (`org.telegram.svipe`, hammasi `// Svipe`)
| Fayl | Vazifa |
|------|--------|
| `SvipeMessageArchiveKeeper.java` | Per-account (`instances[MAX_ACCOUNT_COUNT]`, DCL). `onMessageDeleted(did, msg, dataBytes)`, `onMessageEdited(did, old, new)`. Jadvalga yozadi (MessagesStorage helper orqali) + sinxron media pin. NotificationCenter observer kerak emas (storage hook'lardan to'g'ridan-to'g'ri chaqiriladi). |
| `SvipeMessageArchiveStore.java` | Fasad: katalog/media yo'llari, eviction siyosati, JVM-test qilinadigan sof `nextVersion`/`trim` funksiyalari. |
| `SvipeDeletedLogActivity.java` | Lean per-chat jurnal (§6). |
| `SvipeMessageHistorySheet.java` | Versiya BottomSheet (§7.4). |
| `SvipeMessageArchiveStoreTest.java` | JVM: `nextVersion`, `trim` (count + byte eviction), tartib. |

### O'zgartiriladigan (minimal, additiv)
| Fayl:line | O'zgarish |
|-----------|-----------|
| `MessagesStorage.java` ~120 | `LAST_DB_VERSION 176 → 177` |
| `MessagesStorage.java` createTables | `CREATE TABLE svipe_deleted_messages ...` (+ index) |
| `MessagesStorage.java` ~776 | `updateDbToLastVersion` — 177 migratsiya branch (jadval yaratish) |
| `MessagesStorage.java` ~14192 | Seam A hook (o'chirish) |
| `MessagesStorage.java` ~14900 | Seam B hook (kanal bulk-sweep, `readAttachPath`'dan keyin) |
| `MessagesStorage.java` ~15795 | Seam C hook (tahrir pre-image, `edit_date>` guard) |
| `MessagesStorage.java` yangi metodlar | `insertDeletedForSvipe(...)`, `getDeletedForSvipe(uid, window)`, `trimSvipeArchive(uid)` |
| `MessagesStorage.java` ~8865 | `getMessagesInternal` — toggle ON dialogда `svipe_kind=0` merge |
| `MessagesController.java` ~11883 | `processLoadedMessages` — injektlangan mid'larga `svipeDeleted=true` |
| `ChatActivity.java` ~1652 | Menyu id'lar (`svipe_show_in_chat=75`, `svipe_deleted_log`) |
| `ChatActivity.java` ~4405 | Ikki `lazilyAddSubItem` (Show in chat + Deleted log), secret'да yashir |
| `ChatActivity.java` ~3799 | `onItemClick` — toggle flip + persist; jurnal ochish |
| `ChatActivity.java` ~26183 | `processDeletedMessages` — keep-inline gate (+ reply-count ~26148 qarori) |
| `ChatActivity.java` ~30459 | `createMenu` — "Message history" item *(tekshirilishi kerak)* |
| `ChatMessageCell.java` ~13724 | `deletedLayout` yasash + **kenglik rezervi** |
| `ChatMessageCell.java` ~23988 | Qizil chizish + paint tiklash; ~23686 erta-return yumshatish |
| `ChatMessageCell.java` ~17783 | R10 intercept `didPressButton` |
| `MessageObject.java` | `public boolean svipeDeleted; public boolean svipeArchived;` (core `deleted`ni ISHLATMA — grouping/hit-test yon ta'siri bor) |
| `res/values*/strings.xml` (en+uz+ru) | `SvipeDeletedLabel`, `SvipeMessageHistory`, `SvipeDeletedLog`, `SvipeShowInChat`, `SvipeMediaUnavailable`, bo'lim sarlavhalari — `checkSvipeStrings` guard majburiy |
| `SvipeConfig.java` | Pref-key prefiks + eviction konstantalari |

---

## 11. Bosqichlar (avatar shipment kabi)

1. **Ushlash dvigateli + jadval** — migratsiya (177), 3 hook (dedup guard, non-normalizing yozuv, faqat-cached media pin, deferred-serialize navbat), eviction. JVM testlar. Verify: dev emulyatorda o'chirib/tahrirlab, `adb` orqali jadval qatorlari + `.media` fayllar files dir'da paydo bo'lishi + Clear Cache'da qolishi.
2. **Jurnal ekrani** — `SvipeDeletedLogActivity` + header item. Har qanday inline ishdan oldin kafolatlangan sirt.
3. **Chat-ichi + tarix** — `svipeDeleted` flag, "Show in chat" toggle, live suppression + load-merge injection (reload-safe), qizil yorliq (kenglik rezervi), pin-media ulash, R10 intercept, "Message history" sheet.
4. **Silliqlash** — eviction sozlash, `edit_hide` qarori, albom/guruh yorliqlari, secret istisno + auto-delete kirishini tekshirish, `try/catch` read-back.
5. **Release** — E2E `svipe_test` (o'chir + ko'p-tahrir 1:1/guruh/kanal/auto-delete; secret → arxiv yo'q; R10 error non-cached media'da; toggle OFF default; jurnal doim to'la). `.web` + versiya kodi bump, AAB'ga qarshi verify (["verify against built artifacts"] memory), dev → prod (eganing ko'rigidan keyin).

---

## 12. Xavflar / ochiq savollar

**Xavflar**
- **`getMessagesInternal` merge** `load_type`/pagination oynalarini buzsa — injektlangan o'chgan qatorlar gap-mantiqni buzishi yoki takrorlanishi mumkin. DB-yondashuvining asosiy xavfi; ehtiyot bilan test.
- DB migratsiya (177) xavfsiz bo'lishi shart (`onUpgrade` jadval yaratish; eski o'rnatishlar).
- `Theme.chat_timePaint` ulashilgan — qizil chizishdan keyin rang/alfa tiklanmasa hamma vaqt qizarib ketadi.
- `:23686` guruh-media erta-return `svipeDeleted` albomlar uchun yumshatilishi kerak.
- Kanal bulk-sweep arxivни toshirishi mumkin — eviction chegaralaydi.
- Saqlangan blob'lar `messages_v2` kodegida — TL-layer bump read-back'ni buzishi mumkin; `try/catch` best-effort.
- Keep-inline adapter-count to'g'riligi (saqlangan qator uchun `notifyItemRemoved`/`removedIndexes` yo'q; filtered ro'yxat).
- **"Message history" menyu seam'i (createMenu ~30459) eng kam tasdiqlangan** — implement'dan oldin tekshir.

**Ochiq savollar** (implement paytida hal bo'ladi)
- Reply-count kamaytirish (~26148) saqlangan bubble'da — qoldirilsinmi yoki gate qilinsinmi?
- `edit_hide` — qizil badge / tarix affordance'da hurmat qilinsinmi yoki doim ko'rsatilsinmi?
- Injektlangan o'chgan xabar uchun eng chekka holatlar: albom yaxlitligi, pagination oynasi chegarasida bo'lganda.
