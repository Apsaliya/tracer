/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.moshi.kotlin.codegen

import com.google.auto.service.AutoService
import com.personatech.customannotations.ContributesViewModel
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.WildcardTypeName
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.assisted.AssistedInject
import dagger.multibindings.IntoMap
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType.ISOLATING
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind.CONSTRUCTOR
import javax.lang.model.element.ElementKind.INTERFACE
import javax.lang.model.element.Modifier.ABSTRACT
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.Modifier.STATIC
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypeException
import javax.lang.model.type.MirroredTypesException
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic

/**
 * An annotation processor that reads Kotlin data classes and generates Moshi JsonAdapters for them.
 * This generates Kotlin code, and understands basic Kotlin language features like default values
 * and companion objects.
 *
 * The generated class will match the visibility of the given data class (i.e. if it's internal, the
 * adapter will also be internal).
 */
@AutoService(Processor::class)
@IncrementalAnnotationProcessor(ISOLATING)
class ViewModelCodegenProcessor : AbstractProcessor() {

  private lateinit var types: Types
  private lateinit var elements: Elements
  private lateinit var filer: Filer
  private lateinit var messager: Messager
  private val viewModelAnnot = ContributesViewModel::class.java
  private val assistedInjectAnnot = AssistedInject::class.java
  private var generatedType: ClassName? = null

  override fun getSupportedAnnotationTypes() = setOf(viewModelAnnot.canonicalName)

  override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latest()

  override fun getSupportedOptions() = setOf("pt.vm.generated")

  override fun init(processingEnv: ProcessingEnvironment) {
    super.init(processingEnv)

    this.types = processingEnv.typeUtils
    this.elements = processingEnv.elementUtils
    this.filer = processingEnv.filer
    this.messager = processingEnv.messager
  }

  override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
    if (roundEnv.errorRaised()) {
      // An error was raised in the previous round. Don't try anything for now to avoid adding
      // possible more noise.
      return false
    }


    for (type in roundEnv.getElementsAnnotatedWith(viewModelAnnot)) {
      val member = type.enclosedElements.first { it.kind == CONSTRUCTOR }
      if (member.getAnnotation(assistedInjectAnnot) != null) {
        continue
      }
      val className = ClassName.get(type as TypeElement)
      val viewModelAnnotType = type.getAnnotation(viewModelAnnot)
      try {
        viewModelAnnotType.viewModelInterface
      } catch (e: MirroredTypeException) {
        val interfaceTypeMirror = e.typeMirror
        /*if ((types.asElement(e.typeMirror) as TypeElement).kind != INTERFACE) {
          messager.printMessage(
            Diagnostic.Kind.ERROR, "${className.packageName()}.${className.simpleName()} in annotated with ContributesViewModel but it does not implement interface ${ClassName.get(interfaceTypeMirror as TypeElement).simpleName()}",
            type)
          continue
        }*/

        if (!generateViewModelInterfaceModules(interfaceTypeMirror, className, type)) {
          continue
        }
        generateViewModelModule(className, type)

        try {
          viewModelAnnotType.additionalInterfaces
        } catch (e: MirroredTypesException) {
          val additionalMirrors = e.typeMirrors
          if (additionalMirrors.isNotEmpty()) {
            for (i in 0 until additionalMirrors.size) {
              if (!generateViewModelInterfaceModules(additionalMirrors[i], className, type)) {
                continue
              }
            }
          }
        }

        /*val viewModel = ClassName.get("androidx.lifecycle", "ViewModel")
        val langClassName = ClassName.get("java.lang", "Class")
        val vmWildCard = WildcardTypeName.subtypeOf(viewModel)
        val classOfVmReturnType = ParameterizedTypeName.get(langClassName, vmWildCard)

        val vmInterfaceModuleMethod = MethodSpec.methodBuilder("bind")
          .addModifiers(PUBLIC, STATIC)
          .addAnnotation(IntoMap::class.java)
          .addAnnotation(Provides::class.java)
          .addAnnotation(
            AnnotationSpec.builder(ClassName.get("com.personatech.base.di", "ViewModelInterfaceKey"))
              .addMember("value", "\$T.class", ClassName.get(interfaceTypeMirror))
              .build()
          )
          .addStatement("return \$T.class", TypeName.get(type.asType()))
          .returns(classOfVmReturnType)

        val classOfVmModule = TypeSpec.classBuilder("${className.simpleName()}TypeViewModelClassModule")
          .addAnnotation(Module::class.java)
          .addAnnotation(
            AnnotationSpec.builder(ClassName.get("dagger.hilt", "InstallIn"))
              .addMember("value", "\$T.class", ClassName.get("dagger.hilt.components", "SingletonComponent"))
              .build()
          )
          .addMethod(vmInterfaceModuleMethod.build())
          .addModifiers(PUBLIC)
          .addOriginatingElement(type)
          .build()

        val vmInterfaceModuleFile = JavaFile.builder(ClassName.get(type).packageName(), classOfVmModule)
          .build()

        vmInterfaceModuleFile.writeTo(filer)

        val viewModelClassMethod = MethodSpec.methodBuilder("bind")
          .addModifiers(PUBLIC, ABSTRACT)
          .addAnnotation(IntoMap::class.java)
          .addAnnotation(Binds::class.java)
          .addAnnotation(
            AnnotationSpec.builder(ClassName.get("com.personatech.base.di", "ViewModelKey"))
              .addMember("value", "\$T.class", ClassName.get(type))
              .build()
          )
          .addParameter(TypeName.get(type.asType()), "impl")
          .returns(viewModel)

        val vmModule = TypeSpec.interfaceBuilder("${className.simpleName()}ViewModelModule")
          .addAnnotation(Module::class.java)
          .addAnnotation(
            AnnotationSpec.builder(ClassName.get("dagger.hilt", "InstallIn"))
              .addMember("value", "\$T.class", ClassName.get("dagger.hilt.components", "SingletonComponent"))
              .build()
          )
          .addMethod(viewModelClassMethod.build())
          .addModifiers(PUBLIC)
          .addOriginatingElement(type)
          .build()

        val viewModelModuleFile = JavaFile.builder(ClassName.get(type).packageName(), vmModule)
          .build()

        viewModelModuleFile.writeTo(filer)*/

      }
    }

