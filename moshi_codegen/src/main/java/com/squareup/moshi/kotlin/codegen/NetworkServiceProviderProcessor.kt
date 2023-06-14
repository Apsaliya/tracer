package com.squareup.moshi.kotlin.codegen

import com.google.auto.service.AutoService
import com.personatech.customannotations.NetworkService
import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.WildcardTypeName
import dagger.Module
import dagger.Provides
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType.ISOLATING
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.inject.Named
import javax.inject.Singleton
import javax.lang.model.SourceVersion
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.Modifier.STATIC
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic

@AutoService(Processor::class)
@IncrementalAnnotationProcessor(ISOLATING)
class NetworkServiceProviderProcessor : AbstractProcessor() {
  private lateinit var types: Types
  private lateinit var elements: Elements
  private lateinit var filer: Filer
  private lateinit var messager: Messager
  private val networkServiceAnnot = NetworkService::class.java

  override fun getSupportedAnnotationTypes() = setOf(networkServiceAnnot.canonicalName)

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

    for (type in roundEnv.getElementsAnnotatedWith(networkServiceAnnot)) {
      val supportedScopes = setOf("loggedIn", "loggedOut")
      val className = ClassName.get(type as TypeElement)
      val classNameSuperWildCard = WildcardTypeName.supertypeOf(className)
      val networkServiceAnnotationType = type.getAnnotation(networkServiceAnnot)
      val scope = networkServiceAnnotationType.scope

      if (!supportedScopes.contains(scope)) {
        messager.printMessage(
          Diagnostic.Kind.ERROR, "${className.packageName()}.${className.simpleName()} in annotated with NetworkScope and has invalid scope $scope. Supported scopes are $supportedScopes",
          type)
        continue
      }

      val networkServiceGeneratorClassName = ClassName.get("com.personatech.networkapi", "NetworkServiceGenerator")
      val networkServiceProviderClassName = ClassName.get("com.personatech.networkapi", "NetworkServiceProvider")
      val networkServiceProviderReturnType = ParameterizedTypeName.get(networkServiceProviderClassName, className)
      val continuationClassName = ClassName.get("kotlin.coroutines", "Continuation")
      val notNullAnnotation = ClassName.get("androidx.annotation", "NonNull")
      val function2ClassName = ClassName.get("kotlin.jvm.functions", "Function2")
      val coroutineScopeClassName = ClassName.get("kotlinx.coroutines", "CoroutineScope")
      val buildersKtClassName = ClassName.get("kotlinx.coroutines", "BuildersKt")
      val coroutineContextClassName = ClassName.get("kotlin.coroutines", "CoroutineContext")
      val dispatchersClassName = ClassName.get("kotlinx.coroutines", "Dispatchers")


      val function2Builder = TypeSpec.anonymousClassBuilder("")
        .addSuperinterface(ParameterizedTypeName.get(function2ClassName, coroutineScopeClassName, ParameterizedTypeName.get(continuationClassName, classNameSuperWildCard), className))
        .addMethod(
          MethodSpec.methodBuilder("invoke")
            .addModifiers(PUBLIC)
            .addAnnotation(Override::class.java)
            .addParameter(coroutineScopeClassName, "coroutineScope")
            .addParameter(ParameterizedTypeName.get(continuationClassName, classNameSuperWildCard), "continuation")
            .addStatement("return (\$T) generator.get(\$T.class, continuation)", className, className)
            .returns(className)
            .build()
        )
        .build()



      val annonysServiceProvider = TypeSpec.anonymousClassBuilder("")
        .addSuperinterface(networkServiceProviderReturnType)
        .addMethod(
          MethodSpec.methodBuilder("get")
            .addModifiers(PUBLIC)
            .addAnnotation(Override::class.java)
            .addParameter(
              ParameterSpec.builder(ParameterizedTypeName.get(continuationClassName, classNameSuperWildCard), "continuation")
                .addAnnotation(AnnotationSpec.builder(notNullAnnotation).build())
                .build()
            )
            .addStatement("return (\$T) \$T.withContext((\$T) \$T.getIO(), \$L, continuation)", className, buildersKtClassName, coroutineContextClassName, dispatchersClassName, function2Builder)
            .returns(className)
            .build()
        )
        .build()


      val networkServiceProviderBuilder = MethodSpec.methodBuilder("provide")
        .addModifiers(PUBLIC, STATIC)
        .addAnnotation(
          AnnotationSpec.builder(SuppressWarnings::class.java)
            .addMember("value", CodeBlock.of("{ \"Convert2Lambda\", \"Convert2Diamond\" }"))
            .build()
        )
        .addAnnotation(Provides::class.java)
        .addAnnotation(Singleton::class.java)
        .addParameter(
          ParameterSpec.builder(networkServiceGeneratorClassName, "generator")
            .addAnnotation(
              AnnotationSpec.builder(Named::class.java)
                .addMember("value", CodeBlock.of("\"${scope}\""))
                .build()
            )
            .build()
        )
        .addStatement("return \$L", annonysServiceProvider)
        .returns(networkServiceProviderReturnType)

      type.annotationMirrors.forEach {
        val isNetworkServiceAnnotation = TypeName.get(it.annotationType) == TypeName.get(
          NetworkService::class.java)
        val isKotlinMetaData = TypeName.get(it.annotationType) == TypeName.get(Metadata::class.java)
        if (!isNetworkServiceAnnotation && !isKotlinMetaData) {
          networkServiceProviderBuilder.addAnnotation(AnnotationSpec.get(it))
        }
      }

      val moduleType = TypeSpec.interfaceBuilder("${className.simpleName()}NetworkServiceProviderModule")
        .addMethod(networkServiceProviderBuilder.build())
        .addAnnotation(Module::class.java)
        .addAnnotation(
          AnnotationSpec.builder(ClassName.get("dagger.hilt", "InstallIn"))
            .addMember("value", "\$T.class", ClassName.get("dagger.hilt.components", "SingletonComponent"))
            .build()
        )
        .addModifiers(PUBLIC)
        .addOriginatingElement(type)
        .build()

      val moduleFile = JavaFile.builder(ClassName.get(type).packageName(), moduleType)
        .build()

      moduleFile.writeTo(filer)
    }

    return true
  }
}