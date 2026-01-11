package com.kettlebell

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.library.Architectures.layeredArchitecture

@AnalyzeClasses(packages = ["com.kettlebell"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {
    @ArchTest
    val layerCheck: ArchRule =
        layeredArchitecture()
            .consideringOnlyDependenciesInAnyPackage("com.kettlebell..")
            .layer("Launcher").definedBy("com.kettlebell")
            .layer("DI").definedBy("..di..")
            .layer("Bot").definedBy("..bot..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..")
            .layer("Model").definedBy("..model..")
            .layer("Config").definedBy("..config..")
            .whereLayer("Launcher").mayNotBeAccessedByAnyLayer()
            .whereLayer("DI").mayOnlyBeAccessedByLayers("Launcher")
            .whereLayer("Bot").mayOnlyBeAccessedByLayers("DI", "Launcher")
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Bot", "DI")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "DI")
            .whereLayer("Model").mayOnlyBeAccessedByLayers("Bot", "Service", "Repository", "Config", "DI", "Launcher")
            .whereLayer("Config").mayOnlyBeAccessedByLayers("Bot", "Service", "Repository", "DI", "Launcher")

    @ArchTest
    val noSystemOut: ArchRule = com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
}
