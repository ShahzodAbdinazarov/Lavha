# Svipe — Play Console "Data safety" deklaratsiyasi

> Bu — Play Console → **App content → Data safety** formasiga **so'zma-so'z ko'chiriladigan** javoblar.
> Oxirgi yangilanish: 2026-07-26 (profil rasmlari arxivi + kontaktlar qo'shilgandan keyin).
> Manba: `app/privacy.py` (svipe.uz/privacy) va `docs/svipe-avatar-sync-plan.md`.
> **Qoida:** bu yerdagi har bir "yo'q" kodda ham "yo'q" bo'lishi shart. Formani to'ldirishdan oldin
> o'zgargan joyni tekshir.

## Umumiy javoblar

| Savol | Javob |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** (HTTPS/TLS; obyekt saqlash ham TLS) |
| Do you provide a way for users to request that their data is deleted? | **Yes** — ilova ichida (Settings → Privacy and Security → Profile photo archive) va `support@svipe.uz` |

## Ma'lumot turlari

### Personal info → Name, Phone number, User IDs
- **Collected:** Yes · **Shared:** No · **Processed ephemerally:** No · **Required:** Yes
- **Purposes:** App functionality, Account management
- Izoh: Telegram orqali kirishda keladi.

### Contacts → Contacts
- **Collected:** Yes · **Shared:** **No** · **Optional** (foydalanuvchi tanlaydi)
- **Purposes:** App functionality
- Izoh (formaga yozish shart emas, lekin ko'rib chiquvchi so'rasa shu): faqat foydalanuvchi
  "Profile photo archive → My contacts" ni tanlaganda va **faqat Telegram ID raqamlari** (ism, telefon
  yoki karta ma'lumoti emas). Faqat "so'rovchi shu odamning kontaktimi?" tekshiruvi uchun ishlatiladi,
  boshqa variant tanlansa **darhol o'chiriladi**. Ilova ichida rozilik oynasi shuni ochiq aytadi.

### Photos and videos → Photos
- **Collected:** Yes · **Shared:** **No** · **Required:** No (o'chirib qo'yish mumkin)
- **Purposes:** App functionality
- Izoh: Telegram'da **o'chirilgan** profil rasmlari nusxasi, ular tegishli odamning Telegram ID siga
  bog'lab saqlanadi. Ko'rish faqat Telegram o'zi ruxsat bergan odamlarga; egasi butunlay o'chirib
  tashlashi mumkin.

### Messages → Other in-app messages
- **Collected:** Yes · **Shared:** **No** · **Required:** No (default o'chiq, foydalanuvchi yoqadi)
- **Purposes:** App functionality
- Izoh (ko'rib chiquvchi so'rasa): faqat **shaxsiy (1:1) suhbatда** va faqat **ikkala odam ham Svipe
  ishlatib, ikkalasi ham yoqganда** o'sha suhbatning **o'chirilgan/tahrirlangan** xabarlari saqlanadi.
  Guruh/kanal/Maxfiy chatlarга hech qachon; rozilik bermagan odamning hech narsasi yuklanmaydi.
  Serverда **at-rest shifrlanadi** (kalit bazadan tashqarida); videо/fayllardan faqat tiniq muqova.
  Faqat o'sha ikki kishi ko'radi; muallif o'z xabarlarini istalgan payt o'chiradi; 90 kun saqlanadi.
  Ilovada majburiy Svipe ToS + kontekstли rozilik (chat ichидаги banner) bilan ochiladi.

### App activity → App interactions
- **Collected:** Yes · **Shared:** No · **Purposes:** App functionality, Analytics, Personalization

### App info and performance → Crash logs, Diagnostics
- **Collected:** Yes · **Shared:** No · **Purposes:** App functionality, Analytics

### Device or other IDs → Device or other IDs
- **Collected:** Yes · **Shared:** No · **Purposes:** App functionality (FCM push token)

## "Shared" nima uchun hamma joyda No

Biz ma'lumotni uchinchi tomonga **bermaymiz va sotmaymiz**. Xosting, obyekt saqlash va FCM —
Play ta'rifi bo'yicha "service provider", "sharing" emas. Agar kelajakda biror ma'lumot haqiqatan
uchinchi tomonga chiqsa, avval shu jadval va `app/privacy.py` yangilanishi shart.

## Formadan tashqari, lekin bog'liq

- **Privacy policy URL:** https://svipe.uz/privacy (Data safety formasida ham shu havola turishi kerak)
- **Account deletion URL / usuli:** ilova ichida + `support@svipe.uz`
- **Takedown:** Svipe ishlatmaydigan odam ham o'z rasmlarini o'chirishni so'rashi mumkin; ijro
  `tools/avatar_takedown.py --tg-id <id>` (serverda bitta buyruq).
