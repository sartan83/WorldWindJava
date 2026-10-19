# Java 17 migration — Phase 4 validation

Environment: OpenJDK 17.0.19 (`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`), Ant 1.10.12, Ubuntu 22.04, and Xvfb with Mesa software OpenGL.

| Check | Command | Result |
|---|---|---|
| Release build (default target: assemble + test) | `xvfb-run -a ant -f release-build.xml clean build` | **BUILD SUCCESSFUL**. javac runs with `--release 17` and the `system modules path not set` warning is gone. |
| JUnit (`test/`, release build) | same | 49 suites, **435 tests, 0 failures, 0 errors, 7 skipped**. Identical to the JDK 11-target baseline. |
| NetBeans build | `xvfb-run -a ant -f build.xml clean jar javadoc test` | **BUILD SUCCESSFUL**. 48 suites, 434 tests, 0 failures, 0 errors, 6 skipped. NetBeans only selects `*Test.java`, so `HighResolutionTerrainTest2` is skipped. |
| `testFunctional/` | `javac --release 17 -cp dist/WorldWindJava.jar:...` (10 sources) | Compiles, no errors. These are manual GUI harnesses (`main` methods), not JUnit suites. |
| Bytecode level | `javap -v` on `worldwind.jar`, `worldwindx.jar`, `dist/WorldWindJava.jar` | `major version: 61` (Java 17) |
| Artifacts | | `dist/WorldWindJava.jar` (23.8 MB), `worldwind.jar`, `worldwindx.jar`, `build/worldwind.zip` (SDK), `build/doc/worldwind-javadoc.zip`, `dist/javadoc/` |
| Javadoc (release build) | `ant -f release-build.xml assembleJavadoc` | OK. The Java 17 API and JOGL 2.5.0 links resolve; the old JOGL 2.4.0-rc link was 404. |
| Javadoc (NetBeans) | `ant -f build.xml javadoc` | Failed on JDK 17 doclint (22 legacy-HTML errors). Fixed with `javadoc.additionalparam=-Xdoclint:none`, and now OK. |
| jlink, NetBeans `-do-deploy` | | **Not applicable.** It only runs for named modules (`named.module.internal`), and the project has no `module-info.java`. This is unchanged from Java 11. |
| jlink, custom runtime | `jdeps --print-module-deps` → `jlink --add-modules java.base,java.desktop,java.logging` | 73 MB runtime image. `ApplicationTemplate` runs from it and renders the globe. |
| Demo smoke test | `./run-demo.bash` (ApplicationTemplate) on Xvfb, plus `-Dsun.reflect.debugModuleAccessChecks=access` | Globe, layers panel, world-map inset, compass and view controls all render. With the new `--add-opens` flags there are **0** `InaccessibleObjectException`s; without them there are 2 (JOGL `JAWTUtil` → `sun.awt.SunToolkit.awtLock`, `X11GraphicsDevice.getDisplay`). |
| JOGL jars | sha256 of vendored jars vs `https://jogamp.org/deployment/v2.5.0/jar/*` | All 9 jars are byte-identical to the JOGL/GlueGen 2.5.0 release. |

## Not covered here
- Real GPU drivers on Windows and macOS. Linux was tested with Mesa llvmpipe under Xvfb.
- `assembleMilStd2525`, the optional Batik SVG rasterization target. It is not part of the default build.
- JUnit was not upgraded to 4.13.2. That remains an optional security follow-up.
