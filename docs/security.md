# Trust model — read this before shipping

Amalia's plugin system makes a deliberate trade: **powerful plugins, trusted
authors**. This page states exactly what that means, without marketing.

---

## The short version

| Property | Reality |
|---|---|
| Sandbox | **None.** A code plugin runs inside the Amalia process, with the app's permissions. |
| Signature | **None.** Packages are not signed. |
| Integrity | An optional `sha256` from the catalog is verified before install. |
| Manifest changes | **Impossible.** A plugin cannot add Android permissions; `.amaliaplug` cannot alter the app manifest. |
| Visual system | Plugins do not paint freely — see below. |
| Kill switch | Disabling a plugin removes its contributions instantly. |

**Install code plugins only from authors you trust.** A code plugin can do
anything the app itself can do: read what Amalia can read, send what Amalia can
send, and touch the files Amalia can touch.

---

## What the host still controls

Even a fully trusted plugin cannot:

1. **Rewrite the navigation graph.** A plugin adds screens with a route and
   receives a narrow `ScreenHost` (`goBack`, `navigate`). It never sees the
   `NavController`. Routes colliding with core routes are skipped.
2. **Reach app singletons.** Everything goes through `PluginContext` and the
   facades (`ScreenHost`, `NotificationHost`, `AudioSessionHost`). There is no
   handle to the DI container, the settings store, or the engines registry beyond
   the declared contributions.
3. **Create notification channels.** The host owns channels so the user can
   manage all of them in one place.
4. **Free-draw the interface.** Icons come from the host vector set (`iconName`);
   home-screen visuals occupy fixed *slots* with prepared state. "Glass, light,
   air" is the product, not a suggestion. The motif layer belongs to the theme.
5. **Grant itself anything.** A plugin declares capabilities; the host asks the
   user, showing explanations and — for special accesses — risk warnings.
6. **Break the assistant.** A malformed prompt contribution cannot rewrite the
   JSON answer contract the host relies on, because the host's own instructions
   take precedence.

---

## What a malicious plugin could do

Being honest is more useful than a security theatre claim:

- Read the conversation history (there is no per-plugin storage isolation).
- Make network requests with the app's identity.
- Reflect on internals reachable from `appContext` and from any class on the
  app's classpath — a code plugin is not limited to the documented SDK surface.
- Show notifications, start activities, and keep a foreground service running.

There is no sandbox that would stop this without a much heavier architecture.
The mitigation is **review and provenance**, exactly as in early browser
extension ecosystems.

---

## Practical guidance

**Installing**
- Prefer plugins from a catalog you trust.
- Prefer declarative plugins (`.json` only) when they do the job: no code, no
  process access.
- Check `sha256` for anything you download — a mismatch aborts the install.

**Publishing**
- Declare the **minimum** capabilities you actually use.
- Explain special accesses (`accessibility`, `notification_listener`, …) in your
  description in plain words.
- Don't ship code if a declarative plugin would do.
- Version honestly, and raise `minHostVersion` instead of relying on
  undocumented behaviour.

**Reviewing a plugin (what to look at)**
- `plugin.json`: `category`, `entryPoint`, requested capabilities.
- For code plugins: the source. Ask for it. If a publisher won't show the source
  of a plugin that requests `accessibility` or `comms.*`, don't install it.

---

## Roadmap of trust (not implemented yet)

These are planned, and this file will be updated when they land:

- **Package signing** and a publisher key model.
- A published `:plugin-api` artifact so plugins compile against a stable,
  documented surface instead of a checkout.
- Capability enforcement at the API boundary (a plugin physically unable to call
  what it did not declare).
- A review process for the default catalog.

Until then: treat every code plugin as an app you are installing.
