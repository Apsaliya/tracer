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
import com.personatech.customannotations.ContributesInternalBinding
import com.personatech.customannotations.ContributesMultibinding
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import dagger.Binds
import dagger.Module
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType.ISOLATING
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.inject.Named
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier.ABSTRACT
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.Name
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypesException
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
class BindingsCodegenProcessor : AbstractProcessor() {

  private lateinit var types: Types
  private lateinit var elements: Elements
  private lateinit var filer: Filer
  private lateinit var messager: Messager
  private val contri = ContributesInternalBinding::class.java

  override fun getSupportedAnnotationTypes() = setOf(contri.canonicalName)

  override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latest()

  override fun getSupportedOptions() = setOf("pt.binding.generated")

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


    for (type in roundEnv.getElementsAnnotatedWith(contri)) {
      val className = ClassName.get(type as TypeElement)
      val jsonClass = type.getAnnotation(contri)

      try {
        val allBounds = jsonClass.boundTypes
      } catch (e: MirroredTypesException) {
        if (e.typeMirrors.isEmpty()) {

          if (type.interfaces.size > 1) {
            //error
            messager.printMessage(
              Diagnostic.Kind.ERROR, "${className.packageName()}.${className.simpleName()} implements multiple interfaces. Please specify valid bound",
              type)
          }

          val firstInterface = type.interfaces.first()
          val boundType = TypeName.get(firstInterface)
          generateBindingAndWriteToFiler(type, boundType, types.asElement(firstInterface).simpleName)
        } else {
          e.typeMirrors.forEach {
            val boundType = TypeName.get(it)
            generateBindingAndWriteToFiler(type, boundType, types.asElement(it).simpleName)
          }
        }

      }
    }

    return true
  }

  private fun generateBindingAndWriteToFiler(
    type: Element,
    boundType: TypeName,
    boundTypeName: Name
  ) {

    val methodSpec = MethodSpec.methodBuilder("bind${type.simpleName}")
      .addModifiers(PUBLIC, ABSTRACT)
      .addAnnotation(Binds::class.java)
      .addParameter(TypeName.get(type.asType()), "impl")
      .returns(boundType)

    type.annotationMirrors.forEach {
      val isContributesInternalBinding = TypeName.get(it.annotationType) == TypeName.get(ContributesInternalBinding::class.java)
      val isContributesInternalMultiBinding = TypeName.get(it.annotationType) == TypeName.get(ContributesMultibinding::class.java)
      val isKotlinMetaData = TypeName.get(it.annotationType) == TypeName.get(Metadata::class.java)
      val isQualifier = TypeName.get(it.annotationType) == TypeName.get(Named::class.java)
      val shouldExcludeQualifier = type.getAnnotation(contri).excludeQualifier
      val shouldExcludeBecauseThisAnnotationIsQualifier = if (shouldExcludeQualifier) {
        isQualifier
      } else false
      if (!isContributesInternalBinding && !isKotlinMetaData && !isContributesInternalMultiBinding && !shouldExcludeBecauseThisAnnotationIsQualifier) {
        methodSpec.addAnnotation(AnnotationSpec.get(it))
      }
    }

    val bindingModule = TypeSpec.interfaceBuilder("${boundTypeName}${type.simpleName}BindingModule")
      .addAnnotation(Module::class.java)
      .addAnnotation(
        AnnotationSpec.builder(ClassName.get("dagger.hilt", "InstallIn"))
          .addMember("value", "\$T.class", ClassName.get("dagger.hilt.components", "SingletonComponent"))
          .build()
      )
      .addMethod(methodSpec.build())
      .addModifiers(PUBLIC)
      .addOriginatingElement(type)
      .build()

    val javaFile = JavaFile.builder(ClassName.get(type as TypeElement).packageName(), bindingModule)
      .build()

    javaFile.writeTo(filer)
  }
}