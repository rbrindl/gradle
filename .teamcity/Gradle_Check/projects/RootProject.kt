package projects

import configurations.StagePasses
import jetbrains.buildServer.configs.kotlin.v2018_1.AbsoluteId
import jetbrains.buildServer.configs.kotlin.v2018_1.Project
import jetbrains.buildServer.configs.kotlin.v2018_1.projectFeatures.VersionedSettings
import jetbrains.buildServer.configs.kotlin.v2018_1.projectFeatures.versionedSettings
import model.CIBuildModel
import model.Stage

class RootProject(model: CIBuildModel) : Project({
    uuid = model.projectPrefix.removeSuffix("_")
    id = AbsoluteId(uuid)
    parentId = AbsoluteId("Gradle")
    name = model.rootProjectName

    features {
        versionedSettings {
            id = "PROJECT_EXT_3"
            mode = VersionedSettings.Mode.ENABLED
            buildSettingsMode = VersionedSettings.BuildSettingsMode.PREFER_CURRENT_SETTINGS
            rootExtId = "Gradle_Branches_VersionedSettings"
            showChanges = false
            settingsFormat = VersionedSettings.Format.KOTLIN
            param("credentialsStorageType", "credentialsJSON")
        }
    }

    var prevStage: Stage? = null
    var deferredAlreadyDeclared = false
    model.stages.forEach { stage ->
        val containsDeferredTests = !stage.omitsSlowProjects && !deferredAlreadyDeclared
        deferredAlreadyDeclared = deferredAlreadyDeclared || containsDeferredTests
        buildType(StagePasses(model, stage,  prevStage, containsDeferredTests, uuid))
        subProject(StageProject(model, stage, containsDeferredTests, uuid))
        prevStage = stage
    }

    if (model.stages.map { stage -> stage.performanceTests }.flatten().isNotEmpty()) {
        subProject(WorkersProject(model))
    }

    buildTypesOrder = buildTypes
    subProjectsOrder = subProjects
})
