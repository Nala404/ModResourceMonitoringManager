# Mod Resource Manager

This mod is developed by AI and has not been verified for production use.

A Fabric client-side Minecraft mod that shows mod and process resource usage, including CPU, memory allocation, disk size, and Windows GPU usage in an inventory-like UI.

## Target Versions

- Minecraft `26.1.1`
- Minecraft `26.2+`

## Build

JDK 26 is required.

Place the target Mojang-named client jar and version JSON in:

```text
Minecraft/<minecraft_version>-<loader_version>/<minecraft_version>-<loader_version>.jar
Minecraft/<minecraft_version>-<loader_version>/<minecraft_version>-<loader_version>.json
```

Then run:

```powershell
.\build-all.ps1
```

Artifacts are written to `build/libs/`.

## Usage

- Press `\` to open or close the manager.
- `/modresources` also opens it.
- The UI provides a mod list and performance page with sortable columns.
- Config is stored in `config/modresourcemanager.json`.

## License

This project is MIT licensed. Third-party notices are in `THIRD_PARTY_LICENSES.md`.
