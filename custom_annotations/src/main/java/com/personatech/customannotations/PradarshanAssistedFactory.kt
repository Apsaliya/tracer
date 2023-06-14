package com.personatech.customannotations

import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

@Target(CLASS)
@Retention(RUNTIME)
annotation class PradarshanAssistedFactory(
  val scope: KClass<*>,
  val viewModelImplementation: KClass<*>
)