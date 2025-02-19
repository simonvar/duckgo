/*
 * Copyright (c) 2022 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.anvil.compiler

import com.duckduckgo.anvil.annotations.ContributesPluginPoint
import com.google.auto.service.AutoService
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.annotations.ExperimentalAnvilApi
import com.squareup.anvil.compiler.api.*
import com.squareup.anvil.compiler.internal.*
import com.squareup.anvil.compiler.internal.reference.ClassReference
import com.squareup.anvil.compiler.internal.reference.argumentAt
import com.squareup.anvil.compiler.internal.reference.asClassName
import com.squareup.anvil.compiler.internal.reference.classAndInnerClassReferences
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import dagger.Binds
import dagger.multibindings.Multibinds
import java.io.File
import javax.inject.Inject
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile

/**
 * This Anvil code generator allows inject ViewModel without manually creating the ViewModel factory
 */
@OptIn(ExperimentalAnvilApi::class)
@AutoService(CodeGenerator::class)
class ContributesPluginPointCodeGenerator : CodeGenerator {

    override fun isApplicable(context: AnvilContext): Boolean = true

    override fun generateCode(codeGenDir: File, module: ModuleDescriptor, projectFiles: Collection<KtFile>): Collection<GeneratedFile> {
        return projectFiles.classAndInnerClassReferences(module)
            .toList()
            .filter { it.isAnnotatedWith(ContributesPluginPoint::class.fqName) }
            .flatMap {
                listOf(
                    generatePluginPoint(it, codeGenDir, module),
                    generateBindingModule(it, codeGenDir, module),
                )
            }
            .toList()
    }

    private fun generatePluginPoint(vmClass: ClassReference.Psi, codeGenDir: File, module: ModuleDescriptor): GeneratedFile {
        val generatedPackage = vmClass.packageFqName.toString()
        val pluginPointClassName = "${vmClass.shortName}_PluginPoint"
        val scope = vmClass.annotations.first { it.fqName == ContributesPluginPoint::class.fqName }.scopeOrNull(0)!!
        val pluginClassName = vmClass.pluginClassName(ContributesPluginPoint::class.fqName) ?: vmClass.asClassName()

        if (!vmClass.isInterface()) {
            throw AnvilCompilationException(
                "${vmClass.fqName} must be an interface",
                element = vmClass.clazz.identifyingElement,
            )
        }
        val content = FileSpec.buildFile(generatedPackage, pluginPointClassName) {
            addType(
                TypeSpec.classBuilder(pluginPointClassName)
                    .addSuperinterface(pluginPointFqName.asClassName(module).parameterizedBy(pluginClassName))
                    .primaryConstructor(
                        PropertySpec
                            .builder(
                                "plugins",
                                ClassName("com.duckduckgo.di", "DaggerSet").parameterizedBy(pluginClassName),
                            )
                            .addModifiers(KModifier.PRIVATE)
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("getPlugins")
                            .addModifiers(KModifier.OVERRIDE)
                            .returns(kotlinCollectionFqName.asClassName(module).parameterizedBy(pluginClassName))
                            .addComment("Sort plugins by class name to ensure execution consistency")
                            .addCode(
                                """
                                    return plugins.toList().sortedBy { it.javaClass.name }
                                """.trimIndent(),
                            )
                            .build(),
                    )
                    .build(),
            )
        }

        return createGeneratedFile(codeGenDir, generatedPackage, pluginPointClassName, content)
    }

    private fun generateBindingModule(vmClass: ClassReference.Psi, codeGenDir: File, module: ModuleDescriptor): GeneratedFile {
        val generatedPackage = vmClass.packageFqName.toString()
        val moduleClassName = "${vmClass.shortName}_PluginPoint_Module"
        val scope = vmClass.annotations.first { it.fqName == ContributesPluginPoint::class.fqName }.scopeOrNull(0)!!
        val pluginClassName = vmClass.pluginClassName(ContributesPluginPoint::class.fqName) ?: vmClass.asClassName()

        val content = FileSpec.buildFile(generatedPackage, moduleClassName) {
            addType(
                TypeSpec.classBuilder(moduleClassName)
                    .addAnnotation(AnnotationSpec.builder(dagger.Module::class).build())
                    .addAnnotation(
                        AnnotationSpec
                            .builder(ContributesTo::class).addMember("scope = %T::class", scope.asClassName())
                            .build(),
                    )
                    .addModifiers(KModifier.ABSTRACT)
                    .addFunction(
                        FunSpec.builder("bindEmpty${vmClass.shortName}_PluginPoint")
                            .addAnnotation(AnnotationSpec.builder(Multibinds::class).build())
                            .addModifiers(KModifier.ABSTRACT)
                            .returns(daggerSetFqName.asClassName(module).parameterizedBy(pluginClassName))
                            .build(),
                    )
                    .addFunction(
                        FunSpec.builder("bind${vmClass.shortName}_PluginPoint")
                            .addParameter(
                                "pluginPoint",
                                FqName("$generatedPackage.${vmClass.shortName}_PluginPoint").asClassName(module),
                            )
                            .addAnnotation(AnnotationSpec.builder(Binds::class).build())
                            .addModifiers(KModifier.ABSTRACT)
                            .returns(pluginPointFqName.asClassName(module).parameterizedBy(pluginClassName))
                            .build(),
                    )
                    .build(),
            ).build()
        }

        return createGeneratedFile(codeGenDir, generatedPackage, moduleClassName, content)
    }

    companion object {
        private val pluginPointFqName = FqName("com.duckduckgo.app.global.plugins.PluginPoint")
        private val kotlinCollectionFqName = FqName("kotlin.collections.Collection")
        private val daggerSetFqName = FqName("com.duckduckgo.di.DaggerSet")
    }

    private fun TypeSpec.Builder.primaryConstructor(vararg properties: PropertySpec): TypeSpec.Builder {
        val propertySpecs = properties.map { p -> p.toBuilder().initializer(p.name).build() }
        val parameters = propertySpecs.map { ParameterSpec.builder(it.name, it.type).build() }
        val constructor = FunSpec.constructorBuilder()
            .addParameters(parameters)
            .addAnnotation(AnnotationSpec.builder(Inject::class).build())
            .build()

        return this
            .primaryConstructor(constructor)
            .addProperties(propertySpecs)
    }

    private fun ClassReference.Psi.pluginClassName(
        fqName: FqName,
    ): ClassName? {
        return annotations
            .first { it.fqName == fqName }
            .argumentAt(name = "boundType", index = 1)
            ?.annotation
            ?.boundTypeOrNull()
            ?.asClassName()
    }
}
