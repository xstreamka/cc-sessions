import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import javax.inject.Inject

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Платформа собрана с JVM default-методами: без этого Kotlin генерирует в наших классах заглушки
        // для default-методов интерфейсов платформы, и Plugin Verifier считает их переопределением
        // устаревшего/экспериментального API (ToolWindowFactory.isApplicable, getAnchor и т. п.)
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        bundledPlugin("org.jetbrains.plugins.terminal")
        bundledPlugin("com.intellij.modules.jcef")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    buildSearchableOptions = false

    // Публикация в JetBrains Marketplace: ./gradlew publishPlugin (первую версию загружают вручную на сайте).
    // Секреты в репозиторий не коммитятся. Токен — из переменной PUBLISH_TOKEN, а если её нет —
    // из локального файла ~/.config/cc-sessions/publish-token (только токен, права 600).
    publishing {
        val tokenFile = layout.file(provider { File(System.getProperty("user.home"), ".config/cc-sessions/publish-token") })
        token = providers.environmentVariable("PUBLISH_TOKEN")
            .orElse(providers.fileContents(tokenFile).asText.map { it.trim() })
            .filter { it.isNotEmpty() }
    }
    // Подпись автора необязательна (Marketplace подписывает сам); если переменные заданы — signPlugin подпишет сборку.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    // Проверка совместимости против локально установленных IDE:
    // ./gradlew verifyPlugin -PverifyIdes=/путь/к/phpstorm,/путь/к/goland
    pluginVerification {
        ides {
            providers.gradleProperty("verifyIdes").orNull
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.forEach { local(file(it)) }
        }
    }
}

// ---------- Go-хук: собираем бинарники под все платформы и кладём в ресурсы плагина ----------

abstract class BuildHookTask @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val targets = listOf(
            "linux" to "amd64", "linux" to "arm64",
            "darwin" to "amd64", "darwin" to "arm64",
            "windows" to "amd64",
        )
        targets.forEach { (os, arch) ->
            val exe = if (os == "windows") "cc-sessions-hook.exe" else "cc-sessions-hook"
            val target = outputDir.get().file("hook-bin/$os-$arch/$exe").asFile
            target.parentFile.mkdirs()
            execOps.exec {
                workingDir = sourceDir.get().asFile
                environment("GOOS", os)
                environment("GOARCH", arch)
                environment("CGO_ENABLED", "0")
                commandLine("go", "build", "-trimpath", "-ldflags=-s -w", "-o", target.absolutePath, ".")
            }
        }
    }
}

val hookOutDir = layout.buildDirectory.dir("generated/hook")

val buildHook = tasks.register<BuildHookTask>("buildHook") {
    group = "build"
    description = "Собирает cc-sessions-hook (Go) под Linux/macOS/Windows"
    sourceDir.set(layout.projectDirectory.dir("hook"))
    outputDir.set(hookOutDir)
}

sourceSets {
    main {
        resources.srcDir(buildHook.map { it.outputDir })
    }
}

tasks {
    wrapper { gradleVersion = "9.8.1" }
}
