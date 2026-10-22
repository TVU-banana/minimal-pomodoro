# Third-party notices

Clock source code is licensed under GPL-3.0-or-later. Third-party assets retain their own licenses.

| Asset | Upstream | License in the application |
| --- | --- | --- |
| DSEG7 Classic Regular | [DSEG](https://github.com/keshikan/DSEG) | [SIL OFL 1.1](app/src/main/assets/licenses/DSEG-LICENSE.txt) |
| Source Han Serif CN ExtraLight / Medium | [Adobe Source Han Serif](https://github.com/adobe-fonts/source-han-serif) | [SIL OFL 1.1](app/src/main/assets/licenses/SourceHanSerif-LICENSE.txt) |

AndroidX, Kotlin coroutines, JUnit, Espresso and benchmark dependencies are declared in
`gradle/libs.versions.toml`; resolved versions are recorded in Gradle lockfiles. Consult their
upstream notices when redistributing compiled applications.

The optional Xiaomi Wear SDK is proprietary, excluded from Git and absent from default builds.
Download it only from the official source and review its distribution terms before publishing a
build that enables `-PbandSdk=true`. See [band control](docs/band-control.md).
