Always test your changes unless you are sure there is no impact.

There is an Android phone connected via USB.

You can install and run an APK by running:
adb install your-apk.apk ; adb shell monkey -p your.app.namespace -c android.intent.category.LAUNCHER 1

You can take a screenshot by running:
adb shell screencap -p /sdcard/screenshot.png ; adb pull /sdcard/screenshot.png

You can record a screencast by running:
adb shell screenrecord /sdcard/video.mp4 ; adb pull /sdcard/video.mp4

You can rotate the screen by running:
adb shell settings put system accelerometer_rotation 0 ; adb shell settings put system user_rotation 1
... with 0: Portrait, 1: Landscape, 2: Portrait (Inverted), 3: Landscape (Inverted)

You can type text into a focused text field by running:
adb shell input text "Hello"

You can simulate button presses by running:
adb shell input keyevent 4
... with 3: Home, 4: Back, 82: Menu

You can tap on a specific coordinate by running:
adb shell input tap 500 500

You can clear all app data by running:
adb shell pm clear your.app.namespace

You can grant runtime permissions by running:
adb shell pm grant your.app.namespace android.permission.CAMERA

You can revoke runtime permissions by running:
adb shell pm revoke your.app.namespace android.permission.CAMERA

You can clear the log buffer to start fresh by running:
adb logcat -c

You can dump the current logs to a file by running:
adb logcat -d > logs.txt

You can view the View Hierarchy by running:
adb shell uiautomator dump ; adb pull /sdcard/window_dump.xml

You can identify the Activity currently displayed on screen by running:
adb shell dumpsys activity activities | grep "mResumedActivity"

You can check for memory leaks using these commands smartly:
adb shell am send-trim-memory your.app.namespace HIDDEN ; adb shell dumpsys meminfo your.app.namespace
adb shell am dumpheap your.app.namespace /data/local/tmp/heap-dump.hprof ; adb pull /data/local/tmp/heap-dump.hprof
For instance you can rotate the screen 10 times and using dumpsys meminfo to detect increases after each rotation.
