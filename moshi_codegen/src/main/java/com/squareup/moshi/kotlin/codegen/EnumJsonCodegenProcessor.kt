package com.squareup.moshi.kotlin.codegen

import com.google.auto.service.AutoService
import com.personatech.customannotations.JsonEnumWithFallback
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.WildcardTypeName
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import com.squareup.kotlinpoet.metadata.classinspectors.ElementsClassInspector
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType.ISOLATING
import org.jetbrains.annotations.NotNull
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.inject.Inject
import javax.lang.model.SourceVersion
import javax.lang.model.element.ElementKind
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic

@KotlinPoetMetadataPreview
@AutoService(Processor::class)
@IncrementalAnnotationProcessor(ISOLATING)
class EnumJsonCodegenProcessor : AbstractProcessor() {
  private lateinit var types: Types
  private lateinit var elements: Elements
  private lateinit var filer: Filer
  private lateinit var messager: Messager
  private lateinit var cachedClassInspector: MoshiCachedClassInspector
  private val contri = JsonEnumWithFallback::class.java

  override fun getSupportedAnnotationTypes() = setOf(contri.canonicalName)

  override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latest()

  override fun getSupportedOptions() = setOf("pt.binding.generated")

  override fun init(processingEnv: ProcessingEnvironment) {
    super.init(processingEnv)

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


    for (type in roundEnv.getElementsAnnotatedWith(contri)) {
      val className = ClassName.get(type as TypeElement)
      val jsonClass = type.getAnnotation(contri) ?: return false

      if (type.kind != ElementKind.ENUM) {
        messager.printMessage(
          Diagnostic.Kind.ERROR, "EnumJsonWithFallback is only applicable for enums. Found it declared on unsupported type \"${className.packageName()}.${className.simpleName()}",
          type)
      }

      val fallbackEnumName = jsonClass.fallback
      val fallback = type.enclosedElements.filter { it.kind == ElementKind.ENUM_CONSTANT }
        .firstOrNull { it.simpleName.toString() == fallbackEnumName }

      if (fallback == null) {
        messager.printMessage(
          Diagnostic.Kind.ERROR, "Invalid EnumJsonWithFallback configuration. \"${className.packageName()}.${className.simpleName()} does not have any enum constant $fallbackEnumName",
          type)
      }


      val jsonAdapterKey = ClassName.get("com.personatech.core.di", "JsonAdapterKey")
      val contributerType = ClassName.get("com.personatech.customannotations", "ContributorType")
      val contributesMultibinding = ClassName.get("com.personatech.customannotations", "ContributesMultibinding")
      val jsonAdapterInterface = ClassName.get("com.personatech.core.di", "PradarshanMoshiAdapter")
      val jsonAdapterClassName = ClassName.get("com.squareup.moshi", "JsonAdapter")
      val enumJsonAdapterClassName = ClassName.get("com.personatech.core.moshiadapters", "EnumJsonAdapter")
      val binderClassName = ClassName.get(type).enclosingClassName()?.simpleName() ?: type.simpleName.toString()
      val superClassTypeName = ParameterizedTypeName.get(
        enumJsonAdapterClassName,
        className
      )
      val wildCardTypeName = WildcardTypeName.subtypeOf(Object::class.java)

      val constructor = MethodSpec.constructorBuilder()
        .addModifiers(PUBLIC)
        .addAnnotation(Inject::class.java)
        .addStatement("super(\$T.class, \$T.${fallbackEnumName}, true)", className, className)
        .build()

      val provideAdapterMethod = MethodSpec.methodBuilder("provideAdapter")
          .addAnnotation(Override::class.java)
          .addAnnotation(NotNull::class.java)
          .addModifiers(PUBLIC)
          .addStatement("return this.nullSafe()")
          .returns(ParameterizedTypeName.get(jsonAdapterClassName, wildCardTypeName))
          .build()

      val jsonAdapterKeyAnnotation = AnnotationSpec.builder(jsonAdapterKey)
        .addMember("type", "\$T.class", className)
        .build()

      val contributesMultibindingAnnotation = AnnotationSpec.builder(contributesMultibinding)
        .addMember("boundTypes", "{ \$T.class }", jsonAdapterInterface)
        .addMember("contributorType", "\$T.Binder", contributerType)
        .build()

      val adapterClass = TypeSpec.classBuilder("${binderClassName}JsonAdapter")
        .addAnnotation(jsonAdapterKeyAnnotation)
        .addAnnotation(contributesMultibindingAnnotation)
        .superclass(superClassTypeName)
        .addSuperinterface(jsonAdapterInterface)
        .addMethod(constructor)
        .addMethod(provideAdapterMethod)
        .addModifiers(PUBLIC)
        .addOriginatingElement(type)
        .build()

      val javaFile = JavaFile.builder(className.packageName(), adapterClass)
        .build()

      javaFile.writeTo(filer)
    }

    return true
  }
}