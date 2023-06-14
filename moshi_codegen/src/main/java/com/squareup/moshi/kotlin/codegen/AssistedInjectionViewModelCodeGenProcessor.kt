package com.squareup.moshi.kotlin.codegen

import com.google.auto.service.AutoService
import com.personatech.customannotations.PradarshanAssistedFactory
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import dagger.Module
import dagger.Provides
import dagger.assisted.AssistedFactory
import dagger.multibindings.IntoMap
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
import javax.lang.model.element.ElementKind.METHOD
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier.ABSTRACT
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.Modifier.STATIC
import javax.lang.model.element.TypeElement
import javax.lang.model.type.MirroredTypeException
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic

@AutoService(Processor::class)
@IncrementalAnnotationProcessor(ISOLATING)
class AssistedInjectionViewModelCodeGenProcessor : AbstractProcessor()  {

  private lateinit var types: Types
  private lateinit var elements: Elements
  private lateinit var filer: Filer
  private lateinit var messager: Messager
  private val assistedFactoryViewModel = PradarshanAssistedFactory::class.java
  private var generatedType: ClassName? = null

  override fun getSupportedAnnotationTypes() = setOf(assistedFactoryViewModel.canonicalName)

  override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latest()

  override fun getSupportedOptions() = setOf("pt.assisted.vm.generated")

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

    for (type in roundEnv.getElementsAnnotatedWith(assistedFactoryViewModel)) {
      val assistedInjectAnnotType = type.getAnnotation(assistedFactoryViewModel)
      try {
        assistedInjectAnnotType.viewModelImplementation
      } catch (e: MirroredTypeException) {
        val implementingViewModelTypeMirror = e.typeMirror
        val implementingViewModelClassName = ClassName.get(implementingViewModelTypeMirror)
        val allFactoryMethods = type.enclosedElements.filter { it.kind == METHOD }

        val daggerAssistedFactoryTypeBuilder = TypeSpec.interfaceBuilder("Dagger${type.simpleName}")
          .addAnnotation(AssistedFactory::class.java)
          .addModifiers(PUBLIC)
          .addSuperinterface(ClassName.get(type as TypeElement))
          .addOriginatingElement(type)

        allFactoryMethods.forEach {
          val methodName = it.simpleName.toString()
          val vmInterfaceModuleMethod = MethodSpec.methodBuilder(methodName)
            .addModifiers(PUBLIC, ABSTRACT)
            .addAnnotation(Override::class.java)
            .returns(implementingViewModelClassName)

          val size = (it as? ExecutableElement)?.parameters?.size
          messager.printMessage(
            Diagnostic.Kind.WARNING,
            "it.enclosedElements size --PP ${size}",
            type)
          (it as ExecutableElement).parameters.forEach { pe ->
            messager.printMessage(
              Diagnostic.Kind.WARNING,
              "it.name ${pe.simpleName}---type ${pe.asType()}",
              type)
          }

          (it).parameters.forEach { parameterElement ->
            vmInterfaceModuleMethod.addParameter(
              TypeName.get(parameterElement.asType()), parameterElement.simpleName.toString()
            )
          }
          daggerAssistedFactoryTypeBuilder.addMethod(vmInterfaceModuleMethod.build())
        }

        val javaFile = JavaFile.builder(ClassName.get(type).packageName(), daggerAssistedFactoryTypeBuilder.build())
          .build()

        javaFile.writeTo(filer)

        val objectClassName = ClassName.get("java.lang", "Object")
        val vmInterfaceModuleMethod = MethodSpec.methodBuilder("bind")
          .addModifiers(PUBLIC, STATIC)
          .addAnnotation(IntoMap::class.java)
          .addAnnotation(Provides::class.java)
          .addAnnotation(
            AnnotationSpec.builder(Named::class.java)
              .addMember("value", CodeBlock.of("\"assistedFactories\""))
              .build()
          )
          .addAnnotation(
            AnnotationSpec.builder(ClassName.get("com.personatech.base.di", "PradarshanAssistedFactoryKey"))
              .addMember("value", "\$T.class", ClassName.get(type))
              .build()
          )
          .addParameter(ClassName.get(ClassName.get(type).packageName(), "Dagger${type.simpleName}"), "impl")
          .addStatement("return impl")
          .returns(objectClassName)

        val mapperModule = TypeSpec.classBuilder("${(type).simpleName}DaggerFactoryClassMappingModule")
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

        val mapperModuleJavaFile = JavaFile.builder(ClassName.get(type).packageName(), mapperModule)
          .build()

        mapperModuleJavaFile.writeTo(filer)
      }
    }

    return true
  }

}