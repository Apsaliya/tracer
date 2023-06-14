package com.squareup.moshi.kotlin.codegen

import com.google.auto.service.AutoService
import com.personatech.customannotations.ContributesInternalBinding
import com.personatech.customannotations.ContributesMultibinding
import com.personatech.customannotations.ContributorType
import com.personatech.customannotations.ContributorType.Binder
import com.personatech.customannotations.MultibindingType
import com.personatech.customannotations.MultibindingType.Map
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.kotlinpoet.metadata.KotlinPoetMetadataPreview
import com.squareup.kotlinpoet.metadata.classinspectors.ElementsClassInspector
import com.squareup.kotlinpoet.metadata.isCompanionObject
import com.squareup.kotlinpoet.metadata.toKmClass
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoMap
import dagger.multibindings.IntoSet
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
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.Modifier.STATIC
import javax.lang.model.element.Name
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypesException
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic

@KotlinPoetMetadataPreview
@AutoService(Processor::class)
@IncrementalAnnotationProcessor(ISOLATING)
class MultiBindingsCodegenProcessor : AbstractProcessor() {
  private lateinit var types: Types
  private lateinit var elements: Elements
  private lateinit var filer: Filer
  private lateinit var messager: Messager
  private lateinit var cachedClassInspector: MoshiCachedClassInspector
  private val contri = ContributesMultibinding::class.java

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

      try {
        val allBounds = jsonClass.boundTypes
      } catch (e: MirroredTypesException) {
        val typeMirrors = e.typeMirrors
        if (e.typeMirrors.isEmpty()) {

          if (type.interfaces.size > 1) {
            //error
            messager.printMessage(
              Diagnostic.Kind.ERROR, "${className.packageName()}.${className.simpleName()} implements multiple interfaces. Please specify valid bound",
              type)
          }

          val firstInterface = type.interfaces.firstOrNull() ?: type.superclass
          val boundType = TypeName.get(firstInterface)
          generateBindingAndWriteToFiler(type, boundType, types.asElement(firstInterface).simpleName, jsonClass.contributorType, jsonClass.bindingType)
        } else {
          e.typeMirrors.forEach {
            val boundType = TypeName.get(it)
            generateBindingAndWriteToFiler(type, boundType, types.asElement(it).simpleName, jsonClass.contributorType, jsonClass.bindingType)
          }
        }

      }
    }

    return true
  }

  private fun generateBindingAndWriteToFiler(
    type: Element,
    boundType: TypeName,
    boundTypeName: Name,
    contriButorType: ContributorType,
    bindingType: MultibindingType
  ) {

    val bindingAnnotation = when (bindingType) {
      Map -> {
        IntoMap::class.java
      }

      MultibindingType.Set -> {
        IntoSet::class.java
      }
    }


    val methodSpec = if (contriButorType == Binder) {
      MethodSpec.methodBuilder("bind${boundTypeName}")
        .addModifiers(PUBLIC, STATIC)
        .addAnnotation(Provides::class.java)
        .addAnnotation(bindingAnnotation)
        .addParameter(TypeName.get(type.asType()), "impl")
        .addStatement("return impl")
        .returns(boundType)
    } else {
      val typeMetadata = type.getAnnotation(Metadata::class.java)
      val kmClass = typeMetadata.toKmClass()

      val returnString = if (kmClass.isCompanionObject) {
        "return \$T"
      } else {
        "return \$T.INSTANCE"
      }

      MethodSpec.methodBuilder("provide${boundTypeName}")
        .addModifiers(PUBLIC, STATIC)
        .addAnnotation(Provides::class.java)
        .addAnnotation(bindingAnnotation)
        .addStatement(returnString, TypeName.get(type.asType()))
        .returns(boundType)
    }

    type.annotationMirrors.forEach {
      /*messager.printMessage(
        Diagnostic.Kind.WARNING, "--annotation mirro ${it.annotationType.asElement().simpleName}---key mirrpr ${types.asElement(mapKeyTypeMirror).simpleName}",
        type)*/
      val isContributesInternalMultiBinding = TypeName.get(it.annotationType) == TypeName.get(ContributesMultibinding::class.java)
      val isContributesInternalBinding = TypeName.get(it.annotationType) == TypeName.get(ContributesInternalBinding::class.java)
      val isKotlinMetaData = TypeName.get(it.annotationType) == TypeName.get(Metadata::class.java)
      if (!isContributesInternalBinding && !isKotlinMetaData && !isContributesInternalMultiBinding) {
        methodSpec.addAnnotation(AnnotationSpec.get(it))
      }
    }

    val binderClassName = ClassName.get(type as TypeElement).enclosingClassName()?.simpleName() ?: type.simpleName.toString()

    val bindingModule = TypeSpec.interfaceBuilder("${boundTypeName}${binderClassName}MultiBindingModule")
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