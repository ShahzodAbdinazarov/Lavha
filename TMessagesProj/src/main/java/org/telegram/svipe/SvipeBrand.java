package org.telegram.svipe;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebrands the strings where upstream Telegram names itself.
 *
 * <p>The fork is called Svipe, but most user-facing copy still reads "Telegram", and editing
 * {@code strings.xml} does not fix it: {@link org.telegram.messenger.BuildVars#USE_CLOUD_STRINGS} is
 * true, so {@code LocaleController} resolves a key from Telegram's cloud language pack first and only
 * falls back to our local resource when the pack has no such key. Every native key is in the pack, so
 * the pack always wins.
 *
 * <p>Two ways out were possible. Giving each leak a private {@code Svipe*} key does dodge the pack —
 * that is how {@code SvipeUpdateTitle} works — but it also loses the translation, because our
 * {@code values-uz} and {@code values-ru} carry only Svipe's own strings and everything else would
 * fall back to English. With ~90 leaks that trades a cosmetic bug for a real regression in every
 * language we do not translate ourselves.
 *
 * <p>So instead we keep whatever the pack returned, in whatever language, and rewrite only the brand
 * inside it. "Telegram" is spelled in Latin script in the Russian and Uzbek packs too, so one
 * substitution covers all languages.
 *
 * <p>{@link #KEYS} is an explicit allow-list, never a blanket search-and-replace. Most of the ~505
 * strings mentioning Telegram mean the service, the company, the network, a Telegram-branded product,
 * or another official client, and all of those stay true for a third-party client — rewriting them
 * would make the app assert something false, in every language, which is far worse than the leak we
 * are fixing. A key earns its place here only when every occurrence in it refers to the app the user
 * is holding.
 *
 * <p>Strings that mix both senses in one sentence are not listed here; they are corrected in
 * {@code strings.xml} directly, since they need a human-written result rather than a substitution.
 */
public final class SvipeBrand {

    private SvipeBrand() {}

    /** Matches the bare word, so "Telegram's" becomes "Svipe's" and "Telegrams" is left alone. */
    private static final Pattern TELEGRAM = Pattern.compile("\\bTelegram\\b");

    private static final String SVIPE = "Svipe";

    /**
     * String keys whose every "Telegram" means this app. Keep sorted by area; add only after checking
     * where the string is actually shown.
     */
    private static final Set<String> KEYS;

    static {
        Set<String> k = new HashSet<>();

        // ── Permission rationale and prominent disclosure ────────────────────────────────────────
        // Highest priority: Google Play reviewers watch these dialogs in the demo videos that the
        // sensitive-permission declarations require, and an app name that disagrees with the
        // declaration reads as an inaccurate declaration.
        k.add("PermissionStorageWithHint");
        k.add("PermissionNoAudioWithHint");
        k.add("PermissionNoBluetoothWithHint");
        k.add("PermissionNoAudioVideoWithHint");
        k.add("PermissionNoCameraWithHint");
        k.add("PermissionNoCameraMicVideo");
        k.add("PermissionNoLocation");
        k.add("PermissionNoLocationStory");
        k.add("PermissionNoLocationFriends");
        k.add("PermissionNoLocationNavigation");
        k.add("PermissionNoContactsSharing");
        k.add("PermissionNoContactsSaving");
        k.add("PermissionNoStorageAvatar");
        k.add("PermissionNoAudioStorageStory");
        k.add("PermissionDrawAboveOtherApps");
        k.add("PermissionDrawAboveOtherAppsGroupCall");
        k.add("PermissionXiaomiLockscreen");
        k.add("PermissionFSILockscreen");
        k.add("QRCodePermissionNoCameraWithHint");
        k.add("NotificationsPermissionAlertSubtitle");
        k.add("VoipNeedMicPermissionWithHint");
        k.add("VoipNeedMicCameraPermissionWithHint");
        k.add("VoipNeedCameraPermission");
        k.add("AgeVerificationNeedCameraPermission");
        k.add("BotLocationPermissionRequestDeniedApp");
        k.add("AllowBackgroundActivityInfo");
        k.add("AllowBackgroundActivityInfoOneUIBelowS");
        k.add("AllowBackgroundActivityInfoOneUIAboveS");
        // Login-time rationales for READ_PHONE_STATE / call-log auto-fill. The permission is granted to
        // this package, so the dialog has to name this package.
        k.add("AllowReadCall");
        k.add("AllowReadCallAndLog");
        k.add("AllowReadCallLog");
        k.add("AllowFillNumber");

        // ── Onboarding ───────────────────────────────────────────────────────────────────────────
        // The first screens a reviewer sees. Page1Title and the intro slide 1 copy are deliberately
        // absent: IntroActivity already overrides them with SvipeIntroTitle / SvipeIntroMessage.
        k.add("NoChats");
        k.add("Page2Message");
        k.add("Page3Message");
        k.add("Page4Message");
        k.add("Page5Message");
        k.add("Page6Message");

        // ── Notifications and lock screen ────────────────────────────────────────────────────────
        // These leak outside the app, into the notification shade and the lock screen, where the app
        // name is all the user sees.
        k.add("NotificationHiddenName");
        k.add("NotificationHiddenChatName");
        k.add("SecretChatName");
        k.add("ProfilePopupNotificationInfo");
        k.add("UnlockToUse");
        k.add("AppLocked");

        // ── Calls ────────────────────────────────────────────────────────────────────────────────
        // Call branding reaches the system call UI, Bluetooth head units and the phone's call log.
        k.add("VoipInCallBranding");
        k.add("VoipInVideoCallBranding");
        k.add("VoipInConferenceCallBranding");
        k.add("VoipInCallBrandingWithName");
        k.add("VoipInVideoCallBrandingWithName");
        k.add("VoipOutgoingCall");
        k.add("VoipErrorUnknown");
        k.add("CallViaTelegram");
        k.add("VoiceCallViaTelegram");
        k.add("VideoCallViaTelegram");
        k.add("IncomingCallsSystemSettingDescription");

        // ── Storage, cache and maintenance ───────────────────────────────────────────────────────
        // All of these describe an operation this app performs on this device.
        k.add("LowDiskSpaceMessage");
        k.add("ClearingCacheDescription");
        k.add("LocalDatabaseInfo");
        k.add("LocalDatabaseSize");
        k.add("StorageUsageTelegram");
        k.add("StorageUsageTelegramLess");
        k.add("ClearTelegramCache");
        k.add("TelegramCacheSize");
        k.add("OptimizingTelegram");
        k.add("MigrateOldFolderDescription");
        k.add("SdCardAlert");
        k.add("SdCardErrorDescription");
        k.add("ImportImportingInfo");

        // ── Version and update prompts ───────────────────────────────────────────────────────────
        k.add("TelegramVersion");
        k.add("AppUpdate");
        k.add("AppUpdateBeta");
        k.add("UpdateTelegram");
        k.add("UpdateAppAlert");
        k.add("UnsupportedMedia2");
        k.add("StoryUnsupported");
        // Telegram 12.10 replaced the plain "unsupported" text with a card carrying its own
        // "Update Telegram" line and button. Every occurrence here means the app in the user's hand —
        // the message is unreadable because THIS build is old — so it is ours to rename.
        k.add("UnsupportedBlockMessage");
        k.add("UnsupportedMessageMessage");
        k.add("Gift2ExportTONUpdateRequiredText");

        // ── Passcode and privacy ─────────────────────────────────────────────────────────────────
        k.add("ChangePasscodeInfo");
        k.add("EnterYourPasscodeInfo");
        k.add("EnterYourTelegramPasscode");
        k.add("CreatePasscodeInfoPIN");
        k.add("CreatePasscodeInfoPassword");
        k.add("ScreenCaptureAlert");

        // ── In-app browser ───────────────────────────────────────────────────────────────────────
        k.add("OpenInTelegramBrowser");
        k.add("BrowserSettingsCustomTabs");
        k.add("BrowserSettingsNoCustomTabsInfo");
        k.add("BrowserSettingsEnableInfo");
        k.add("BrowserSettingsCookiesInfo");
        k.add("BrowserExternalRestricted");

        // ── Misc app self-reference ──────────────────────────────────────────────────────────────
        k.add("UpdateContactsMessage");
        k.add("CreateNewThemeAlert");
        k.add("UseProxySponsorInfo");
        k.add("PremiumPreviewAppIconDescription");

        // Deliberately NOT here, after review — each of these is true as written for a third-party
        // client, and rewriting it would make the app assert something false in every language:
        //   InviteText2 / InviteToTelegram / InviteFriendsHelp / InviteUser — the invite link really is
        //     https://telegram.org/dl, so the invitee really does join Telegram.
        //   TelegramTones — server-supplied ringtones that come from Telegram.
        //   CallAvailableIn2 — Telegram's servers place a real PSTN call.
        //   WearAuthTitle — the separate official Telegram Wear OS app.
        //   PermissionBackgroundLocation / Page1Title — dead keys; already replaced by Svipe-owned ones.

        KEYS = Collections.unmodifiableSet(k);
    }

    /**
     * Returns {@code value} with the brand corrected when {@code key} is a self-reference, otherwise
     * {@code value} untouched. The set lookup runs on every string resolution, so the common path is a
     * single hash lookup and the regex only runs for listed keys.
     */
    public static String apply(String key, String value) {
        if (key == null || value == null || !KEYS.contains(key)) {
            return value;
        }
        return TELEGRAM.matcher(value).replaceAll(Matcher.quoteReplacement(SVIPE));
    }
}
