/*
 * Svipe mini app extension — ContactsManager and the attachment menu.
 * https://svipe.uz/bots/contacts
 * https://svipe.uz/bots/attach-menu
 *
 * Load it after the mini app's own scripts:
 *   <script src="https://svipe.uz/js/svipe-web-app.js"></script>
 *
 * Svipe lets a mini app ask for the user's Telegram contacts the way Telegram lets it ask for the
 * location: one dialog naming the bot, the answer kept per bot, a switch on the bot's profile. This
 * file gives that the same shape as Telegram.WebApp.LocationManager, so code written for one reads
 * like the other:
 *
 *   const contacts = Telegram.WebApp.ContactsManager;
 *   contacts.init(() => {
 *     if (!contacts.isContactsAvailable) return;      // not Svipe: hide the feature
 *     contacts.getContacts(list => {
 *       if (list === null) { ... declined ... }       // contacts.openSettings() from a button tap
 *       else { ... list of {user_id, first_name, last_name?, username?, phone_number?} ... }
 *     });
 *   });
 *
 * Other Telegram apps never answer the check, so init() waits briefly and then reports the feature
 * as unavailable instead of hanging. There, getContacts() offers the user Svipe in Telegram's own
 * popup (suggestSvipe()); its texts, link and repeat interval are the bot's to set, and the bot can
 * turn it off (see the page above).
 *
 * The attachment menu: Telegram puts only the bots it picks in the 📎 menu. Svipe lets any bot with
 * a mini app ask the user to add it there, with Telegram's own dialog. Shaped like Telegram's
 * addToHomeScreen() / checkHomeScreenStatus():
 *
 *   Telegram.WebApp.checkAttachmentMenuStatus(status => { ... });   // added | missed | declined | unsupported
 *   button.onclick = () => Telegram.WebApp.addToAttachmentMenu((added, error) => { ... });
 *
 * And what Telegram gives only its own attach bots, for every bot opened from Svipe's menu:
 *
 *   Telegram.WebApp.svipeInitData                 // receiver / chat, signed by Svipe: check it on your server
 *   Telegram.WebApp.sendToChat(preparedId, (sent, error) => { ... });   // like answerWebAppQuery
 */
