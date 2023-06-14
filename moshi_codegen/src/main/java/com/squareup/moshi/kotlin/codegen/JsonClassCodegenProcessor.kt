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
import com.personatech.customannotations.Composed
import com.personatech.customannotations.ContributesInternalBinding
import com.personatech.customannotations.ContributesViewModel
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import com.squareup.kotlinpoet.metadata.classinspectors.ElementsClassInspector
import com.squareup.moshi.JsonClass
import com.squareup.moshi.kotlin.codegen.api.AdapterGenerator
import com.squareup.moshi.kotlin.codegen.api.Options.OPTION_GENERATED
import com.squareup.moshi.kotlin.codegen.api.Options.OPTION_GENERATE_PROGUARD_RULES
import com.squareup.moshi.kotlin.codegen.api.Options.OPTION_INSTANTIATE_ANNOTATIONS
import com.squareup.moshi.kotlin.codegen.api.Options.POSSIBLE_GENERATED_NAMES
import com.squareup.moshi.kotlin.codegen.api.ProguardConfig
import com.squareup.moshi.kotlin.codegen.api.PropertyGenerator
import com.squareup.moshi.kotlin.codegen.api.TargetProperty
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
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import javax.tools.StandardLocation

/**
 * An annotation processor that reads Kotlin data classes and generates Moshi JsonAdapters for them.
 * This generates Kotlin code, and understands basic Kotlin language features like default values
 * and companion objects.
 *
 * The generated class will match the visibility of the given data class (i.e. if it's internal, the
 * adapter will also be internal).
 */
@KotlinPoetMetadataPreview
@AutoService(Processor::class)
@IncrementalAnnotationProcessor(ISOLATING)
class JsonClassCodegenProcessor : AbstractProcessor() {

  private lateinit var types: Types
  private lateinit var elements: Elements
  private lateinit var filer: Filer
  private lateinit var messager: Messager
  private lateinit var cachedClassInspector: MoshiCachedClassInspector
  private val annotation = JsonClass::class.java
  private val composedAnnotation = Composed::class.java
  private val contri = ContributesInternalBinding::class.java
  private val viewModelAnnot = ContributesViewModel::class.java
  private var generatedType: ClassName? = null
  private var generateProguardRules: Boolean = true
  private var instantiateAnnotations: Boolean = true

  override fun getSupportedAnnotationTypes() = setOf(annotation.canonicalName)

  override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latest()

  override fun getSupportedOptions() = setOf(OPTION_GENERATED)

  override fun init(processingEnv: ProcessingEnvironment) {
    super.init(processingEnv)
    generatedType = processingEnv.options[OPTION_GENERATED]?.let {
      POSSIBLE_GENERATED_NAMES[it] ?: error(
        "Invalid option value for $OPTION_GENERATED. Found $it, " +
          "allowable values are $POSSIBLE_GENERATED_NAMES."
      )
    }

    generateProguardRules = processingEnv.options[OPTION_GENERATE_PROGUARD_RULES]?.toBooleanStrictOrNull() ?: true
    instantiateAnnotations = processingEnv.options[OPTION_INSTANTIATE_ANNOTATIONS]?.toBooleanStrictOrNull() ?: true

    this.types = processingEnv.typeUtils
    this.elements = processingEnv.elementUtils
    this.filer = processingEnv.filer
    this.messager = processingEnv.messager
    cachedClassInspector = MoshiCachedClassInspector(ElementsClassInspector.create(elements, types))
  }

  override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
    if (roundEnv.errorRaised()) {
      // An error was raised in the previous round. Don't try anything for now to avoid adding
      // possible more noise.
      return false
    }

