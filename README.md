# simple-os-apps

Modules: `home` (ambient home + tap overlay), `provision` (first-boot setup).

## Build
Open this folder in Android Studio (JDK 17), then Build > Build APK(s), or:
    gradle :home:assembleRelease :provision:assembleRelease
Outputs: <module>/build/outputs/apk/release/<module>-release.apk
Both are signed with the debug key. Keep that keystore: later APK updates in the image must use the same one.

## Put into the image (mounted root = system/)
| File | Destination |
|---|---|
| provision-release.apk | system/priv-app/Provision/Provision.apk |
| home-release.apk | system/app/Home/Home.apk |
| image-files/privapp-permissions-simple.xml | system/etc/permissions/ |
| image-files/default-permissions-simple.xml | system/etc/default-permissions/ |

Dirs 0755, files 0644, owner root:root, SELinux label u:object_r:system_file:s0.
Remove Launcher3/Quickstep so Home is the only HOME app.

## Settings keys Home reads (written by the Settings app)
simple_lat, simple_lon (decimal degrees), simple_unit ("C" or "F")
Test before Settings exists: adb shell settings put system simple_lat 51.5 (needs adb root/shell WRITE_SETTINGS)

## Not included yet
- Lock screen disable (needs a framework-res overlay: config_disableLockscreenByDefault=true)
- Launcher (Home's Apps button fires action com.simple.action.APPS)
