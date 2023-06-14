package com.personatech.customannotations

import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

@Target(CLASS)
@Retention(RUNTIME)
annotation class ContributesViewModel(
  val scope: KClass<*>,
  val viewModelInterface: KClass<*>,
  val additionalInterfaces: Array<KClass<*>> = []
)