    /*for (type in roundEnv.getElementsAnnotatedWith(viewModelAnnot)) {
      val typeMetadata = type.getAnnotation(Metadata::class.java)
      val kmClass = cachedClassInspector.toKmClass(typeMetadata)
      val typeSpec = kmClass.toTypeSpec(cachedClassInspector.classInspector)
      val className = type.asType().asTypeName().rawType()
      val viewModelAnnotType = type.getAnnotation(viewModelAnnot)
      val fileName = className.simpleName + "BindingModule"
      val vmInterface = try {
        viewModelAnnotType.viewModelInterface.asTypeName()
      } catch (e: MirroredTypeException) {
        e.typeMirror.asTypeName()
      }

      if (types.directSupertypes(type.asType()).isEmpty()) {
        messager.printMessage(
          Diagnostic.Kind.ERROR, "${className.packageName}.${className.simpleName} in annotated with ContributesViewModel but it does not implement interface ${vmInterface.findRawType()?.simpleName}",
          type)
      }

      val isVmBoundInterface = try {
        viewModelAnnotType.viewModelInterface.toKmClass().isInterface
      } catch (e: MirroredTypeException) {
        (types.asElement(e.typeMirror) as TypeElement).toKmClass().isInterface
      }

      if (!isVmBoundInterface) {
        messager.printMessage(
          Diagnostic.Kind.ERROR, "${className.packageName}.${className.simpleName} in annotated with ContributesViewModel but it does not implement interface ${vmInterface.findRawType()?.simpleName}",
          type)
      }

      val viewModel = ClassName("androidx.lifecycle", "ViewModel")
      val langClassName = ClassName("java.lang", "Class")
      val classOfViewModel = langClassName.parameterizedBy(WildcardTypeName.producerOf(viewModel))

      val type1 = TypeSpec.objectBuilder(typeSpec.name + "BindingModuleInternal")
        .addAnnotation(Module::class)
        .addAnnotation(
          AnnotationSpec.builder(ClassName("dagger.hilt", "InstallIn"))
            .addMember("%T::class", ClassName("dagger.hilt.components", "SingletonComponent"))
            .build()
        )
        .addFunction(
          FunSpec.builder("bind")
            .returns(classOfViewModel)
            .addAnnotation(IntoMap::class)
            .addAnnotation(Provides::class)
            .addAnnotation(
              AnnotationSpec.builder(ClassName("com.personatech.base.di", "ViewModelInterfaceKey"))
                .addMember("%T::class", vmInterface)
                .build()
            )
            .addStatement("return ${typeSpec.name}::class.java")
            .build()
        )
        .addOriginatingElement(type)
        .addModifiers(INTERNAL)
        .build()

      val type2 = TypeSpec.objectBuilder(typeSpec.name + "BindingModule")
        .addAnnotation(
          AnnotationSpec.builder(ClassName("dagger.hilt", "InstallIn"))
            .addMember("%T::class", ClassName("dagger.hilt.components", "SingletonComponent"))
            .build()
        )
        .addAnnotation(AnnotationSpec.builder(Module::class)
          .addMember("includes = %L", "[${typeSpec.name }BindingModuleInternal::class]")
          .build()
        )
        .addOriginatingElement(type)
        .build()

      val type3 = TypeSpec.interfaceBuilder(typeSpec.name + "ViewModelBindingModuleInternal")
        .addAnnotation(Module::class)
        .addAnnotation(
          AnnotationSpec.builder(ClassName("dagger.hilt", "InstallIn"))
            .addMember("%T::class", ClassName("dagger.hilt.components", "SingletonComponent"))
            .build()
        )
        .addFunction(
          FunSpec.builder("bind")
            .addParameter(
              "impl",
              className
            )
            .returns(ClassName("androidx.lifecycle", "ViewModel"))
            .addAnnotation(IntoMap::class)
            .addAnnotation(Binds::class)
            .addAnnotation(
              AnnotationSpec.builder(ClassName("com.personatech.base.di", "ViewModelKey"))
                .addMember("%T::class", className)
                .build()
            )
            .addModifiers(KModifier.ABSTRACT)
            .build()
        )
        .addOriginatingElement(type)
        .addModifiers(INTERNAL)
        .build()

      val type4 = TypeSpec.interfaceBuilder(typeSpec.name + "ViewModelBindingModule")
        .addAnnotation(
          AnnotationSpec.builder(ClassName("dagger.hilt", "InstallIn"))
            .addMember("%T::class", ClassName("dagger.hilt.components", "SingletonComponent"))
            .build()
        )
        .addAnnotation(AnnotationSpec.builder(Module::class)
          .addMember("includes = %L", "[${typeSpec.name}ViewModelBindingModuleInternal::class]")
          .build()
        )
        .addOriginatingElement(type)
        .build()

      val filt = FileSpec.builder(className.packageName, fileName)
        .addType(
          type1
        )
        .addType(
          type2
        )
        .addType(
          type3
        )
        .addType(
          type4
        )
        .build()
      filt.writeTo(filer)
    }


    for (type in roundEnv.getElementsAnnotatedWith(contri)) {
      val typeMetadata = type.getAnnotation(Metadata::class.java)
      val kmClass = cachedClassInspector.toKmClass(typeMetadata)
      val typeSpec = kmClass.toTypeSpec(cachedClassInspector.classInspector)
      val className = type.asType().asTypeName().rawType()
      val jsonClass = type.getAnnotation(contri)
      try {
        val allBounds = jsonClass.boundTypes
      } catch (e: MirroredTypesException) {
        val types = e.typeMirrors.forEach {
          var boundType = it.asTypeName()
          if (boundType == Unit::class.asTypeName()) {
            if (typeSpec.superinterfaces.entries.size > 1) {
              //error
              messager.printMessage(
                Diagnostic.Kind.ERROR, "${className.packageName}.${className.simpleName} implements multiple interfaces. Please specify valid bound",
                type)
            }
          }

          if (boundType == Unit::class.asTypeName()) {
            boundType = typeSpec.superinterfaces.entries.first().key
          }

          val baseTypeName = "${boundType.rawType().simpleName}${className.simpleName}"
          val fileName = "${baseTypeName}Binding"

          val functionTypeBuilder = FunSpec.builder("bind")
            .addAnnotation(Binds::class)
            .returns(boundType)
            .addParameter(
              "impl",
              type.asType().asTypeName()
            )
            .addModifiers(ABSTRACT)

          typeSpec.annotationSpecs.forEach {
            if (it.typeName != contri.asTypeName()) {
              functionTypeBuilder.addAnnotation(it)
            }
          }


          val typeBuilder = TypeSpec.interfaceBuilder("${baseTypeName}Internal")
            .addAnnotation(Module::class)
            .addAnnotation(
              AnnotationSpec.builder(ClassName("dagger.hilt", "InstallIn"))
                .addMember("%T::class", ClassName("dagger.hilt.components", "SingletonComponent"))
                .build()
            )
            .addFunction(
              functionTypeBuilder
                .build()
            )
            .addModifiers(INTERNAL)
            .addOriginatingElement(type)

          val typeBuilder1 = TypeSpec.interfaceBuilder(baseTypeName)
            .addAnnotation(AnnotationSpec.builder(Module::class)
              .addMember("includes = %L", "[${baseTypeName}Internal::class]")
              .build()
            )
            .addAnnotation(
              AnnotationSpec.builder(ClassName("dagger.hilt", "InstallIn"))
                .addMember("%T::class", ClassName("dagger.hilt.components", "SingletonComponent"))
                .build()
            )
            .addOriginatingElement(type)

          val filt = FileSpec.builder(className.packageName, fileName)
            .addType(
              typeBuilder.build()
            )
            .addType(
              typeBuilder1.build()
            )
            .build()
          filt.writeTo(filer)
        }
      }
    }*/
    for (type in roundEnv.getElementsAnnotatedWith(annotation)) {
      if (type !is TypeElement) {
        messager.printMessage(
            Diagnostic.Kind.ERROR, "@JsonClass can't be applied to $type: must be a Kotlin class",
            type)
        continue
      }
      val jsonClass = type.getAnnotation(annotation)
      if (jsonClass.generateAdapter && jsonClass.generator.isEmpty()) {
        val generator = adapterGenerator(type, cachedClassInspector) ?: continue
        val preparedAdapter = generator
          .prepare(messager, generateProguardRules) { spec ->
            spec.toBuilder()
              .apply {
                @Suppress("DEPRECATION") // This is a Java type
                generatedType?.let { generatedClassName ->
                  addAnnotation(
                    AnnotationSpec.builder(generatedClassName)
                      .addMember(
                        "value = [%S]",
                        JsonClassCodegenProcessor::class.java.canonicalName
                      )
                      .addMember("comments = %S", "https://github.com/square/moshi")
                      .build()
                  )
                }
              }
              .addOriginatingElement(type)
              .build()
          }

        preparedAdapter.spec.writeTo(filer)
        preparedAdapter.proguardConfig?.writeTo(filer, type)
      }
    }

