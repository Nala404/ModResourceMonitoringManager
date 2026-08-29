# Mod Resource Manager

English version: [README_EN.md](README_EN.md)

该mod为Ai开发，未经验证是否可用。

适用于 Java Minecraft 的 Fabric 客户端 Mod，用来在一个接近原版背包界面的窗口中查看 Mod 与游戏进程的 CPU、内存分配速率、Jar/目录占用和 Windows GPU 使用情况。

## 目标版本

- Minecraft `26.1.1`
- Minecraft `26.2+`（构建脚本默认构建 `26.2`）

## 构建

需要 JDK 26，因为 Minecraft `26.1.1` 和 `26.2` 都要求 Java 25，同时本工程使用 JDK 26 编译。

构建前请把目标版本的 Mojang 命名客户端 Jar 和版本 JSON 放在：

```text
Minecraft/<minecraft_version>-<loader_version>/<minecraft_version>-<loader_version>.jar
Minecraft/<minecraft_version>-<loader_version>/<minecraft_version>-<loader_version>.json
```

项目已包含 Gradle Wrapper，可直接构建：

```powershell
$env:ORG_GRADLE_PROJECT_minecraft_version='26.1.1'
$env:ORG_GRADLE_PROJECT_loader_version='0.19.3'
$env:ORG_GRADLE_PROJECT_fabric_version='0.155.2+26.1.2'
$env:ORG_GRADLE_PROJECT_fabric_command_api_version='3.0.5+e2bdee784c'
$env:ORG_GRADLE_PROJECT_fabric_key_mapping_api_version='2.0.4+e2bdee784c'
$env:ORG_GRADLE_PROJECT_fabric_lifecycle_events_api_version='4.1.1+df84eb3d4c'
.\gradlew.bat assemble
```

或构建默认版本矩阵：

```powershell
.\build-all.ps1
```

输出文件位于 `build/libs/`。

`build-all.ps1` 会额外保留版本后缀产物，例如 `mrm-fabric-0.0.1-alpha-26.1.1.jar` 和 `mrm-fabric-0.0.1-alpha-26.2.jar`。

## 版本参数

不要使用 `-Pminecraft_version=26.1.1` 形式的命令行覆盖：当前 Gradle 9.7 会把带点的值截断为 `26`。版本矩阵脚本已改用 `ORG_GRADLE_PROJECT_*` 环境变量。

`gradle.properties` 中的默认值对应 `26.1.1`。构建 `26.2` 时，直接运行 `.\build-all.ps1`，它会设置正确的 `minecraft_version`、`loader_version`、`fabric_version` 以及 Fabric API 模块版本。

## 使用

- 默认按 `\` 打开或关闭界面。
- 输入 `/modresources` 也可以打开界面。
- 界面包含 Mod 列表和性能页；点击表头可切换升序/降序，排序会保存到配置文件。
- 配置文件位于 `config/modresourcemanager.json`。

## 指标说明

- 磁盘占用按 Fabric Mod 的根路径递归计算，Jar 直接取文件大小。
- 如果 Fabric Loader 未暴露某些 `mods/` 目录内的 Jar，会回退扫描 `mods` 目录，避免遗漏已安装 Mod。
- 每个 Mod 的 CPU 和分配速率是通过线程 CPU 时间、线程分配字节数和栈帧归属估算的，界面中应视为估算值。
- GPU 仅提供整个 Minecraft 进程的 GPU 使用情况；NVIDIA 优先使用 `nvidia-smi`，其他 Windows GPU 回退到 PDH 计数器。
- JDK 不允许读取线程分配字节时，内存分配列会显示 `N/A`，其余功能不受影响。
