# Capabilities, special accesses and permissions

A plugin must be honest about what it needs. Declaring a capability does **not**
grant it: the host shows the permission screen and the user decides. If
something isn't granted, degrade gracefully — mark the feature unavailable
rather than failing silently.

```kotlin
override fun requiredCapabilities(): List<String> =
    listOf("audio.music", "notifications.actions", "system.background")
```

---

## The 51 capabilities

A capability is a human-language scenario plus the Android permissions behind
it. `audio.duck`, for example, is "lower other audio while Amalia speaks" and
carries the audio-focus permissions.

### AUDIO (5)
`audio.listen` · `audio.play` · `audio.music` · `audio.duck` · `audio.capture_system`

### CAPTURE (5)
`camera.photo` · `camera.video` · `screen.record` · `sensors.activity` · `sensors.body`

### COMMS (8)
`comms.call` · `comms.sms` · `comms.contacts` · `comms.sms_read` · `comms.call_log` ·
`comms.contacts_write` · `nfc.read` · `comms.answer`

### MEDIA (3)
`media.library_audio` · `media.library_video` · `media.library_images`

### DEVICE (7)
`device.wifi` · `device.bluetooth` · `device.brightness` · `device.flashlight` ·
`device.battery` · `network.nearby_wifi` · `bluetooth.scan`

### TIME (6)
`time.clock` · `time.alarm` · `time.timer` · `calendar.read` · `calendar.write` ·
`time.alarm_exact`

### NOTIFICATIONS (3)
`notifications.post` · `notifications.actions` · `notifications.listen`

### LOCATION (1)
`location.current`

### FILES (2)
`files.read` · `files.install`

### SYSTEM (11)
`screen.automate` · `system.launch_app` · `system.settings` · `system.web` ·
`system.background` · `sensors.biometric` · `screen.overlay` · `system.usage` ·
`system.autostart` · `system.wakelock` · `system.feedback`

> Some capabilities are declared by the host but have no ready-made tool yet —
> they mark a scenario the platform *permits*, and a plugin may implement it.

---

## The 12 special accesses

These are not runtime permissions. They are separate consents granted in
Android's own settings screens, and they unlock things permissions cannot:

`accessibility` · `notification_listener` · `overlay` · `usage_stats` ·
`exact_alarm` · `write_settings` · `do_not_disturb` · `battery_optimization` ·
`install_packages` · `media_projection` · `device_admin` · `modify_phone_state`

Every one carries a **risk description** in the host. If a risk is present, the
host is obliged to show it to the user before granting — so ask for special
accesses only when your feature genuinely cannot work without them.

Examples:

| Access | Real use |
|---|---|
| `accessibility` | act inside third-party apps ("send the message for me") |
| `notification_listener` | read incoming messages aloud |
| `overlay` | draw a floating control over other apps |
| `exact_alarm` | an alarm that must fire at a precise minute |
| `write_settings` | change screen brightness directly instead of opening a panel |

---

## Permission groups Amalia already declares

The host declares the Android permissions its own features use (microphone,
location, contacts, calendar, phone/SMS, sensors, NFC, media, notifications,
foreground services). Your plugin does **not** add permissions to the manifest —
`.amaliaplug` packages cannot change the app manifest. If your plugin needs
something the host does not declare, it cannot be done from a plugin; say so in
your description instead of failing at runtime.

---

## How the host enforces this

1. Amalia collects `requiredCapabilities()` from all **enabled** plugins.
2. The permission screen shows the union, grouped, with explanations — and it
   shows what the app is capable of building, not just what is installed.
3. Grants are checked at runtime. A missing grant shows as "unavailable" for
   that feature, with an action to open the right settings screen.
4. Disabling your plugin removes its capabilities from the set — users can
   take back access by switching the plugin off.

---

## Etiquette

- **Ask for the minimum.** A wallpaper plugin does not need `comms.sms`.
  Reviewers and users both notice.
- **Declare what you use, use what you declare.** Requesting one set and using
  another is the fastest way to lose trust.
- **Degrade, don't die.** Without `notifications.post`, keep the feature working
  in-app; the notification is a convenience, not the product.
- **Explain the special access.** If you need `accessibility`, say why in your
  plugin description in plain words.