    return true
  }

  private fun generateViewModelModule(className: ClassName, type: Element) {
    val viewModel = ClassName.get("androidx.lifecycle", "ViewModel")
    val viewModelClassMethod = MethodSpec.methodBuilder("bind")
      .addModifiers(PUBLIC, ABSTRACT)
      .addAnnotation(IntoMap::class.java)
      .addAnnotation(Binds::class.java)
      .addAnnotation(
        AnnotationSpec.builder(ClassName.get("com.personatech.base.di", "ViewModelKey"))
          .addMember("value", "\$T.class", ClassName.get(type as TypeElement))
          .build()
      )
      .addParameter(TypeName.get(type.asType()), "impl")
      .returns(viewModel)

    val vmModule = TypeSpec.interfaceBuilder("${className.simpleName()}ViewModelModule")
      .addAnnotation(Module::class.java)
      .addAnnotation(
        AnnotationSpec.builder(ClassName.get("dagger.hilt", "InstallIn"))
          .addMember("value", "\$T.class", ClassName.get("dagger.hilt.components", "SingletonComponent"))
          .build()
      )
      .addMethod(viewModelClassMethod.build())
      .addModifiers(PUBLIC)
      .addOriginatingElement(type)
      .build()

    val viewModelModuleFile = JavaFile.builder(ClassName.get(type).packageName(), vmModule)
      .build()

    viewModelModuleFile.writeTo(filer)
  }

  private fun generateViewModelInterfaceModules(interfaceTypeMirror: TypeMirror, className: ClassName, type: Element): Boolean {
    val interfaceTypeElement = (types.asElement(interfaceTypeMirror) as TypeElement)
    if (interfaceTypeElement.kind != INTERFACE) {
      messager.printMessage(
        Diagnostic.Kind.ERROR, "${className.packageName()}.${className.simpleName()} in annotated with ContributesViewModel but it does not implement interface $interfaceTypeMirror",
        type)
      return false
    }

    val viewModel = ClassName.get("androidx.lifecycle", "ViewModel")
    val langClassName = ClassName.get("java.lang", "Class")
    val vmWildCard = WildcardTypeName.subtypeOf(viewModel)
    val classOfVmReturnType = ParameterizedTypeName.get(langClassName, vmWildCard)

    val vmInterfaceModuleMethod = MethodSpec.methodBuilder("bind")
      .addModifiers(PUBLIC, STATIC)
      .addAnnotation(IntoMap::class.java)
      .addAnnotation(Provides::class.java)
      .addAnnotation(
        AnnotationSpec.builder(ClassName.get("com.personatech.base.di", "ViewModelInterfaceKey"))
          .addMember("value", "\$T.class", ClassName.get(interfaceTypeMirror))
          .build()
      )
      .addStatement("return \$T.class", TypeName.get(type.asType()))
      .returns(classOfVmReturnType)

    val classOfVmModule = TypeSpec.classBuilder("${className.simpleName()}_${ClassName.get(interfaceTypeElement).simpleName()}_ClassMappingModule")
      .addAnnotation(Module::class.java)
      .addAnnotation(
        AnnotationSpec.builder(ClassName.get("dagger.hilt", "InstallIn"))
          .addMember("value", "\$T.class", ClassName.get("dagger.hilt.components", "SingletonComponent"))
          .build()
      )
      .addMethod(vmInterfaceModuleMethod.build())
      .addModifiers(PUBLIC)
      .addOriginatingElement(type)
      .build()

    val vmInterfaceModuleFile = JavaFile.builder(ClassName.get(type as TypeElement).packageName(), classOfVmModule)
      .build()

    vmInterfaceModuleFile.writeTo(filer)
    return true
  }
}