    return false
  }

  private fun TypeElement.getComposedFieldsRec(): List<Element> {
    val composedFields = enclosedElements.filter { it.getAnnotation(composedAnnotation) != null }.toMutableList()
    composedFields.forEach {
      if (it is TypeElement) {
        composedFields.addAll(it.getComposedFieldsRec())
      }
    }
    return composedFields.toList()
  }

  private fun adapterGenerator(
      element: TypeElement,
      cachedClassInspector: MoshiCachedClassInspector
  ): AdapterGenerator? {
    val type = targetType(messager, elements, types, element, cachedClassInspector) ?: return null
    val composedPropertiesInType = type.properties.values.filter { it.isComposed }.toSet()
    val composedFields = element.enclosedElements.filter { it.getAnnotation(composedAnnotation) != null }
    //val composedFields = element.getComposedFieldsRec()
    val composedTypeElements = composedFields.associateBy { elementEntry ->

      return@associateBy composedPropertiesInType.find { it.name ==  elementEntry.simpleName.toString()}
    }.mapValues {
      return@mapValues elements.getTypeElement(it.value.asType().toString())
    }.mapValues {
      targetType(messager, elements, types, it.value, cachedClassInspector) ?: return null
    }

    val properties = mutableMapOf<String, PropertyGenerator>()
    val composedProperties = mutableMapOf<String, Pair<TargetProperty, PropertyGenerator>>()
    for (property in type.properties.values) {
      val generator = property.generator(messager, element, elements)
      if (generator != null) {
        properties[property.name] = generator
      }
    }

    composedTypeElements.forEach { c ->
      if (c.key != null) {
        for (property in c.value.properties) {
          val generator = property.value.generator(messager, element, elements)
          if (generator != null) {
            composedProperties[property.value.name] = Pair(c.key!!, generator)
          }
        }
      }
    }


    for ((name, parameter) in type.constructor.parameters) {
      if (type.properties[parameter.name] == null && !parameter.hasDefault) {
        messager.printMessage(
            Diagnostic.Kind.ERROR,
            "No property for required constructor parameter $name",
            element)
        return null
      }
    }
    composedTypeElements.values.forEach {
      for ((name, parameter) in it.constructor.parameters) {
        if (it.properties[parameter.name] == null && !parameter.hasDefault) {
          messager.printMessage(
            Diagnostic.Kind.ERROR,
            "No property for required constructor parameter $name",
            element)
          return null
        }
      }
    }

    // Sort properties so that those with constructor parameters come first.
    val sortedProperties = properties.values.sortedBy {
      if (it.hasConstructorParameter) {
        it.target.parameterIndex
      } else {
        Integer.MAX_VALUE
      }
    }

    val sortedComposedProperties = composedProperties.values.sortedBy {
      if (it.second.hasConstructorParameter) {
        it.second.target.parameterIndex
      } else {
        Integer.MAX_VALUE
      }
    }

    //check if composed field type is appropriate
    val composedMap = composedProperties.values.groupBy { it.first }
    composedMap.forEach { entry ->

      if (entry.key.hasDefault) {
        messager.printMessage(
            Diagnostic.Kind.ERROR,
            "Composed field type can not have default values. ${entry.key.name} in ${type.typeName} has default value",
            element)
        return null
      }

      var allOptional = true
      for (en in entry.value) {
        if (!en.second.delegateKey.nullable) {
          allOptional = false
          break
        }
      }
      /*if (allOptional && !entry.key.type.isNullable) {
        messager.printMessage(
          Diagnostic.Kind.ERROR,
          "All composed fields are optional but composing parent type ${entry.key.name} in ${type.typeName} is not optional",
          element)
        return null
      }*/
    }

    return AdapterGenerator(type, sortedProperties, sortedComposedProperties.filter { !it.second.isTransient })
  }
}

/** Writes this config to a [filer]. */
private fun ProguardConfig.writeTo(filer: Filer, vararg originatingElements: Element) {
  filer.createResource(StandardLocation.CLASS_OUTPUT, "", "${outputFilePathWithoutExtension(targetClass.canonicalName)}.pro", *originatingElements)
    .openWriter()
    .use(::writeTo)
}
