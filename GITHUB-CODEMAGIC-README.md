# HCam+ — GitHub/Codemagic Ready

Upload the CONTENTS of this folder to the root of a GitHub repository.

Expected root:
- app/
- build.gradle.kts
- settings.gradle.kts
- gradle.properties
- gradlew (if present)
- gradlew.bat (if present)
- gradle/
- codemagic.yaml

Then connect the repository to Codemagic and run the `hcamplus-android` workflow.

Note: release signing may require configuring a keystore in Codemagic. For a quick test build, you can temporarily change the Gradle task to `assembleDebug` in codemagic.yaml.
