# Design Guide

Monochrome, quiet, legible. The app delivers news nobody wants; the interface should not compete with it.

## Theme (`ui/Theme.kt`)
- **Colour**: a grey ramp with one accent: white-ish (`Bone`) on near-black in dark mode, near-black (`Ink`) on white in light mode. The one hue is **Alert** red, kept for what needs the user (a possible match) and for errors. Status is told apart by label, icon and fill, never by colour alone. No dynamic colour.
- **Contrast**: text is well above 4.5:1. The `outline` colour (a status badge's border, a progress track, a field's edge) is at least **3:1** against every background in its theme: dark `#707070` (3.3:1 on the lightest dark surface, 4.0:1 on the page), light `#808080` (3.3:1 on the darkest light surface, 3.7:1 on the page). `outlineVariant` is for decorative dividers and card edges only, which carry no meaning of their own. `ThemeContrastTest` holds the ratios.
- **Type** (`AppTypography`): titleLarge for screen titles, titleMedium for card titles, labelLarge for section headers, labelMedium for field labels, bodyLarge/bodyMedium for content, bodySmall for captions.
- **Spacing** (`Spacing`): xs 4, sm 8, md 16, lg 24, xl 32 dp. Screens and cards pad `md`; items in a column sit `md` apart.
- The launch theme `Theme.CleanUnderwear.Launch` follows the phone's night mode for the first window and the Android 12+ system splash (`res/values/` is light, `#F7F7F7`; `res/values-night/` is dark, `#0A0A0A`), so there is no flash of the wrong colour. `MainActivity` then applies the app's own light/dark preference. The app draws edge to edge.

## Components (`ui/Components.kt`)
Use these rather than building a screen's own:
- `AppTopBar`, `AppSection`, `AppCaption`, `AppCard` (with `emphasis` for what needs the user)
- `AppAction` (the main button), `AppTextAction` (secondary), `AppDialog` (its body scrolls; `dismiss = null` for a dialog that only informs, so it has one button, not two that do the same)
- `AppSnackbars` / `LocalAppSnackbars`: a one-line message with an action (Undo) from any screen. It runs in the app's own scope, so Undo is still there after Back.
- `contentWidth()` caps a screen's content at 640 dp and centres it; `screenInsets()` is the Scaffold inset set plus the display cut-out; `LocalBottomBarHeight` is the measured height of the bar of an operation put aside, so lists and the snackbar clear it.
- `LoadingCaption`, `StatusBadge` / `statusSpec` (the one place a status gets its label and icon), `DetailRow`

## Busy screen (`ui/OperationOverlay.kt`)
While an operation runs, everything behind it, rail included, is darkened and blurred (blur needs Android 12 and a device that allows it; without it the screen is darkened further). The loader and what is happening sit in the middle, **Cancel** at the bottom; Cancel keeps what was finished before it. It is always drawn dark, in either theme.
- An operation that keeps going on its own says so, and **Keep using the app** (or Back) puts it aside: a bar at the bottom shows it is still running, with Cancel, and tapping it brings the busy screen back. Reading contacts keeps going while the app is in use; the records check runs in WorkManager and keeps going after the user leaves the app.
- Under the loader, what has turned up so far, a line each: the records check's tally (checked, each possible match by name and kind, weak matches, contacts whose sites didn't answer); Fill in details' tally (how many have people to choose from at the end) and its last few contacts ("3 people found", "nobody found"); for reading contacts, how many were read, merged and made ready. The bar shows the first line.
- An operation that needs the user (a lookup in the browser) has no "keep using" option.
- Delete everything can't be cancelled: half a deletion is worse than none, and it takes a moment.
- In the browser, an automated page is darkened and blurred the same way, with Cancel at the bottom; it lifts when the page needs the user.

## Words
All text a person reads in the Compose screens is in `res/values/strings.xml` (`stringResource`, `pluralStringResource` for counts), so it can be translated; add a `values-xx/` folder to translate. A string is never built from pieces (`"contact" + "s"`): a sentence is one resource, with positional arguments (`%1$s`), and a count is a `<plurals>`. Not yet in resources: text produced inside `MainViewModel` (snackbar results, the busy screen's description and tallies), the domain classes' labels (`Recommendation.label`, review cases, lookup options) and `UpdateChannel.label`, the crash dialog in `MainActivity`, the update dialog and the browser screen.

## Writing
Plain words for the person, not the system: "Checked daily", "Possible obituary", "Fill in details", not "MONITORING" or "Resolve". Say what will happen before it does (a confirmation names how many contacts a lookup will send), and what a setting costs. A possible death or arrest is never stated as fact. One ellipsis character (`…`), never three dots. A button and the label above it never repeat each other ("Site sign-ins" above "Sign out of sites").

## Taking things back
A verdict or a removal the user can reasonably regret is not final. "Not them" and Archive show a snackbar with **Undo**; "Show dismissed records again" on a contact's screen forgets every dismissed record (and offers Undo too); a confirmed death or custody has "This was a mistake"; "None of these is X" and "Sign out of sites" ask first. Delete everything has its own confirmation and can't be undone.

## Navigation
AzNavRail **11.57**. The active destination uses `primary`; focus and highlight use `onSurfaceVariant`, so the rail stays inside the monochrome palette.

## Launcher icon
The manifest uses `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`: adaptive icons with a white `@color/ic_launcher_background` plate, the black vector foreground, and a monochrome layer for themed icons. The black-and-white artwork is unchanged.

## Layout
The app draws edge to edge. Scaffold screens take `screenInsets()` (bars and cut-out); the contact list pads for the status bar and the cut-out itself. Content is capped at 640 dp wide and centred (`contentWidth()`), so a tablet or a phone on its side does not stretch lines across the screen. Dialog bodies scroll. The activity log panel is a third of the screen's height, within 140 to 280 dp.

## Accessibility
- Touch targets at least 48 dp: `AppAction` and `AppTextAction` enforce it with `minimumInteractiveComponentSize`; Material chips, icon buttons and list items already meet it. The AzNavRail components in Settings (the `AzToggle` and `AzRoller`) draw their own size, which this app can't change.
- A settings switch is one control: the row is the toggle, and TalkBack reads its title, caption and state together. A radio card (`Who is it?`) is `selectable`, so its selected state is announced. A status badge is announced once ("Status: Checked daily").
- Icons that carry meaning have a `contentDescription`; `null` only where a label beside the icon already says it.
- Long-press actions carry an `onLongClickLabel` for TalkBack.
