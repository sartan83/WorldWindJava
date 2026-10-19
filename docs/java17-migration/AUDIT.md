# Java 11 → 17 migration — Phase 1 audit

Baseline: branch `develop` @ `2543c234`, audited with OpenJDK 17.0.19 and Ant 1.10.12 on Ubuntu 22.04.

## 1. Java version touchpoints

The repository has **two independent Ant builds**. Both pin Java 11 and both had to move.

| File | Setting | Value before | Notes |
|---|---|---|---|
| `nbproject/project.properties` | `javac.source` / `javac.target` | `11` / `11` | NetBeans build (`build.xml` → `nbproject/build-impl.xml`). |
| `nbproject/project.properties` | `javac.compilerargs` | empty | `--release` cannot go here: `build-impl.xml` always passes `-source`/`-target`, and javac rejects `--release` together with them. |
| `nbproject/project.properties` | `javadoc.*` | no version pins | Nothing to change. |
| `nbproject/project.properties` | `run.jvmargs` | empty | Used by NetBeans Run/Debug. |
| `nbproject/build-impl.xml` | `default.javac.source/target` | `1.8` fallback | Generated file. Only used when `javac.source` is unset, so it is not edited. It has no `javac.release` support (stylesheet 1.108). |
| `nbproject/project.xml` | javac profile | none | No `<javac-profile>` / `<explicit-platform>`, so nothing to change. |
| `nbproject/configs/*.properties` | `main.class` per demo | no JVM args | 45 run configs, all of which use `run.jvmargs` from `project.properties`. |
| `release-build.properties` | `worldwind.jdk` | `11` | Main build used for releases, CI and the blueprint (`ant -f release-build.xml`). |
| `release-build.macros.xml` | `compileJava` → `<javac source=@{jdk} target=@{jdk}>` | from `worldwind.jdk` | Without `--release`, javac uses `-source 11` against the JDK 17 class library and warns `system modules path not set`. |
| `release-build.xml` | javadoc `<link>` | Java 8 API, JOGL `v2.4.0-rc-20200307` | The JOGL link returns 404 (`Error fetching URL ... package-list`). |
| `.travis.yml` | `jdk:` | `openjdk11` | Travis CI. The fork has no `.github/workflows`. |
| `run-demo.bash` / `run-demo.bat` | `java -classpath ...` | no module flags | Release-archive launchers. |
| `WorldWind.iml` | `inheritedJdk` | no level pin | IntelliJ module. There is no `.idea/` directory in the repo. |

A search for other pins (`java.version`, `--release`, `source="1.x"`, `1.11`, `languageLevel`) found nothing else in the source tree or build files.

## 2. Dependency audit

| Jar | Version found | Java 17 status | Action |
|---|---|---|---|
| `jogl-all.jar`, `gluegen-rt.jar` (+ natives for linux-amd64, macosx-universal, windows-amd64) | **2.5.0** (`2.5-b1533-20230818` / `2.5-b967-20230818`, built with JDK 17) | Supported. Manifest declares `Add-Opens: java.desktop/sun.awt java.desktop/sun.java2d`, but the JVM only honours that attribute for `java -jar`. | No jar upgrade. Fix `lib-external/jogl-gluegen/get-jogl.sh`, whose `v2.5.0-rc-20230523` URLs return 404. Add `--add-opens` for classpath launches (see §4). |
| `gdal.jar` | GDAL JNI bindings, classes from 2011–2013 (Java 7 bytecode) | `jdeps --jdk-internals`: none. Plain JNI, so it loads on 17. | None. Native libs live in `lib-external/gdal/`. |
| `junit-4.5.jar` | 4.5 (2008) | Runs on 17 (435 tests, see below). | Optional: upgrade to 4.13.2 + hamcrest-core 1.3 for CVE-2020-15250 (TemporaryFolder). Not done here because it changes the test runtime classpath. |
| `vpf-symbols.jar` | resources only | n/a | None. |
| `lib-external/batik/*` | Batik 1.7 (2008), Xalan 2.6, Xerces 2.5 | Only used by the optional `assembleMilStd2525` target, to rasterize SVGs at build time. Not on the runtime classpath. | None for 17. Upgrading to Batik 1.17 is a separate security item. |
| `lib-external/webview/*` | native C/ObjC/C++ sources | Not built by Ant and not affected by the JDK level. | None. |

