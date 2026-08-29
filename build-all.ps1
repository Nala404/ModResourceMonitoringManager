$ErrorActionPreference = 'Stop'

$gradle = '.\gradlew.bat'

$matrix = @(
    @{
        minecraft = '26.1.1'
        loader    = '0.19.3'
        fabric    = '0.155.2+26.1.2'
        commandApi = '3.0.5+e2bdee784c'
        keyMappingApi = '2.0.4+e2bdee784c'
        lifecycleApi = '4.1.1+df84eb3d4c'
    },
    @{
        minecraft = '26.2'
        loader    = '0.19.3'
        fabric    = '0.158.0+26.2'
        commandApi = '3.1.0+00cb03469e'
        keyMappingApi = '2.0.5+e2bdee789e'
        lifecycleApi = '4.1.3+4575b05f9e'
    }
)

foreach ($entry in $matrix) {
    Write-Host "Building for Minecraft $($entry.minecraft)"

    # Gradle 9.7 truncates dotted -P values to their major segment.
    # Environment variables keep the full version string intact.
    $env:ORG_GRADLE_PROJECT_minecraft_version = $entry.minecraft
    $env:ORG_GRADLE_PROJECT_loader_version = $entry.loader
    $env:ORG_GRADLE_PROJECT_fabric_version = $entry.fabric
    $env:ORG_GRADLE_PROJECT_fabric_command_api_version = $entry.commandApi
    $env:ORG_GRADLE_PROJECT_fabric_key_mapping_api_version = $entry.keyMappingApi
    $env:ORG_GRADLE_PROJECT_fabric_lifecycle_events_api_version = $entry.lifecycleApi

    & $gradle assemble --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed for Minecraft $($entry.minecraft)"
    }

    Copy-Item 'build/libs/mrm-fabric-0.0.1-alpha.jar' "build/libs/mrm-fabric-0.0.1-alpha-$($entry.minecraft).jar" -Force
    Copy-Item 'build/libs/mrm-fabric-0.0.1-alpha-sources.jar' "build/libs/mrm-fabric-0.0.1-alpha-$($entry.minecraft)-sources.jar" -Force
}

Write-Host 'All builds completed.'
