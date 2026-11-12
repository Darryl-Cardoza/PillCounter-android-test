// Shared Kover configuration for CI (can be applied from root build.gradle.kts)
// usage: apply(from = "$rootDir/tool/kover-config.gradle.kts")

import org.jetbrains.kotlinx.kover.gradle.plugin.dsl.AggregationType

plugins.apply("org.jetbrains.kotlinx.kover")

kover {
    // Include main sources, exclude generated and android test artifacts
    filters {
        excludes {
            // generated / android
            classes(
                "*BuildConfig",
                "*.databinding.*",
                "hilt_aggregated_deps.*",
                "dagger.hilt.internal.*",
                "*.R", "*.R$*",
                "*.Manifest", "*.Manifest$*",
                "*.Hilt_*",
                "*.di.*",
            )
            packages(
                "androidx.*",
                "com.google.*"
            )
        }
    }

    // Generate HTML + XML so Sonar can read XML and humans can read HTML
    reports {
        total {
            html {
                setReportFile(layout.buildDirectory.file("reports/kover/html/index.html"))
            }
            xml {
                setReportFile(layout.buildDirectory.file("reports/kover/xml/report.xml"))
            }
        }
    }
}

// Ensure XML + HTML reports are produced on CI
tasks.matching { it.name.equals("koverHtmlReport", true) || it.name.equals("koverXmlReport", true) }
    .configureEach { this.enabled = true }

// Convenience aggregate task
tasks.register("koverAllReports") {
    dependsOn("koverHtmlReport", "koverXmlReport")
}