`jdeps --multi-release 17 --jdk-internals` reported **no JDK-internal API use** in any of `jogl-all.jar`, `gluegen-rt.jar`, `gdal.jar`, `junit-4.5.jar`, `vpf-symbols.jar`, or in the 3,230 compiled WorldWind classes.

## 3. Source audit (APIs removed or encapsulated by 17)

- `javac --release 17 -Xlint:removal,deprecation` over all of `src/`: **0 warnings**.
- `sun.misc.*`, `jdk.internal.*`, `com.sun.*` imports: none. `sun.awt.noerasebackground` is only a system-property string in 4 example apps, which is fine.
- `SecurityManager` / `AccessController`, `java.applet` / `JApplet`, `javax.xml.bind` (JAXB), sealed classes: none.
- `Object.finalize()` overrides: none (one is commented out in `BufferedImageRaster`). `System.runFinalization()` is still called in `CachedDataRaster`. It is valid on 17 and only deprecated for removal from JDK 18, so it is left as a forward-compatibility note.
- `gov.nasa.worldwind.util.BrowserOpener`:
  - On macOS it reflects into `com.apple.eio.FileManager`, which was removed in JDK 9, so it always throws on 11 and 17.
  - On Windows it uses `Runtime.exec(String)`, which is deprecated from JDK 18 and splits URLs on spaces.
  - **Fixed in Phase 3.**

## 4. Runtime: strong encapsulation (JEP 403)

`ApplicationTemplate` was run on JDK 17 / Xvfb with `-Dsun.reflect.debugModuleAccessChecks=access`. The globe renders, but JOGL silently fails two reflective accesses and falls back:

```
InaccessibleObjectException: Unable to make public static final void sun.awt.SunToolkit.awtLock() accessible:
    module java.desktop does not "exports sun.awt" to unnamed module   (jogamp.nativewindow.jawt.JAWTUtil)
InaccessibleObjectException: Unable to make public native long sun.awt.X11GraphicsDevice.getDisplay() accessible ...
```

With `--add-opens java.desktop/sun.awt=ALL-UNNAMED --add-opens java.desktop/sun.java2d=ALL-UNNAMED`, which is the same set JOGL's manifest asks for, both errors disappear. The only remaining entry is a JOGL-internal `IllegalAccessException` on its own private class, which is not a JDK encapsulation issue. The flags are therefore *recommended, not mandatory*: they let JOGL use the AWT toolkit lock on X11 instead of its fallback. Phase 3 adds them to `run.jvmargs` and the run scripts.

## 5. Baseline results (JDK 17 runtime, `-source/-target 11`)

| Check | Result |
|---|---|
| `ant -f release-build.xml clean unitTest` | BUILD SUCCESSFUL: 49 suites, **435 tests, 0 failures, 0 errors, 7 skipped** |
| `ant -f release-build.xml assembleRelease` | OK (`worldwind.jar`, `worldwindx.jar`) |
| `ant -f build.xml clean jar` (NetBeans) | OK (`dist/WorldWindJava.jar`) |
| `ant -f release-build.xml assembleJavadoc` | Builds, but with doclint errors on legacy HTML (non-fatal) and a JOGL link 404 |
| `ApplicationTemplate` demo on Xvfb | Renders the globe, with the silent JOGL fallbacks in §4 |

## 6. Prior art

The remote branch `devin/1790697735-java21-migration` is an unmerged earlier migration to **Java 21** that overlaps with this work. This branch targets **17** as requested. Only one of them should land.
