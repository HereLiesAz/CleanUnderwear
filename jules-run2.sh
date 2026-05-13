export BUILD_NUMBER=$(git rev-list --count HEAD)

# Extract version info from properties file

MAJOR=$(grep 'versionMajor=' version.properties | cut -d'=' -f2 | tr -d '\r ')
MINOR=$(grep 'versionMinor=' version.properties | cut -d'=' -f2 | tr -d '\r ')

# Calculate Patch version programmatically based on commits since Minor update
MINOR_COMMIT=$(git blame -L '/versionMinor=/',+1 version.properties | awk '{print $1}' | tr -d '^')
if [ -n "$MINOR_COMMIT" ]; then
  PATCH=$(git rev-list --count $MINOR_COMMIT..HEAD)
else

  PATCH=$(grep 'versionPatch=' version.properties | cut -d'=' -f2 | tr -d '\r ')
fi

VERSION_NAME="$MAJOR.$MINOR.$PATCH.$BUILD_NUMBER"

TAG_NAME="latest-debug-v${MAJOR}.${MINOR}"

# Dynamic App Name from settings.gradle
if [ -f "settings.gradle.kts" ]; then
   APP_NAME=$(grep 'rootProject.name' settings.gradle.kts | sed -E "s/.*=.*[\"'](.*)[\"']/\1/")
elif [ -f "settings.gradle" ]; then
   APP_NAME=$(grep 'rootProject.name' settings.gradle | sed -E "s/.*=.*[\"'](.*)[\"']/\1/")
else
   APP_NAME="App"
fi

# Locate built APK
APK_ORIGINAL=$(find app/build/outputs/apk/debug -name "*.apk" | head -n 1)

if [ -z "$APK_ORIGINAL" ]; then
   echo "Error: No APK found to release!"
fi

TARGET_NAME="${APP_NAME}-${VERSION_NAME}-debug.apk"

# Rename for release
mv "$APK_ORIGINAL" "$TARGET_NAME"

echo "TAG_NAME=$TAG_NAME" >> GITHUB_ENV
echo "APK_FILE=$TARGET_NAME" >> GITHUB_ENV