(function () {
  'use strict';

  var Telegram = window.Telegram;
  if (!Telegram || !Telegram.WebView || !Telegram.WebApp) {
    console.error('[Svipe] Telegram.WebApp is missing: load svipe-web-app.js after the mini app SDK.');
    return;
  }
  if (Telegram.WebApp.ContactsManager) {
    return;
  }
  var WebView = Telegram.WebView;

  // How long init() waits for an app that may not know the event at all.
  var CHECK_TIMEOUT_MS = 1500;

  var isInited = false;
  var isContactsAvailable = false;
  var isAccessRequested = false;
  var isAccessGranted = false;

  var initCallbacks = [];
  var getCallbacks = [];
  var initTimer = null;

  function emit(eventType, data) {
    WebView.callEventCallbacks('webview:' + eventType, function (callback) {
      callback.call(Telegram.WebApp, data);
    });
  }

  function finishInit() {
    if (initTimer) {
      clearTimeout(initTimer);
      initTimer = null;
    }
    isInited = true;
    var callbacks = initCallbacks;
    initCallbacks = [];
    for (var i = 0; i < callbacks.length; i++) {
      try { callbacks[i](); } catch (e) { console.error(e); }
    }
  }

  WebView.onEvent('contacts_checked', function (eventType, eventData) {
    eventData = eventData || {};
    isContactsAvailable = !!eventData.available;
    isAccessRequested = isContactsAvailable && !!eventData.access_requested;
    isAccessGranted = isAccessRequested && !!eventData.access_granted;
    finishInit();
    emit('contactsManagerUpdated');
  });

  WebView.onEvent('contacts_requested', function (eventType, eventData) {
    eventData = eventData || {};
    var list = eventData.status === 'allowed' ? (eventData.contacts || []) : null;
    isAccessRequested = true;
    isAccessGranted = list !== null;
    var callbacks = getCallbacks;
    getCallbacks = [];
    for (var i = 0; i < callbacks.length; i++) {
      try { callbacks[i](list, list === null ? 'declined' : undefined); } catch (e) { console.error(e); }
    }
    if (list !== null) {
      emit('contactsRequested', { contacts: list });
    }
  });

  var manager = {};
  Object.defineProperty(manager, 'isInited', {
    get: function () { return isInited; },
    enumerable: true
  });
  Object.defineProperty(manager, 'isContactsAvailable', {
    get: function () { return isInited && isContactsAvailable; },
    enumerable: true
  });
  Object.defineProperty(manager, 'isAccessRequested', {
    get: function () { return isAccessRequested; },
    enumerable: true
  });
  Object.defineProperty(manager, 'isAccessGranted', {
    get: function () { return isAccessRequested && isAccessGranted; },
    enumerable: true
  });

  manager.init = function (callback) {
    if (isInited) {
      if (callback) {
        callback();
      }
      return manager;
    }
    if (callback) {
      initCallbacks.push(callback);
    }
    if (!initTimer) {
      initTimer = setTimeout(function () {
        initTimer = null;
        if (!isInited) {
          isContactsAvailable = false;
          finishInit();
        }
      }, CHECK_TIMEOUT_MS);
      WebView.postEvent('web_app_check_contacts', false);
    }
    return manager;
  };

  // callback(list, reason, suggestion) — the contact list, or null with a reason: 'declined' when
  // the user declined or revoked access, 'unavailable' in an app without the permission (another
  // Telegram app, or a plain browser). The dialog is shown once per bot; after that the stored
  // answer comes back without asking. It never throws, and it runs init() itself if the page has
  // not.
  //
  // Where contacts are unavailable it suggests Svipe first (suggestSvipe(), with its "Not now"
  // interval) and passes that result as `suggestion`. options.suggest_svipe: false turns this off;
  // an object there is passed to suggestSvipe() as its options.
  manager.getContacts = function (callback, options) {
    callback = callback || function () {};
    options = options || {};
    var answer = function (list, reason, suggestion) {
      try { callback(list, reason, suggestion); } catch (e) { console.error(e); }
    };
    manager.init(function () {
      if (!isContactsAvailable) {
        console.warn('[Svipe] Contacts access is not available in this app.');
        if (options.suggest_svipe === false) {
          return answer(null, 'unavailable');
        }
        var suggestOptions = typeof options.suggest_svipe === 'object' ? options.suggest_svipe : null;
        manager.suggestSvipe(suggestOptions, function (result, why) {
          answer(null, 'unavailable', { result: result, reason: why });
        });
        return;
      }
      getCallbacks.push(answer);
      WebView.postEvent('web_app_request_contacts', false);
    });
    return manager;
  };

  // Opens the bot's profile at the Contacts switch. Call it from a button the user tapped: apps
  // ignore it otherwise. Where there is nothing to open it only logs a warning.
  manager.openSettings = function () {
    if (!isInited || !isContactsAvailable) {
      console.warn('[Svipe] Contacts access is not available in this app.');
      return manager;
    }
    if (!isAccessRequested) {
      console.warn('[Svipe] Contacts access was not requested yet.');
      return manager;
    }
    if (isAccessGranted) {
      console.warn('[Svipe] Contacts access is already granted, no need to go to settings.');
      return manager;
    }
    WebView.postEvent('web_app_open_contacts_settings', false);
    return manager;
  };

  // --- Suggesting Svipe where contacts are not available -------------------------------------
  //
  // Telegram's own apps never share contacts with a mini app. Where the user is in one of them,
  // suggestSvipe() shows Telegram's native popup (Telegram.WebApp.showPopup) suggesting Svipe, a
  // Telegram client in which the same account can sync contacts with the bot. The bot decides when
  // to call it, what it says, where the button leads and how often it may come back.

  var SUGGEST_LINK = 'https://svipe.uz/#get';
  var SUGGEST_KEY = 'svipe_suggest_dismissed_at';
  var SUGGEST_REMIND_AFTER_DAYS = 7;
  var DAY_MS = 24 * 60 * 60 * 1000;

  // Telegram's popup limits; showPopup throws on anything longer.
  var TITLE_MAX = 64;
  var MESSAGE_MAX = 256;
  var BUTTON_MAX = 64;

  var SUGGEST_TEXTS = {
    en: {
      title: 'Sync your contacts automatically',
      message: 'Telegram does not share your contacts with mini apps. Svipe is a Telegram app with the same account and chats: open this mini app there and sync your contacts in one tap.',
      install_button_text: 'Get Svipe',
      cancel_button_text: 'Not now'
    },
    uz: {
      title: 'Kontaktlarni avtomatik sinxronlang',
      message: 'Telegram mini ilovalarga kontaktlaringizni bermaydi. Svipe — o\'sha akkaunt va chatlar bilan ishlaydigan Telegram ilovasi: bu mini ilovani unda oching va kontaktlaringizni bir bosishda sinxronlang.',
      install_button_text: 'Svipe\'ni o\'rnatish',
      cancel_button_text: 'Hozir emas'
    },
    ru: {
      title: 'Синхронизируйте контакты автоматически',
      message: 'Telegram не передаёт ваши контакты мини-приложениям. Svipe — приложение Telegram с тем же аккаунтом и чатами: откройте в нём это мини-приложение и синхронизируйте контакты одним нажатием.',
      install_button_text: 'Установить Svipe',
      cancel_button_text: 'Не сейчас'
    }
  };

  // The same popup where the attachment menu is unavailable, with its own texts.
  var ATTACH_SUGGEST_TEXTS = {
    en: {
      title: 'Open this app from any chat',
      message: 'Telegram adds only a few chosen bots to the attachment menu. Svipe is a Telegram app with the same account and chats: open this mini app there to add it to your 📎 menu.',
      install_button_text: 'Get Svipe',
      cancel_button_text: 'Not now'
    },
    uz: {
      title: 'Bu ilovani istalgan chatdan oching',
      message: 'Telegram 📎 menyusiga faqat o\'zi tanlagan bir nechta botni qo\'shadi. Svipe — o\'sha akkaunt va chatlar bilan ishlaydigan Telegram ilovasi: bu mini ilovani unda oching va 📎 menyusiga qo\'shing.',
      install_button_text: 'Svipe\'ni o\'rnatish',
      cancel_button_text: 'Hozir emas'
    },
    ru: {
      title: 'Открывайте это приложение из любого чата',
      message: 'Telegram добавляет в меню вложений только нескольких выбранных ботов. Svipe — приложение Telegram с тем же аккаунтом и чатами: откройте в нём это мини-приложение и добавьте его в меню 📎.',
      install_button_text: 'Установить Svipe',
      cancel_button_text: 'Не сейчас'
    }
  };

  var SIDE_SUGGEST_TEXTS = {
    en: {
      title: 'Keep this app one tap away',
      message: 'Telegram does not let mini apps into its menu. Svipe is a Telegram app with the same account and chats: open this mini app there to add it to the side menu.',
      install_button_text: 'Get Svipe',
      cancel_button_text: 'Not now'
    },
    uz: {
      title: 'Bu ilovani bir bosishda oching',
      message: 'Telegram mini ilovalarni o\'z menyusiga qo\'ymaydi. Svipe — o\'sha akkaunt va chatlar bilan ishlaydigan Telegram ilovasi: bu mini ilovani unda oching va yon menyuga qo\'shing.',
      install_button_text: 'Svipe\'ni o\'rnatish',
      cancel_button_text: 'Hozir emas'
    },
    ru: {
      title: 'Это приложение — в одно нажатие',
      message: 'Telegram не добавляет мини-приложения в своё меню. Svipe — приложение Telegram с тем же аккаунтом и чатами: откройте в нём это мини-приложение и добавьте его в боковое меню.',
      install_button_text: 'Установить Svipe',
      cancel_button_text: 'Не сейчас'
    }
  };

  var PROFILE_SUGGEST_TEXTS = {
    en: {
      title: 'Show this app on your profile',
      message: 'Telegram has no bot tabs on profiles. Svipe is a Telegram app with the same account and chats: open this mini app there to add its tab to your profile.',
      install_button_text: 'Get Svipe',
      cancel_button_text: 'Not now'
    },
    uz: {
      title: 'Bu ilovani profilingizda ko\'rsating',
      message: 'Telegram\'da profilda bot tablari yo\'q. Svipe — o\'sha akkaunt va chatlar bilan ishlaydigan Telegram ilovasi: bu mini ilovani unda oching va uning tabini profilingizga qo\'shing.',
      install_button_text: 'Svipe\'ni o\'rnatish',
      cancel_button_text: 'Hozir emas'
    },
    ru: {
      title: 'Покажите это приложение в профиле',
      message: 'В Telegram у профилей нет вкладок ботов. Svipe — приложение Telegram с тем же аккаунтом и чатами: откройте в нём это мини-приложение и добавьте его вкладку в свой профиль.',
      install_button_text: 'Установить Svipe',
      cancel_button_text: 'Не сейчас'
    }
  };

  var suggestOpen = false;

  function userLanguage() {
    var user = Telegram.WebApp.initDataUnsafe && Telegram.WebApp.initDataUnsafe.user;
    var code = (user && user.language_code) || '';
    return code.toLowerCase().split('-')[0];
  }

  function suggestTexts(options) {
    var language = (options.language || userLanguage()).toLowerCase().split('-')[0];
    var texts = options.feature === 'attach_menu' ? ATTACH_SUGGEST_TEXTS
      : options.feature === 'side_menu' ? SIDE_SUGGEST_TEXTS
      : options.feature === 'profile_tab' ? PROFILE_SUGGEST_TEXTS : SUGGEST_TEXTS;
    var base = texts[language] || texts.en;
    var texts = {};
    for (var key in base) {
      texts[key] = typeof options[key] === 'string' && options[key] ? options[key] : base[key];
    }
    return texts;
  }

  function cloudStorage() {
    var WebApp = Telegram.WebApp;
    return WebApp.CloudStorage && WebApp.isVersionAtLeast && WebApp.isVersionAtLeast('6.9') ? WebApp.CloudStorage : null;
  }

  // The time of the last "Not now", from Telegram's CloudStorage (per user and bot, on every
  // device) or, in older apps, this device's localStorage.
  function readDismissedAt(callback) {
    var storage = cloudStorage();
    if (storage) {
      try {
        storage.getItem(SUGGEST_KEY, function (error, value) {
          callback(error ? 0 : (parseInt(value, 10) || 0));
        });
        return;
      } catch (e) {}
    }
    try {
      callback(parseInt(window.localStorage.getItem(SUGGEST_KEY), 10) || 0);
    } catch (e) {
      callback(0);
    }
  }

  function writeDismissedAt(value) {
    var storage = cloudStorage();
    if (storage) {
      try {
        storage.setItem(SUGGEST_KEY, String(value));
        return;
      } catch (e) {}
    }
    try {
      window.localStorage.setItem(SUGGEST_KEY, String(value));
    } catch (e) {}
  }

  // callback(result, reason): result is 'opened' (the user took the button), 'dismissed' (Not now,
  // or the popup was closed) or 'skipped' (nothing was shown, reason says why: 'available',
  // 'snoozed', 'not_telegram', 'unsupported', 'busy').
  manager.suggestSvipe = function (options, callback) {
    if (typeof options === 'function') {
      callback = options;
      options = null;
    }
    options = options || {};
    var done = function (result, reason) {
      if (callback) {
        try { callback(result, reason); } catch (e) { console.error(e); }
      }
    };
    var WebApp = Telegram.WebApp;

    manager.init(function () {
      if (isContactsAvailable) {
        return done('skipped', 'available');
      }
      if (!WebApp.platform || WebApp.platform === 'unknown') {
        return done('skipped', 'not_telegram');
      }
      if (!WebApp.isVersionAtLeast || !WebApp.isVersionAtLeast('6.2')) {
        return done('skipped', 'unsupported');
      }
      if (suggestOpen) {
        return done('skipped', 'busy');
      }
      // Taken from here on, not when the popup opens: the "Not now" lookup below is asynchronous,
      // and two calls in a row must not both get past it.
      suggestOpen = true;
      var remindAfterDays = typeof options.remind_after_days === 'number' ? options.remind_after_days : SUGGEST_REMIND_AFTER_DAYS;
      var show = function () {
        var texts = suggestTexts(options);
        var link = options.link || SUGGEST_LINK;
        try {
          WebApp.showPopup({
            title: texts.title.substring(0, TITLE_MAX),
            message: texts.message.substring(0, MESSAGE_MAX),
            buttons: [
              { id: 'install', type: 'default', text: texts.install_button_text.substring(0, BUTTON_MAX) },
              { id: 'cancel', type: 'default', text: texts.cancel_button_text.substring(0, BUTTON_MAX) }
            ]
          }, function (buttonId) {
            suggestOpen = false;
            if (buttonId === 'install') {
              WebApp.openLink(link);
              done('opened');
            } else {
              writeDismissedAt(Date.now());
              done('dismissed');
            }
          });
        } catch (e) {
          // WebAppPopupOpened: another popup of the page is on screen.
          suggestOpen = false;
          done('skipped', 'busy');
        }
      };
      if (options.force) {
        return show();
      }
      readDismissedAt(function (dismissedAt) {
        if (dismissedAt && (remindAfterDays < 0 || Date.now() - dismissedAt < remindAfterDays * DAY_MS)) {
          suggestOpen = false;
          return done('skipped', 'snoozed');
        }
        show();
      });
    });
    return manager;
  };

  // Forgets "Not now", so the next suggestSvipe() shows the popup again.
  manager.resetSvipeSuggestion = function () {
    writeDismissedAt(0);
    return manager;
  };

  Telegram.WebApp.ContactsManager = manager;

  // --- The attachment menu ---------------------------------------------------------------------
  //
  // Any bot with a mini app may ask to be added to the user's 📎 menu. The user sees Telegram's own
  // attach bot dialog; once added, the bot opens from the menu in any chat. A mini app gets the
  // dialog once: after "Cancel", or after the user removed the bot, it is answered 'declined'.

  var ATTACH_TIMEOUT_MS = 1500;
  var attachStatus = null;          // the last known status, null before the first answer
  var attachCheckCallbacks = [];
  var attachCheckTimer = null;
  var attachAddCallbacks = [];

  function answerAttachCheck(status) {
    if (attachCheckTimer) {
      clearTimeout(attachCheckTimer);
      attachCheckTimer = null;
    }
    attachStatus = status;
    var callbacks = attachCheckCallbacks;
    attachCheckCallbacks = [];
    for (var i = 0; i < callbacks.length; i++) {
      try { callbacks[i](status); } catch (e) { console.error(e); }
    }
  }

  function answerAttachAdd(added, error) {
    var callbacks = attachAddCallbacks;
    attachAddCallbacks = [];
    for (var i = 0; i < callbacks.length; i++) {
      try { callbacks[i](added, error); } catch (e) { console.error(e); }
    }
  }

  WebView.onEvent('attach_menu_checked', function (eventType, eventData) {
    var status = (eventData && eventData.status) || 'missed';
    answerAttachCheck(status);
    emit('attachmentMenuChecked', { status: status });
  });

  WebView.onEvent('attach_menu_added', function () {
    attachStatus = 'added';
    answerAttachAdd(true);
    emit('attachmentMenuAdded');
  });

  WebView.onEvent('attach_menu_failed', function (eventType, eventData) {
    var error = (eventData && eventData.error) || 'UNSUPPORTED';
    if (error === 'USER_DECLINED') {
      attachStatus = 'declined';
    }
    answerAttachAdd(false, error);
    emit('attachmentMenuFailed', { error: error });
  });

  // callback(status): 'added', 'missed' (not added, the user may be asked), 'declined' (the user said
  // no or removed the bot; asking again only answers 'declined') or 'unsupported' (another Telegram
  // app, or a plain browser). Other apps never answer, so 'unsupported' comes after a short wait.
  Telegram.WebApp.checkAttachmentMenuStatus = function (callback) {
    if (callback) {
      attachCheckCallbacks.push(callback);
    }
    if (!attachCheckTimer) {
      attachCheckTimer = setTimeout(function () {
        attachCheckTimer = null;
        answerAttachCheck('unsupported');
      }, ATTACH_TIMEOUT_MS);
      WebView.postEvent('web_app_check_attach_menu', false);
    }
    return Telegram.WebApp;
  };

  // Shows Telegram's attach bot dialog. Call it from a button the user just tapped: apps ignore it
  // otherwise. callback(added, error, suggestion): error is 'USER_DECLINED' or 'UNSUPPORTED'. Where the
  // attachment menu is unavailable the user is first offered Svipe (options.suggest_svipe works as in
  // getContacts()), and suggestion is that result. Never throws.
  Telegram.WebApp.addToAttachmentMenu = function (callback, options) {
    callback = callback || function () {};
    options = options || {};
    var answer = function (added, error, suggestion) {
      try { callback(added, error, suggestion); } catch (e) { console.error(e); }
    };
    var go = function (status) {
      if (status === 'added') {
        return answer(true);
      }
      if (status === 'declined') {
        return answer(false, 'USER_DECLINED');
      }
      if (status === 'unsupported') {
        console.warn('[Svipe] The attachment menu is not available in this app.');
        if (options.suggest_svipe === false) {
          return answer(false, 'UNSUPPORTED');
        }
        var suggestOptions = {};
        var own = typeof options.suggest_svipe === 'object' && options.suggest_svipe ? options.suggest_svipe : {};
        for (var key in own) {
          suggestOptions[key] = own[key];
        }
        suggestOptions.feature = 'attach_menu';
        manager.suggestSvipe(suggestOptions, function (result, why) {
          answer(false, 'UNSUPPORTED', { result: result, reason: why });
        });
        return;
      }
      attachAddCallbacks.push(answer);
      WebView.postEvent('web_app_add_to_attach_menu', false);
    };
    if (attachStatus === 'missed') {
      go(attachStatus);
    } else {
      Telegram.WebApp.checkAttachmentMenuStatus(go);
    }
    return Telegram.WebApp;
  };
  //
  // The side menu (the menu on Svipe's chat list), a permission of its own: at most three bots, and
  // asked for a fourth the user picks which one it replaces. The same shape as the attachment menu's.

  var sideStatus = null;
  var sideCheckCallbacks = [];
  var sideCheckTimer = null;
  var sideAddCallbacks = [];

  function answerSideCheck(status) {
    if (sideCheckTimer) {
      clearTimeout(sideCheckTimer);
      sideCheckTimer = null;
    }
    sideStatus = status;
    var callbacks = sideCheckCallbacks;
    sideCheckCallbacks = [];
    for (var i = 0; i < callbacks.length; i++) {
      try { callbacks[i](status); } catch (e) { console.error(e); }
    }
  }

  function answerSideAdd(added, error) {
    var callbacks = sideAddCallbacks;
    sideAddCallbacks = [];
    for (var i = 0; i < callbacks.length; i++) {
      try { callbacks[i](added, error); } catch (e) { console.error(e); }
    }
  }

  WebView.onEvent('side_menu_checked', function (eventType, eventData) {
    var status = (eventData && eventData.status) || 'missed';
    answerSideCheck(status);
    emit('sideMenuChecked', { status: status });
  });

  WebView.onEvent('side_menu_added', function () {
    sideStatus = 'added';
    answerSideAdd(true);
    emit('sideMenuAdded');
  });

  WebView.onEvent('side_menu_failed', function (eventType, eventData) {
    var error = (eventData && eventData.error) || 'UNSUPPORTED';
    if (error === 'USER_DECLINED') {
      sideStatus = 'declined';
    }
    answerSideAdd(false, error);
    emit('sideMenuFailed', { error: error });
  });

  // callback(status): 'added', 'missed', 'declined' or 'unsupported', as checkAttachmentMenuStatus().
  Telegram.WebApp.checkSideMenuStatus = function (callback) {
    if (callback) {
      sideCheckCallbacks.push(callback);
    }
    if (!sideCheckTimer) {
      sideCheckTimer = setTimeout(function () {
        sideCheckTimer = null;
        answerSideCheck('unsupported');
      }, ATTACH_TIMEOUT_MS);
      WebView.postEvent('web_app_check_side_menu', false);
    }
    return Telegram.WebApp;
  };

  // Asks the user to put this bot in the side menu. Call it from a button the user just tapped.
  // callback(added, error, suggestion), as addToAttachmentMenu(). With three bots there already, the
  // user is asked which one this replaces; "Cancel" there is 'USER_DECLINED'. Never throws.
  Telegram.WebApp.addToSideMenu = function (callback, options) {
    callback = callback || function () {};
    options = options || {};
    var answer = function (added, error, suggestion) {
      try { callback(added, error, suggestion); } catch (e) { console.error(e); }
    };
    var go = function (status) {
      if (status === 'added') {
        return answer(true);
      }
      if (status === 'declined') {
        return answer(false, 'USER_DECLINED');
      }
      if (status === 'unsupported') {
        console.warn('[Svipe] The side menu is not available in this app.');
        if (options.suggest_svipe === false) {
          return answer(false, 'UNSUPPORTED');
        }
        var suggestOptions = {};
        var own = typeof options.suggest_svipe === 'object' && options.suggest_svipe ? options.suggest_svipe : {};
        for (var key in own) {
          suggestOptions[key] = own[key];
        }
        suggestOptions.feature = 'side_menu';
        manager.suggestSvipe(suggestOptions, function (result, why) {
          answer(false, 'UNSUPPORTED', { result: result, reason: why });
        });
        return;
      }
      sideAddCallbacks.push(answer);
      WebView.postEvent('web_app_add_to_side_menu', false);
    };
    if (sideStatus === 'missed') {
      go(sideStatus);
    } else {
      Telegram.WebApp.checkSideMenuStatus(go);
    }
    return Telegram.WebApp;
  };

  //
  // A tab on the user's own profile, a permission of its own: at most three, and asked for a fourth
  // the user picks which one it replaces. The same shape as the side menu's.

  var profileStatus = null;
  var profileCheckCallbacks = [];
  var profileCheckTimer = null;
  var profileAddCallbacks = [];

  function answerProfileCheck(status) {
    if (profileCheckTimer) {
      clearTimeout(profileCheckTimer);
      profileCheckTimer = null;
    }
    profileStatus = status;
    var callbacks = profileCheckCallbacks;
    profileCheckCallbacks = [];
    for (var i = 0; i < callbacks.length; i++) {
      try { callbacks[i](status); } catch (e) { console.error(e); }
    }
  }

  function answerProfileAdd(added, error) {
    var callbacks = profileAddCallbacks;
    profileAddCallbacks = [];
    for (var i = 0; i < callbacks.length; i++) {
      try { callbacks[i](added, error); } catch (e) { console.error(e); }
    }
  }

  WebView.onEvent('profile_tab_checked', function (eventType, eventData) {
    var status = (eventData && eventData.status) || 'missed';
    answerProfileCheck(status);
    emit('profileTabChecked', { status: status });
  });

  WebView.onEvent('profile_tab_added', function () {
    profileStatus = 'added';
    answerProfileAdd(true);
    emit('profileTabAdded');
  });

  WebView.onEvent('profile_tab_failed', function (eventType, eventData) {
    var error = (eventData && eventData.error) || 'UNSUPPORTED';
    if (error === 'USER_DECLINED') {
      profileStatus = 'declined';
    }
    answerProfileAdd(false, error);
    emit('profileTabFailed', { error: error });
  });

  // callback(status): 'added', 'missed', 'declined' or 'unsupported', as checkAttachmentMenuStatus().
  Telegram.WebApp.checkProfileTabStatus = function (callback) {
    if (callback) {
      profileCheckCallbacks.push(callback);
    }
    if (!profileCheckTimer) {
      profileCheckTimer = setTimeout(function () {
        profileCheckTimer = null;
        answerProfileCheck('unsupported');
      }, ATTACH_TIMEOUT_MS);
      WebView.postEvent('web_app_check_profile_tab', false);
    }
    return Telegram.WebApp;
  };

  // Asks the user to put this bot's tab on their profile. Call it from a button the user just tapped.
  // callback(added, error, suggestion), as addToAttachmentMenu(). With three bots there already, the
  // user is asked which one this replaces; "Cancel" there is 'USER_DECLINED'. Never throws.
  Telegram.WebApp.addToProfile = function (callback, options) {
    callback = callback || function () {};
    options = options || {};
    var answer = function (added, error, suggestion) {
      try { callback(added, error, suggestion); } catch (e) { console.error(e); }
    };
    var go = function (status) {
      if (status === 'added') {
        return answer(true);
      }
      if (status === 'declined') {
        return answer(false, 'USER_DECLINED');
      }
      if (status === 'unsupported') {
        console.warn('[Svipe] Profile tabs are not available in this app.');
        if (options.suggest_svipe === false) {
          return answer(false, 'UNSUPPORTED');
        }
        var suggestOptions = {};
        var own = typeof options.suggest_svipe === 'object' && options.suggest_svipe ? options.suggest_svipe : {};
        for (var key in own) {
          suggestOptions[key] = own[key];
        }
        suggestOptions.feature = 'profile_tab';
        manager.suggestSvipe(suggestOptions, function (result, why) {
          answer(false, 'UNSUPPORTED', { result: result, reason: why });
        });
        return;
      }
      profileAddCallbacks.push(answer);
      WebView.postEvent('web_app_add_to_profile', false);
    };
    if (profileStatus === 'missed') {
      go(profileStatus);
    } else {
      Telegram.WebApp.checkProfileTabStatus(go);
    }
    return Telegram.WebApp;
  };

  //
  // What Telegram gives only the bots it picked, for every bot opened from Svipe's attachment menu.

  // Who the chat is with: Telegram signs receiver / chat into initData for its own attach bots; for any
  // other bot Svipe signs them itself. The app passes them as the launch param tgWebAppSvipeData, which
  // Telegram's SDK keeps with every tgWebApp* param. Send svipeInitData to your server and check it
  // there (svipe.uz/bots/attach-menu#chat-context); initDataUnsafe.receiver / chat are for display only.
  var svipeInitData = (WebView.initParams && WebView.initParams.tgWebAppSvipeData) || '';
  Telegram.WebApp.svipeInitData = svipeInitData;
  if (svipeInitData) {
    var unsafe = Telegram.WebApp.initDataUnsafe;
    var pairs = svipeInitData.split('&');
    for (var p = 0; p < pairs.length; p++) {
      var eq = pairs[p].indexOf('=');
      var name = decodeURIComponent(pairs[p].slice(0, eq).replace(/\+/g, ' '));
      var value = decodeURIComponent(pairs[p].slice(eq + 1).replace(/\+/g, ' '));
      if ((name === 'receiver' || name === 'chat') && unsafe && !unsafe[name]) {
        try { unsafe[name] = JSON.parse(value); } catch (e) { console.error(e); }
      } else if (name === 'start_param' && unsafe && !unsafe.start_param) {
        // A bot with only a menu button gets no start_param from Telegram here.
        unsafe.start_param = value;
      }
    }
  }

  // Telegram's attach bots send to the chat with answerWebAppQuery(query_id). Here the bot prepares the
  // message with the Bot API (savePreparedInlineMessage), and the mini app hands its id to the app,
  // which sends it to the chat the mini app was opened in and closes it, the same as answerWebAppQuery.
  // callback(sent, error): error is 'UNSUPPORTED' (not opened from the attachment menu in a chat, or
  // another Telegram app), 'MESSAGE_EXPIRED' or 'ALREADY_SENT' (one message per launch, as one answer
  // per query_id).
  var SEND_TIMEOUT_MS = 1500;
  var sendCallback = null;
  var sendTimer = null;

  function answerSend(sent, error) {
    if (sendTimer) {
      clearTimeout(sendTimer);
      sendTimer = null;
    }
    var callback = sendCallback;
    sendCallback = null;
    if (callback) {
      try { callback(sent, error); } catch (e) { console.error(e); }
    }
  }

  // The app knows the event and is sending: the wait for an app that doesn't is over.
  WebView.onEvent('send_to_chat_accepted', function () {
    if (sendTimer) {
      clearTimeout(sendTimer);
      sendTimer = null;
    }
  });

  WebView.onEvent('sent_to_chat', function () {
    answerSend(true);
    emit('sentToChat');
  });

  WebView.onEvent('send_to_chat_failed', function (eventType, eventData) {
    var error = (eventData && eventData.error) || 'UNSUPPORTED';
    answerSend(false, error);
    emit('sendToChatFailed', { error: error });
  });

  Telegram.WebApp.sendToChat = function (preparedMessageId, callback) {
    if (sendCallback) {
      try { callback && callback(false, 'BUSY'); } catch (e) { console.error(e); }
      return Telegram.WebApp;
    }
    sendCallback = callback || function () {};
    // An app that doesn't know the event never answers; one that does accepts it at once, then sends.
    sendTimer = setTimeout(function () {
      sendTimer = null;
      answerSend(false, 'UNSUPPORTED');
    }, SEND_TIMEOUT_MS);
    WebView.postEvent('web_app_send_to_chat', false, { id: String(preparedMessageId || '') });
    return Telegram.WebApp;
  };
})();
