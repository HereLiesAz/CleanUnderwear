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

## Writing
Plain words for the person, not the system: "Checked daily", "Possible match", "Fill in details", not "MONITORING" or "Resolve". Say what will happen before it does (a confirmation names how many contacts a lookup will send), and what a setting costs. A possible death or arrest is never stated as fact.

## Navigation
AzNavRail **11.57**. The active destination uses `primary`; focus and highlight use `onSurfaceVariant`, so the rail stays inside the monochrome palette.

## Launcher icon
The manifest uses `@drawable/ic_launcher_foreground` directly for both `icon` and `roundIcon`. Adaptive icons need an opaque backdrop (transparent backgrounds render opaque on some launchers), so the foreground-only vector is used instead of the layered `mipmap/ic_launcher` assets. The black-and-white artwork is unchanged.

## Accessibility
- Touch targets at least 48 dp.
- Icons that carry meaning have a `contentDescription`; `null` only where a label beside the icon already says it.
- Long-press actions carry an `onLongClickLabel` for TalkBack.
