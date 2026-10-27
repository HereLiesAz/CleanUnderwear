# Design Guide

Monochrome, quiet, legible. The app delivers news nobody wants; the interface should not compete with it.

## Theme (`ui/Theme.kt`)
- **Colour**: a grey ramp with one accent: white-ish (`Bone`) on near-black in dark mode, near-black (`Ink`) on white in light mode. The one hue is **Alert** red, kept for what needs the user (a possible match) and for errors. Status is told apart by label, icon and fill, never by colour alone. No dynamic colour.
- **Type** (`AppTypography`): titleLarge for screen titles, titleMedium for card titles, labelLarge for section headers, labelMedium for field labels, bodyLarge/bodyMedium for content, bodySmall for captions.
- **Spacing** (`Spacing`): xs 4, sm 8, md 16, lg 24, xl 32 dp. Screens and cards pad `md`; items in a column sit `md` apart.
- The window background is dark from launch (`res/values/themes.xml`), so there is no white flash. The app draws edge to edge.

## Components (`ui/Components.kt`)
Use these rather than building a screen's own:
- `AppTopBar`, `AppSection`, `AppCaption`, `AppCard` (with `emphasis` for what needs the user)
- `AppAction` (the main button), `AppTextAction` (secondary), `AppDialog`
- `LoadingCaption`, `StatusBadge` / `statusSpec` (the one place a status gets its label and icon), `DetailRow`

## Busy screen (`ui/OperationOverlay.kt`)
While an operation runs, everything behind it, rail included, is darkened and blurred (blur needs Android 12 and a device that allows it; without it the screen is darkened further). The loader and what is happening sit in the middle, **Cancel** at the bottom; Cancel keeps what was finished before it. It is always drawn dark, in either theme.
- An operation that keeps going on its own says so, and **Keep using the app** (or Back) puts it aside: a bar at the bottom shows it is still running, with Cancel, and tapping it brings the busy screen back. Reading contacts keeps going while the app is in use; the records check runs in WorkManager and keeps going after the user leaves the app.
- Under the loader, what has turned up so far, a line each: the records check's tally (checked, each possible match by name and kind, weak matches, contacts whose sites didn't answer); Fill in details' tally and its last few contacts ("born ~1970, Gretna, LA", "nobody found"); for reading contacts, how many were read, merged and made ready. The bar shows the first line.
- An operation that needs the user (a lookup in the browser) has no "keep using" option.
- Delete everything can't be cancelled: half a deletion is worse than none, and it takes a moment.
- In the browser, an automated page is darkened and blurred the same way, with Cancel at the bottom; it lifts when the page needs the user.

## Writing
Plain words for the person, not the system: "Checked daily", "Possible obituary", "Fill in details", not "MONITORING" or "Resolve". Say what will happen before it does (a confirmation names how many contacts a lookup will send), and what a setting costs. A possible death or arrest is never stated as fact.

## Navigation
AzNavRail **11.57**. The active destination uses `primary`; focus and highlight use `onSurfaceVariant`, so the rail stays inside the monochrome palette.

## Launcher icon
The manifest uses `@drawable/ic_launcher_foreground` directly for both `icon` and `roundIcon`. Adaptive icons need an opaque backdrop (transparent backgrounds render opaque on some launchers), so the foreground-only vector is used instead of the layered `mipmap/ic_launcher` assets. The black-and-white artwork is unchanged.

## Accessibility
- Touch targets at least 48 dp.
- Icons that carry meaning have a `contentDescription`; `null` only where a label beside the icon already says it.
- Long-press actions carry an `onLongClickLabel` for TalkBack.
