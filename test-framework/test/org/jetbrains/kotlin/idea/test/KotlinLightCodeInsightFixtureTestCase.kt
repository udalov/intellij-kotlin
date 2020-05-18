/*
 * Copyright 2000-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.test

import com.intellij.application.options.CodeStyle
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.startup.impl.StartupManagerImpl
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProviderImpl
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.pom.java.LanguageLevel
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.RunAll
import com.intellij.util.ThrowableRunnable
import org.apache.log4j.Logger
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.config.CompilerSettings.Companion.DEFAULT_ADDITIONAL_ARGUMENTS
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCommonCompilerArgumentsHolder
import org.jetbrains.kotlin.idea.compiler.configuration.KotlinCompilerSettings
import org.jetbrains.kotlin.idea.facet.*
import org.jetbrains.kotlin.idea.formatter.KotlinStyleGuideCodeStyle
import org.jetbrains.kotlin.idea.inspections.UnusedSymbolInspection
import org.jetbrains.kotlin.idea.test.CompilerTestDirectives.COMPILER_ARGUMENTS_DIRECTIVE
import org.jetbrains.kotlin.idea.test.CompilerTestDirectives.JVM_TARGET_DIRECTIVE
import org.jetbrains.kotlin.idea.test.CompilerTestDirectives.LANGUAGE_VERSION_DIRECTIVE
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.InTextDirectivesUtils
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.TestMetadataUtil
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.rethrow
import java.io.File
import java.io.IOException
import java.nio.file.Paths
import java.util.*
import kotlin.reflect.full.findAnnotation

abstract class KotlinLightCodeInsightFixtureTestCase : KotlinLightCodeInsightFixtureTestCaseBase() {
    private val exceptions = ArrayList<Throwable>()

    protected open val captureExceptions = true

    protected fun testDataFile(fileName: String): File = File(testDataPath, fileName)

    protected fun testDataFile(): File = testDataFile(fileName())

    protected fun testPath(fileName: String = fileName()): String = testDataFile(fileName).toString()

    protected fun testPath(): String = testPath(fileName())

    protected open fun fileName(): String = KotlinTestUtils.getTestDataFileName(this::class.java, this.name) ?: (getTestName(false) + ".kt")

    protected fun getLocalPath(path: String): String = File(path).toRelativeString(File(testDataPath))

    override fun getTestDataPath(): String {
        return TestMetadataUtil.getTestDataPath(javaClass) ?: super.getTestDataPath()
    }

    override fun setUp() {
        super.setUp()

        enableKotlinOfficialCodeStyle(project)
        // We do it here to avoid possible initialization problems
        // UnusedSymbolInspection() calls IDEA UnusedDeclarationInspection() in static initializer,
        // which in turn registers some extensions provoking "modifications aren't allowed during highlighting"
        // when done lazily
        UnusedSymbolInspection()

        VfsRootAccess.allowRootAccess(myFixture.testRootDisposable, KotlinTestUtils.getHomeDirectory())

        editorTrackerProjectOpened(project)

        invalidateLibraryCache(project)

        if (captureExceptions) {
            LoggedErrorProcessor.setNewInstance(object : LoggedErrorProcessor() {
                override fun processError(message: String?, t: Throwable?, details: Array<out String>?, logger: Logger) {
                    exceptions.addIfNotNull(t)
                    super.processError(message, t, details, logger)
                }
            })
        }
    }

    override fun tearDown() {
        runAll(
            ThrowableRunnable { LoggedErrorProcessor.restoreDefaultProcessor() },
            ThrowableRunnable { disableKotlinOfficialCodeStyle(project) },
            ThrowableRunnable { super.tearDown() },
        )

        if (exceptions.isNotEmpty()) {
            exceptions.forEach { it.printStackTrace() }
            throw AssertionError("Exceptions in other threads happened")
        }
    }

    override fun getProjectDescriptor(): LightProjectDescriptor = getProjectDescriptorFromFileDirective()

    protected fun getProjectDescriptorFromAnnotation(): LightProjectDescriptor {
        val testMethod = this::class.java.getDeclaredMethod(name)
        val platformId = testMethod.getAnnotation(ProjectDescriptorKind::class.java)?.value

        return when (platformId) {
            JDK_AND_MULTIPLATFORM_STDLIB_WITH_SOURCES -> KotlinJdkAndMultiplatformStdlibDescriptor.JDK_AND_MULTIPLATFORM_STDLIB_WITH_SOURCES

            KOTLIN_JVM_WITH_STDLIB_SOURCES -> ProjectDescriptorWithStdlibSources.INSTANCE

            KOTLIN_JAVASCRIPT -> KotlinStdJSProjectDescriptor

            KOTLIN_JVM_WITH_STDLIB_SOURCES_WITH_ADDITIONAL_JS -> {
                KotlinMultiModuleProjectDescriptor(
                    KOTLIN_JVM_WITH_STDLIB_SOURCES_WITH_ADDITIONAL_JS,
                    mainModuleDescriptor = ProjectDescriptorWithStdlibSources.INSTANCE,
                    additionalModuleDescriptor = KotlinStdJSProjectDescriptor
                )
            }

            KOTLIN_JAVASCRIPT_WITH_ADDITIONAL_JVM_WITH_STDLIB -> {
                KotlinMultiModuleProjectDescriptor(
                    KOTLIN_JAVASCRIPT_WITH_ADDITIONAL_JVM_WITH_STDLIB,
                    mainModuleDescriptor = KotlinStdJSProjectDescriptor,
                    additionalModuleDescriptor = ProjectDescriptorWithStdlibSources.INSTANCE
                )
            }

            else -> throw IllegalStateException("Unknown value for project descriptor kind")
        }
    }

    protected fun getProjectDescriptorFromTestName(): LightProjectDescriptor {
        val testName = StringUtil.toLowerCase(getTestName(false))

        return when {
            testName.endsWith("runtime") -> KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE
            testName.endsWith("stdlib") -> ProjectDescriptorWithStdlibSources.INSTANCE
            else -> KotlinLightProjectDescriptor.INSTANCE
        }
    }

    private fun getProjectDescriptorFromFileDirective(): LightProjectDescriptor {
        if (isAllFilesPresentInTest()) {
            return KotlinLightProjectDescriptor.INSTANCE
        }

        val file = File(testDataPath, fileName())
        if (!file.exists()) {
            return KotlinLightProjectDescriptor.INSTANCE
        }

        try {
            val fileText = FileUtil.loadFile(file, true)

            val withLibraryDirective = InTextDirectivesUtils.findLinesWithPrefixesRemoved(fileText, "WITH_LIBRARY:")
            return when {
                !withLibraryDirective.isEmpty() ->
                    SdkAndMockLibraryProjectDescriptor(
                        PluginTestCaseBase.getTestDataPathBase() + "/" + withLibraryDirective[0],
                        true
                    )

                InTextDirectivesUtils.isDirectiveDefined(fileText, "RUNTIME_WITH_SOURCES") ->
                    ProjectDescriptorWithStdlibSources.INSTANCE

                InTextDirectivesUtils.isDirectiveDefined(fileText, "RUNTIME_WITH_KOTLIN_TEST") ->
                    KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE_WITH_KOTLIN_TEST

                InTextDirectivesUtils.isDirectiveDefined(fileText, "RUNTIME_WITH_FULL_JDK") ->
                    KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE_FULL_JDK

                InTextDirectivesUtils.isDirectiveDefined(fileText, "RUNTIME_WITH_JDK_10") ->
                    KotlinWithJdkAndRuntimeLightProjectDescriptor.getInstance(LanguageLevel.JDK_10)

                InTextDirectivesUtils.isDirectiveDefined(fileText, "RUNTIME_WITH_REFLECT") ->
                    KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE_WITH_REFLECT

                InTextDirectivesUtils.isDirectiveDefined(fileText, "RUNTIME_WITH_SCRIPT_RUNTIME") ->
                    KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE_WITH_SCRIPT_RUNTIME

                InTextDirectivesUtils.isDirectiveDefined(fileText, "RUNTIME") ||
                        InTextDirectivesUtils.isDirectiveDefined(fileText, "WITH_RUNTIME") ->
                    KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE

                InTextDirectivesUtils.isDirectiveDefined(fileText, "JS") ->
                    KotlinStdJSProjectDescriptor

                InTextDirectivesUtils.isDirectiveDefined(fileText, "ENABLE_MULTIPLATFORM") ->
                    KotlinProjectDescriptorWithFacet.KOTLIN_STABLE_WITH_MULTIPLATFORM

                else -> getDefaultProjectDescriptor()
            }
        } catch (e: IOException) {
            throw rethrow(e)
        }
    }

    protected open fun getDefaultProjectDescriptor(): KotlinLightProjectDescriptor = KotlinLightProjectDescriptor.INSTANCE

    protected fun isAllFilesPresentInTest(): Boolean = KotlinTestUtils.isAllFilesPresentTest(getTestName(false))

    protected fun performNotWriteEditorAction(actionId: String): Boolean {
        val dataContext = (myFixture.editor as EditorEx).dataContext

        val managerEx = ActionManagerEx.getInstanceEx()
        val action = managerEx.getAction(actionId)
        val event = AnActionEvent(null, dataContext, ActionPlaces.UNKNOWN, Presentation(), managerEx, 0)

        action.update(event)
        if (!event.presentation.isEnabled) {
            return false
        }

        managerEx.fireBeforeActionPerformed(action, dataContext, event)
        action.actionPerformed(event)

        managerEx.fireAfterActionPerformed(action, dataContext, event)
        return true
    }

}

object CompilerTestDirectives {
    const val LANGUAGE_VERSION_DIRECTIVE = "LANGUAGE_VERSION:"
    const val JVM_TARGET_DIRECTIVE = "JVM_TARGET:"
    const val COMPILER_ARGUMENTS_DIRECTIVE = "COMPILER_ARGUMENTS:"

    val ALL_COMPILER_TEST_DIRECTIVES = listOf(LANGUAGE_VERSION_DIRECTIVE, JVM_TARGET_DIRECTIVE, COMPILER_ARGUMENTS_DIRECTIVE)
}

fun <T> withCustomCompilerOptions(fileText: String, project: Project, module: Module, body: () -> T): T {
    val removeFacet = !module.hasKotlinFacet()
    val configured = configureCompilerOptions(fileText, project, module)
    try {
        return body()
    } finally {
        if (configured) {
            rollbackCompilerOptions(project, module, removeFacet)
        }
    }
}

private fun configureCompilerOptions(fileText: String, project: Project, module: Module): Boolean {
    val version = InTextDirectivesUtils.findStringWithPrefixes(fileText, "// $LANGUAGE_VERSION_DIRECTIVE ")
    val jvmTarget = InTextDirectivesUtils.findStringWithPrefixes(fileText, "// $JVM_TARGET_DIRECTIVE ")
    // We can have several such directives in quickFixMultiFile tests
    // TODO: refactor such tests or add sophisticated check for the directive
    val options = InTextDirectivesUtils.findListWithPrefixes(fileText, "// $COMPILER_ARGUMENTS_DIRECTIVE ").firstOrNull()

    if (version != null || jvmTarget != null || options != null) {
        configureLanguageAndApiVersion(
            project, module,
            version ?: LanguageVersion.LATEST_STABLE.versionString,
            null
        )

        val facetSettings = KotlinFacet.get(module)!!.configuration.settings

        if (jvmTarget != null) {
            val compilerArguments = facetSettings.compilerArguments
            require(compilerArguments is K2JVMCompilerArguments) { "Attempt to specify `$JVM_TARGET_DIRECTIVE` for non-JVM test" }
            compilerArguments.jvmTarget = jvmTarget
        }

        if (options != null) {
            val compilerSettings = facetSettings.compilerSettings ?: CompilerSettings().also {
                facetSettings.compilerSettings = it
            }
            compilerSettings.additionalArguments = options
            facetSettings.updateMergedArguments()

            KotlinCompilerSettings.getInstance(project).update { this.additionalArguments = options }
        }
        return true
    }

    return false
}

fun configureRegistryAndRun(fileText: String, body: () -> Unit) {
    val registers = InTextDirectivesUtils.findListWithPrefixes(fileText, "// REGISTRY:")
        .map { it.split(' ') }
        .map { Registry.get(it.first()) to it.last() }
    try {
        for ((register, value) in registers) {
            register.setValue(value)
        }
        body()
    } finally {
        for ((register, _) in registers) {
            register.resetToDefault()
        }
    }
}

fun configureCodeStyleAndRun(
    project: Project,
    configurator: (CodeStyleSettings) -> Unit,
    body: () -> Unit
) {
    val codeStyleSettings = CodeStyle.getSettings(project)
    try {
        configurator(codeStyleSettings)
        body()
    } finally {
        codeStyleSettings.clearCodeStyleSettings()
    }
}

fun enableKotlinOfficialCodeStyle(project: Project) {
    KotlinStyleGuideCodeStyle.apply(CodeStyle.getSettings(project))
}

fun disableKotlinOfficialCodeStyle(project: Project) {
    CodeStyle.getSettings(project)
}

fun runAll(
    vararg actions: ThrowableRunnable<Throwable>,
    suppressedExceptions: List<Throwable> = emptyList()
) = RunAll(*actions).run(suppressedExceptions)

private fun rollbackCompilerOptions(project: Project, module: Module, removeFacet: Boolean) {
    KotlinCompilerSettings.getInstance(project).update { this.additionalArguments = DEFAULT_ADDITIONAL_ARGUMENTS }
    KotlinCommonCompilerArgumentsHolder.getInstance(project).update { this.languageVersion = LanguageVersion.LATEST_STABLE.versionString }

    if (removeFacet) {
        module.removeKotlinFacet(IdeModifiableModelsProviderImpl(project), commitModel = true)
        return
    }

    configureLanguageAndApiVersion(project, module, LanguageVersion.LATEST_STABLE.versionString, ApiVersion.LATEST_STABLE.versionString)

    val facetSettings = KotlinFacet.get(module)!!.configuration.settings
    (facetSettings.compilerArguments as? K2JVMCompilerArguments)?.jvmTarget = JvmTarget.DEFAULT.description

    val compilerSettings = facetSettings.compilerSettings ?: CompilerSettings().also {
        facetSettings.compilerSettings = it
    }
    compilerSettings.additionalArguments = DEFAULT_ADDITIONAL_ARGUMENTS
    facetSettings.updateMergedArguments()
}

fun withCustomLanguageAndApiVersion(
    project: Project,
    module: Module,
    languageVersion: String,
    apiVersion: String?,
    body: () -> Unit
) {
    val removeFacet = !module.hasKotlinFacet()
    configureLanguageAndApiVersion(project, module, languageVersion, apiVersion)
    try {
        body()
    } finally {
        if (removeFacet) {
            KotlinCommonCompilerArgumentsHolder.getInstance(project)
                .update { this.languageVersion = LanguageVersion.LATEST_STABLE.versionString }
            module.removeKotlinFacet(IdeModifiableModelsProviderImpl(project), commitModel = true)
        } else {
            configureLanguageAndApiVersion(
                project,
                module,
                LanguageVersion.LATEST_STABLE.versionString,
                ApiVersion.LATEST_STABLE.versionString
            )
        }
    }
}

private fun configureLanguageAndApiVersion(
    project: Project,
    module: Module,
    languageVersion: String,
    apiVersion: String?
) {
    WriteAction.run<Throwable> {
        val modelsProvider = IdeModifiableModelsProviderImpl(project)
        val facet = module.getOrCreateFacet(modelsProvider, useProjectSettings = false)

        val compilerArguments = facet.configuration.settings.compilerArguments
        if (compilerArguments != null) {
            compilerArguments.apiVersion = null
        }

        facet.configureFacet(languageVersion, LanguageFeature.State.DISABLED, null, modelsProvider)
        if (apiVersion != null) {
            facet.configuration.settings.apiLevel = LanguageVersion.fromVersionString(apiVersion)
        }
        KotlinCommonCompilerArgumentsHolder.getInstance(project).update { this.languageVersion = languageVersion }
        modelsProvider.commit()
    }
}

fun Project.allKotlinFiles(): List<KtFile> {
    val virtualFiles = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, ProjectScope.getProjectScope(this))
    return virtualFiles
        .map { PsiManager.getInstance(this).findFile(it) }
        .filterIsInstance<KtFile>()
}

fun Project.allJavaFiles(): List<PsiJavaFile> {
    val virtualFiles = FileTypeIndex.getFiles(JavaFileType.INSTANCE, ProjectScope.getProjectScope(this))
    return virtualFiles
        .map { PsiManager.getInstance(this).findFile(it) }
        .filterIsInstance<PsiJavaFile>()
}

fun Project.findFileWithCaret(): PsiClassOwner {
    return (allKotlinFiles() + allJavaFiles()).single {
        "<caret>" in VfsUtilCore.loadText(it.virtualFile) && !it.virtualFile.name.endsWith(".after")
    }
}
