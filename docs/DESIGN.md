# Design Guide

CleanUnderwear is designed with a focus on simplicity, consistency, and a modern aesthetic, adhering strongly to Google's Material Design 3 guidelines.

## UI Framework: Jetpack Compose

We use Jetpack Compose exclusively for building the user interface. This brings several design advantages:

*   **Declarative UI**: UI components are described as functions of their state, making it easier to visualize and reason about the interface.
*   **Reusability**: We heavily rely on building small, reusable, stateless components that can be composed together to form complex screens.
*   **Theming Integration**: Compose integrates seamlessly with our Material 3 theme.

## Material Design 3 (Material You)

The application utilizes the `androidx.compose.material3` library.

### Theming Strategy

Our theming is centralized in the `Theme.kt` file (typically found under `ui/theme`).

1.  **Color Palette**: We define light and dark color schemes using `lightColorScheme()` and `darkColorScheme()`. We aim to support dynamic color on Android 12+ devices, allowing the app's colors to adapt to the user's wallpaper.
2.  **Typography**: We establish a consistent typographical hierarchy (Headings, Body, Labels) using `Typography()`. We utilize standard Material 3 text styles, customized where necessary to fit the CleanUnderwear brand identity.
3.  **Shapes**: We define the corner radii for different component sizes (Small, Medium, Large) using `Shapes()`, ensuring consistent rounding across buttons, cards, and dialogs.

### Core Components

*   **Scaffold**: We use `Scaffold` as the base structural layout for most screens, providing standard slots for `TopAppBar`, `BottomAppBar`, `FloatingActionButton`, and the main content area.
*   **Top App Bar**: Provides context and navigation for the current screen.
*   **Navigation**: We utilize standard Material navigation components like `NavigationBar` (for bottom navigation) or `NavigationRail` (for larger screens) to allow users to switch between primary destinations.
*   **Cards and Lists**: Data is primarily presented using `Card` components within `LazyColumn` or `LazyRow` lists for efficient scrolling performance.

## Component Design Principles

When creating new UI components in Compose, we follow these guidelines:

1.  **Stateless by Default**: Components should ideally be stateless. They should receive their data (state) and their event handlers (callbacks) as parameters.
2.  **State Hoisting**: If a component needs to manage state, that state should be hoisted (moved up) to the nearest common ancestor, usually the screen-level composable or the ViewModel.
3.  **Previewability**: Every reusable component should have at least one `@Preview` function to demonstrate its visual state without running the app. We use `@Preview(showBackground = true)` and often provide previews for both light and dark modes.

## Media and Assets

*   **Icons**: We use the standard Material Icons Extended library (`androidx.compose.material.icons.extended`) for consistent iconography.
*   **Images**: For remote images, we use [Coil](https://coil-kt.github.io/coil/), which integrates smoothly with Compose via the `AsyncImage` composable.
*   **Vector Drawables**: All local graphical assets (like the app launcher icon) are stored as Vector Drawables to ensure they scale perfectly across different screen densities.

## AzNavRail selection colors

The navigation rail uses AzNavRail **11.57** and distinguishes navigation state from touch emphasis:

- **Active destination**: `MaterialTheme.colorScheme.primary` — NeonCyan (`#00E5FF`) in dark mode, CircuitBlue (`#00838F`) in light mode.
- **Highlighted/focused item**: `MaterialTheme.colorScheme.tertiary` — IntelligenceGold (`#FFB300`) in both modes. Used for the library's `focusColor` (pressed or last-tapped action) and `secondaryColor` (app-driven highlight).

This retains the library's destination/focus distinction rather than coloring all selected-looking items identically.

## Launcher icon

The manifest uses `@drawable/ic_launcher_foreground` directly for both `icon` and `roundIcon`. Android adaptive icons require an opaque backdrop (transparent backgrounds render as opaque on some launchers), so the foreground-only vector is deliberately used instead of the layered `mipmap/ic_launcher` assets. The existing black-and-white icon artwork is unchanged.

## Accessibility

Design isn't just about how it looks, but how it works for everyone.
*   We ensure all interactive elements have adequate touch target sizes (minimum 48x48dp).
*   We provide meaningful `contentDescription` properties for all `Icon` and `Image` composables that convey meaning (using `null` only for purely decorative graphics).
*   We rely on Compose's built-in semantics to ensure compatibility with screen readers like TalkBack.
