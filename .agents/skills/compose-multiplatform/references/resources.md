# Compose Multiplatform — Resources

<setup>
## Setup

Add to `commonMain`:
```kotlin
commonMain.dependencies {
    implementation(compose.components.resources)
}
```

### Directory structure
```
composeApp/src/commonMain/composeResources/
├── drawable/          # Images (PNG, JPEG, WebP, BMP, XML vectors, SVG*)
├── font/              # Fonts (*.ttf, *.otf)
├── values/            # Strings (XML files with <string>, <string-array>, <plurals>)
└── files/             # Raw files (any hierarchy)
```
*SVG supported on all platforms except Android.

Resources can be in any module or source set (Kotlin 2.0.0+, Gradle 7.6+).
</setup>

<qualifiers>
## Qualifiers

Add qualifiers to directory names with hyphens. Priority: language → theme → density.

| Qualifier type | Examples | Notes |
|---------------|----------|-------|
| Language | `drawable-en`, `values-fr` | ISO 639-1 (2-letter) or ISO 639-2 (3-letter) |
| Region | `drawable-en-rUS`, `values-spa-rMX` | Lowercase `r` prefix + ISO 3166-1 alpha-2 |
| Theme | `drawable-dark`, `drawable-light` | Matches system theme |
| Density | `drawable-mdpi`, `drawable-xxhdpi` | ldpi(0.75x) mdpi(1x) hdpi(1.5x) xhdpi(2x) xxhdpi(3x) xxxhdpi(4x) |

Combined example: `drawable-en-rUS-mdpi-dark`

If a qualified resource isn't found, the default (unqualified) resource is used.
</qualifiers>

<usage>
## Accessing Resources

Build the project to generate the `Res` class. Import:
```kotlin
import project.composeapp.generated.resources.Res
import project.composeapp.generated.resources.my_image
```

### Images
```kotlin
// As Painter (most common — works for raster + XML vectors)
Image(painter = painterResource(Res.drawable.my_image), contentDescription = null)

// As ImageBitmap (raster only)
val bitmap: ImageBitmap = imageResource(Res.drawable.my_photo)

// As ImageVector (XML vector only)
val vector: ImageVector = vectorResource(Res.drawable.my_icon)
```

### Strings
XML file in `composeResources/values/strings.xml`:
```xml
<resources>
    <string name="app_name">My App</string>
    <string name="greeting">Hello, %1$s! You have %2$d messages.</string>
    <string-array name="colors">
        <item>Red</item>
        <item>Blue</item>
    </string-array>
    <plurals name="items">
        <item quantity="one">%1$d item</item>
        <item quantity="other">%1$d items</item>
    </plurals>
</resources>
```

```kotlin
// Simple string
Text(stringResource(Res.string.app_name))

// Template with args
Text(stringResource(Res.string.greeting, "Alice", 5))

// String array
val colors: List<String> = stringArrayResource(Res.array.colors)

// Plurals
Text(pluralStringResource(Res.plurals.items, count, count))
```

Non-composable access (suspend):
```kotlin
val name = getString(Res.string.app_name)
val arr = getStringArray(Res.array.colors)
val plural = getPluralString(Res.plurals.items, 1, 1)
```

### Fonts
```kotlin
val interFont = FontFamily(Font(Res.font.inter_regular, FontWeight.Normal))
```

### Raw files
```kotlin
val bytes: ByteArray = Res.readBytes("files/myDir/data.bin")  // suspend

// Convert to images
Image(bytes.decodeToImageBitmap(), null)     // raster
Image(bytes.decodeToImageVector(LocalDensity.current), null)  // XML vector
Image(bytes.decodeToSvgPainter(LocalDensity.current), null)   // SVG (not Android)
```

### Get URI for external libraries
```kotlin
val uri = Res.getUri("files/my_video.mp4")
// Pass uri to media player, kotlinx-io, WebView, etc.
```

### Resource maps (string ID access)
```kotlin
val img = Res.allDrawableResources["my_image"]
val str = Res.allStringResources["app_name"]
```
</usage>

<customization>
## Customization

```kotlin
compose.resources {
    publicResClass = false               // true to make Res public (default: internal)
    packageOfResClass = "com.example.res" // custom package
    generateResClass = auto              // auto | always
}
```

Custom resource directories:
```kotlin
compose.resources {
    customDirectory(
        sourceSetName = "jvmMain",
        directoryProvider = provider { layout.projectDirectory.dir("desktopResources") }
    )
}
```

Web resource path mapping:
```kotlin
configureWebResources {
    resourcePathMapping { path -> "https://cdn.example.com/res/$path" }
}
```
</customization>
